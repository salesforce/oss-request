/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import akka.stream.Materializer
import javax.inject.Inject
import play.api.http.HeaderNames
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class OnlyHttpsFilter @Inject() (implicit val mat: Materializer, ec: ExecutionContext) extends Filter {
  def apply(nextFilter: (RequestHeader) => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    nextFilter(requestHeader).map { result =>
      val isWellKnown = requestHeader.path.startsWith(controllers.routes.Application.wellKnown("").url)
      val isForwardedAndInsecure = requestHeader.headers.get(HeaderNames.X_FORWARDED_PROTO).exists(_ != "https")

      if (isWellKnown || !isForwardedAndInsecure) {
        result
      }
      else {
        Results.MovedPermanently("https://" + requestHeader.host + requestHeader.uri)
      }
    }
  }
}
