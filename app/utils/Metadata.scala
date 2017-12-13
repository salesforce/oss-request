/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import java.io.FileInputStream
import javax.inject.Inject

import models.Task
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment, Mode}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class Metadata(groups: Map[String, Set[String]], tasks: Map[String, Task.Prototype])

object Metadata {
  implicit val jsonReads = Json.reads[Metadata]
}

class MetadataService @Inject() (configuration: Configuration, environment: Environment, wsClient: WSClient) (implicit ec: ExecutionContext) {

  val defaultMetadataFile = "examples/metadata.json"

  val maybeMetadataUrl = configuration.getOptional[String]("metadata-url").orElse {
    if (environment.mode == Mode.Prod)
      throw new Exception("metadata-url must be set in prod mode")
    else
      None
  }
  val maybeMetadataToken = configuration.getOptional[String]("metadata-token")

  def fetchMetadata: Future[Metadata] = {
    maybeMetadataUrl.fold {
      environment.getExistingFile(defaultMetadataFile).fold(Future.failed[Metadata](new Exception(s"Could not open $defaultMetadataFile"))) { metadataFile =>
        val metadataTry = Try {
          val fileInputStream = new FileInputStream(metadataFile)
          val json = Json.parse(fileInputStream)
          fileInputStream.close()
          json.as[Metadata]
        }
        Future.fromTry(metadataTry)
      }
    } { metadataUrl =>
      val wsRequest = wsClient.url(metadataUrl.toString)
      val requestWithMaybeAuth = maybeMetadataToken.fold(wsRequest) { token =>
        wsRequest.withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token")
      }

      requestWithMaybeAuth.get().flatMap { response =>
        response.status match {
          case Status.OK => Future.fromTry(Try(response.json.as[Metadata]))
          case _ => Future.failed(new Exception(response.body))
        }
      }
    }
  }

}
