/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import java.io.{File, FileInputStream}
import java.net.URI
import java.nio.file.{Files, StandardCopyOption}
import java.time.{Instant, ZonedDateTime}

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.jcraft.jsch.{JSch, Session}
import javax.inject.{Inject, Singleton}
import models.{Metadata, MetadataVersion, Program}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.{JschConfigSessionFactory, OpenSshConfig, SshTransport}
import org.eclipse.jgit.util.FS
import play.api.libs.json.Json
import play.api.{Configuration, Environment, Mode}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GitMetadata @Inject()(configuration: Configuration, environment: Environment, actorSystem: ActorSystem)(implicit ec: ExecutionContext) {

  private implicit val timeout: Timeout = 15.seconds

  private lazy val actor = actorSystem.actorOf(Props(new GitMetadataActor(configuration, environment)))

  def allVersions: Future[Set[MetadataVersion]] = {
    (actor ? GitMetadata.GetAllVersions).mapTo[Set[MetadataVersion]]
  }

  def latestVersion: Future[(Option[ObjectId], Metadata)] = {
    allVersions.flatMap { versions =>
      val maybeVersion = versions.maxBy(_.date.toEpochSecond).id
      fetchMetadata(maybeVersion).map(maybeVersion -> _)
    }
  }

  def fetchMetadata(maybeVersion: Option[ObjectId]): Future[Metadata] = {
    (actor ? GitMetadata.GetVersion(maybeVersion)).mapTo[Metadata]
  }

  def fetchProgram(maybeVersion: Option[ObjectId], programKey: String): Future[Program] = {
    fetchMetadata(maybeVersion).flatMap(_.program(programKey))
  }

}

class GitMetadataActor(configuration: Configuration, environment: Environment) extends Actor {

  private implicit val ec = this.context.system.dispatcher

  lazy val metadataGitUri: URI = configuration.getOptional[String]("metadata-git-uri").map(new URI(_)).getOrElse {
    if (environment.mode == Mode.Prod)
      throw new Exception("metadata-git-uri must be set in prod mode")
    else
      environment.rootPath.toURI
  }

  lazy val metadataGitFile = configuration.getOptional[String]("metadata-git-file").getOrElse {
    if (environment.mode == Mode.Prod)
      throw new Exception("metadata-git-file must be set in prod mode")
    else
      "examples/metadata.json"
  }

  lazy val maybeMetadataGitSshKey = configuration.getOptional[String]("metadata-git-ssh-key")


  lazy val gitRepo = {
    val cacheDir = Files.createTempDirectory("git").toFile

    val maybeSshSessionFactory = maybeMetadataGitSshKey.map { metadataGitSshKey =>
      new JschConfigSessionFactory() {
        override def configure(hc: OpenSshConfig.Host, session: Session): Unit = {
          session.setConfig("StrictHostKeyChecking", "no")
        }

        override def createDefaultJSch(fs: FS): JSch = {
          val jsch = new JSch()
          jsch.addIdentity("key", metadataGitSshKey.getBytes, Array.emptyByteArray, Array.emptyByteArray)
          jsch
        }
      }
    }

    val (gitUri, branch) = if (metadataGitUri.getFragment != null) {
      new URI(metadataGitUri.getScheme, metadataGitUri.getUserInfo, metadataGitUri.getHost, metadataGitUri.getPort, metadataGitUri.getPath, null, null) -> metadataGitUri.getFragment
    }
    else if (environment.mode == Mode.Test && sys.env.get("HEROKU_TEST_RUN_BRANCH").isDefined) {
      new URI(metadataGitUri.getScheme, metadataGitUri.getUserInfo, metadataGitUri.getHost, metadataGitUri.getPort, metadataGitUri.getPath, null, null) -> sys.env("HEROKU_TEST_RUN_BRANCH")
    }
    else {
      metadataGitUri -> "master"
    }

    val baseClone = Git.cloneRepository()
      .setURI(gitUri.toString)
      .setDirectory(cacheDir)
      .setBranchesToClone(Seq(s"refs/heads/$branch").asJava)
      .setBranch(s"refs/heads/$branch")

    val clone = maybeSshSessionFactory.fold(baseClone) { sshSessionFactory =>
      baseClone.setTransportConfigCallback { transport =>
        val sshTransport = transport.asInstanceOf[SshTransport]
        sshTransport.setSshSessionFactory(sshSessionFactory)
      }
    }

    clone.call()

    if (metadataGitUri.getScheme == "file") {
      // copy the local working metadata file
      val src = new File(new File(metadataGitUri), metadataGitFile)
      val dst = new File(cacheDir, metadataGitFile)
      Files.copy(src.toPath, dst.toPath, StandardCopyOption.REPLACE_EXISTING)
    }

    Git.open(cacheDir)
  }

  def readMetadata: Metadata = {
    val metadataFile = new File(gitRepo.getRepository.getWorkTree, metadataGitFile)
    if (metadataFile.exists()) {
      val fis = new FileInputStream(metadataFile)
      val metadata = Json.parse(fis).as[Metadata]
      fis.close()
      metadata
    }
    else {
      throw new Exception(metadataGitFile + " does not exist")
    }
  }

  def versions: Set[MetadataVersion] = {
    val versions = gitRepo.log().all().addPath(metadataGitFile).call().asScala.map { revCommit =>
      val datetime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(revCommit.getCommitTime), revCommit.getAuthorIdent.getTimeZone.toZoneId)
      MetadataVersion(Some(revCommit.getId), datetime)
    }.toSet

    if (metadataGitUri.getScheme == "file") {
      // there may be local changes to the metadata
      versions + MetadataVersion(None, ZonedDateTime.now())
    }
    else {
      versions
    }
  }

  def fetchMetadata(maybeVersion: Option[ObjectId]): Metadata = {
    maybeVersion.fold {
      if (environment.mode == Mode.Prod) {
        throw new Exception("The metadata version should always be specified in prod mode")
      }
      else {
        if (metadataGitUri.getScheme == "file") {
          val src = new File(new File(metadataGitUri), metadataGitFile)
          val dst = new File(gitRepo.getRepository.getWorkTree, metadataGitFile)
          Files.copy(src.toPath, dst.toPath, StandardCopyOption.REPLACE_EXISTING)
        }
        readMetadata
      }
    } { version =>
      gitRepo.reset().setRef(version.name()).setMode(ResetType.HARD).call()
      readMetadata
    }
  }

  override def receive: Receive = {
    case GitMetadata.GetVersion(maybeVersion) => sender ! fetchMetadata(maybeVersion)
    case GitMetadata.GetAllVersions => sender ! versions
  }

  override def postStop(): Unit = {
    gitRepo.close()
  }

}

object GitMetadata {
  case class GetVersion(maybeVersion: Option[ObjectId])
  case object GetAllVersions
}
