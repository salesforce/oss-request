/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import java.io.{File, FileInputStream}
import java.nio.file.Files

import com.jcraft.jsch.{JSch, Session}
import javax.inject.{Inject, Singleton}
import models.Task
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.{JschConfigSessionFactory, OpenSshConfig, SshTransport}
import org.eclipse.jgit.util.FS
import play.api.libs.json.Json
import play.api.{Configuration, Environment, Mode}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class Metadata(groups: Map[String, Set[String]], tasks: Map[String, Task.Prototype]) {
  val admins = groups.getOrElse("admin", Set.empty[String])
}

object Metadata {
  implicit val jsonReads = Json.reads[Metadata]
}

@Singleton
class MetadataService @Inject() (configuration: Configuration, environment: Environment) (implicit ec: ExecutionContext) {

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
        json.as[Metadata]
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

      (maybeMetadataGitFile, maybeMetadataGitSshKey) match {
        case (Some(metadataGitFile), Some(metadataGitSshKey)) => readMetadata(metadataGitFile, metadataGitSshKey)
        case _ => Future.failed(new Exception("metadata-git-file and metadata-git-ssh-key config must be set"))
      }

    }
  }

}
