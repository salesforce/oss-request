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

import com.jcraft.jsch.{JSch, Session}
import javax.inject.{Inject, Singleton}
import models.{Metadata, MetadataVersion, Program}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.{JschConfigSessionFactory, OpenSshConfig, SshTransport}
import org.eclipse.jgit.util.FS
import play.api.{Configuration, Environment, Mode}
import play.api.cache.SyncCacheApi
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@Singleton
class GitMetadata @Inject() (cache: SyncCacheApi, configuration: Configuration, environment: Environment) (implicit ec: ExecutionContext) {

  lazy val cacheDir = Files.createTempDirectory("git")

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

  def withGitRepo[T](f: Git => T): Future[T] = {
    withGitRepoF { gitRepo =>
      Future.fromTry {
        Try {
          f(gitRepo)
        }
      }
    }
  }

  def withGitRepoF[T](f: Git => Future[T]): Future[T] = {
    val baseDir = new File(cacheDir.toFile, "metadata")

    val baseDirFuture = if (!baseDir.exists()) {
      Future.fromTry {
        Try {

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

          val tmpBaseDir = Files.createTempDirectory("git").toFile

          val baseClone = Git.cloneRepository()
            .setURI(gitUri.toString)
            .setDirectory(tmpBaseDir)
            .setBranch(branch)

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
            val dst = new File(tmpBaseDir, metadataGitFile)
            Files.copy(src.toPath, dst.toPath, StandardCopyOption.REPLACE_EXISTING)
          }

          tmpBaseDir.renameTo(baseDir)

          baseDir
        }
      }
    }
    else {
      Future.successful(baseDir)
    }

    baseDirFuture.flatMap { baseDir =>
      Future.fromTry {
        Try {
          Git.open(baseDir)
        }
      }
    } flatMap { gitRepo =>
      val future = f(gitRepo)
      future.onComplete(_ => gitRepo.close())
      future
    }
  }

  def readMetadata(gitRepo: Git): Future[Metadata] = {
    val metadataFile = new File(gitRepo.getRepository.getWorkTree, metadataGitFile)
    if (metadataFile.exists()) {
      val fis = new FileInputStream(metadataFile)
      val metadataTry = Try(Json.parse(fis).as[Metadata])
      fis.close()
      Future.fromTry(metadataTry)
    }
    else {
      Future.failed(new Exception(metadataGitFile + " does not exist"))
    }
  }

  def versions(gitRepo: Git): Set[MetadataVersion] = {
    val versions = gitRepo.log().addPath(metadataGitFile).call().asScala.map { revCommit =>
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

  def latestMetadata: Future[(Option[ObjectId], Metadata)] = {
    withGitRepoF { gitRepo =>
      val maybeVersion = versions(gitRepo).maxBy(_.date.toEpochSecond).id
      fetchMetadata(gitRepo, maybeVersion).map(maybeVersion -> _)
    }
  }

  def withLatestMetadata[T](f: Metadata => T): Future[T] = {
    latestMetadata.map { case (_, metadata) =>
      f(metadata)
    }
  }

  def withLatestMetadataF[T](f: Metadata => Future[T]): Future[T] = {
    latestMetadata.flatMap { case (_, metadata) =>
      f(metadata)
    }
  }

  def fetchMetadata(gitRepo: Git, maybeVersion: Option[ObjectId]): Future[Metadata] = {
    maybeVersion.fold {
      if (environment.mode == Mode.Prod) {
        Future.failed(new Exception("The metadata version should always be specified in prod mode"))
      }
      else {
        if (metadataGitUri.getScheme == "file") {
          val src = new File(new File(metadataGitUri), metadataGitFile)
          val dst = new File(gitRepo.getRepository.getWorkTree, metadataGitFile)
          Files.copy(src.toPath, dst.toPath, StandardCopyOption.REPLACE_EXISTING)
        }
        readMetadata(gitRepo)
      }
    } { version =>
      Future.fromTry {
        Try {
          gitRepo.reset().setRef(version.name()).setMode(ResetType.HARD).call()
        }
      }.flatMap(_ => readMetadata(gitRepo))
    }
  }

  def fetchMetadata(maybeVersion: Option[ObjectId]): Future[Metadata] = {
    withGitRepoF(fetchMetadata(_, maybeVersion))
  }

  def fetchProgram(maybeVersion: Option[ObjectId], programKey: String): Future[Program] = {
    fetchMetadata(maybeVersion).flatMap(_.program(programKey))
  }

}
