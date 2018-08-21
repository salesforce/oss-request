/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.io.{File, FileInputStream}
import java.nio.file.Files

import com.jcraft.jsch.{JSch, Session}
import javax.inject.{Inject, Singleton}
import models.Task.CompletableByType
import models.{State, Task}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.{JschConfigSessionFactory, OpenSshConfig, SshTransport}
import org.eclipse.jgit.util.FS
import play.api.cache.SyncCacheApi
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.{Configuration, Environment, Mode}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class Metadata(programs: Map[String, Program])

object Metadata {
  val multiprogramReads = Reads.mapReads[Program](Program.jsonReads).map(Metadata(_))
  val singleprogramReads = Program.jsonReads.map { program =>
    Metadata(Map("default" -> program))
  }

  implicit val jsonReads: Reads[Metadata] = multiprogramReads.orElse(singleprogramReads)
}

case class Program(name: String, description: Option[String], startTasks: Set[String], groups: Map[String, Set[String]], services: Map[String, String], tasks: Map[String, Task.Prototype], reports: Map[String, Report]) {
  val admins: Set[String] = groups.getOrElse("admin", Set.empty[String])

  def isAdmin(userInfo: UserInfo): Boolean = isAdmin(userInfo.email)
  def isAdmin(email: String): Boolean = admins.contains(email)

  def completableBy(completableBy: (CompletableByType.CompletableByType, String)): Option[Set[String]] = {
    val (completableByType, completableByValue) = completableBy
    completableByType match {
      case models.Task.CompletableByType.Group =>
        groups.get(completableByValue)
      case models.Task.CompletableByType.Email =>
        Some(Set(completableByValue))
      case models.Task.CompletableByType.Service =>
        services.get(completableByValue).map(Set(_))
    }
  }

  def descriptionMarkdown: Option[String] = description.map(MarkdownTransformer.transform)
}

object Program {
  implicit val jsonReads: Reads[Program] = (
    (__ \ "name").read[String].orElse(Reads.pure("Default")) ~
    (__ \ "description").readNullable[String] ~
    (__ \ "start_tasks").read[Set[String]].orElse(Reads.pure(Set.empty[String])) ~
    (__ \ "groups").read[Map[String, Set[String]]] ~
    (__ \ "services").read[Map[String, String]].orElse(Reads.pure(Map.empty[String, String])) ~
    (__ \ "tasks").read[Map[String, Task.Prototype]] ~
    (__ \ "reports").read[Map[String, Report]].orElse(Reads.pure(Map.empty[String, Report]))
  )(Program.apply _)
}

case class ReportQuery(state: Option[State.State], program: Option[String], data: JsObject)

object ReportQuery {
  implicit val jsonReads: Reads[ReportQuery] = (
    (__ \ "state").readNullable[State.State] ~
    (__ \ "program").readNullable[String] ~
    (__ \ "data").read[JsObject].orElse(Reads.pure(JsObject.empty))
  )(ReportQuery.apply _)
}


case class Report(title: String, query: ReportQuery)

object Report {
  implicit val jsonReads: Reads[Report] = Json.reads[Report]
}

@Singleton
class MetadataService @Inject() (cache: SyncCacheApi, configuration: Configuration, environment: Environment) (implicit ec: ExecutionContext) {

  val defaultMetadataFile = "examples/metadata.json"

  lazy val cacheDir = Files.createTempDirectory("git")

  lazy val maybeMetadataGitUrl = configuration.getOptional[String]("metadata-git-url").orElse {
    if (environment.mode == Mode.Prod)
      throw new Exception("metadata-git-url must be set in prod mode")
    else
      None
  }
  lazy val maybeMetadataGitFile = configuration.getOptional[String]("metadata-git-file")
  lazy val maybeMetadataGitSshKey = configuration.getOptional[String]("metadata-git-ssh-key")

  def localMetadata: Future[Metadata] = {
    environment.getExistingFile(defaultMetadataFile).fold(Future.failed[Metadata](new Exception(s"Could not open $defaultMetadataFile"))) { metadataFile =>
      val metadataTry = Try {
        val fileInputStream = new FileInputStream(metadataFile)
        val json = Json.parse(fileInputStream)
        fileInputStream.close()
        json.as[Metadata](Metadata.jsonReads)
      }
      Future.fromTry(metadataTry)
    }
  }

  def fetchMetadata: Future[Metadata] = {
    maybeMetadataGitUrl.fold(localMetadata) { metadataGitUrl =>

      def readMetadata(metadataGitFile: String, metdataGitSshKey: String): Future[Metadata] = {
        val baseDir = new File(cacheDir.toFile, "metadata")

        val baseDirFuture = if (!baseDir.exists()) {
          Future.fromTry {
            Try {

              val sshSessionFactory = new JschConfigSessionFactory() {
                override def configure(hc: OpenSshConfig.Host, session: Session): Unit = {
                  session.setConfig("StrictHostKeyChecking", "no")
                }

                override def createDefaultJSch(fs: FS): JSch = {
                  val jsch = new JSch()
                  jsch.addIdentity("key", metdataGitSshKey.getBytes, Array.emptyByteArray, Array.emptyByteArray)
                  jsch
                }
              }

              val (gitUrl, branch) = if (metadataGitUrl.contains("#")) {
                val parts = metadataGitUrl.split("#")
                parts.head -> parts.last
              }
              else {
                metadataGitUrl -> "master"
              }

              val clone = Git.cloneRepository()
                .setURI(gitUrl)
                .setDirectory(baseDir)
                .setBranch(branch)
                .setTransportConfigCallback { transport =>
                  val sshTransport = transport.asInstanceOf[SshTransport]
                  sshTransport.setSshSessionFactory(sshSessionFactory)
                }

              clone.call()

              baseDir
            }
          }
        }
        else {
          Future.successful(baseDir)
        }

        baseDirFuture.flatMap { baseDir =>
          val metadataFile = new File(baseDir, metadataGitFile)
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
      }

      def cached(metadataGitFile: String, metdataGitSshKey: String): Future[Metadata] = {
        cache.get[Metadata]("metadata").fold[Future[Metadata]] {
          val f = readMetadata(metadataGitFile, metdataGitSshKey)
          f.foreach(cache.set("metadata", _, 5.minutes))
          f
        } (Future.successful)
      }

      (maybeMetadataGitFile, maybeMetadataGitSshKey) match {
        case (Some(metadataGitFile), Some(metadataGitSshKey)) => cached(metadataGitFile, metadataGitSshKey)
        case _ => Future.failed(new Exception("metadata-git-file and metadata-git-ssh-key config must be set"))
      }

    }
  }

  def fetchProgram(programKey: String): Future[Program] = {
    fetchMetadata.flatMap { metadata =>
      metadata.programs.get(programKey).fold(Future.failed[Program](new Exception(s"Program '$programKey' not found")))(Future.successful)
    }
  }

}
