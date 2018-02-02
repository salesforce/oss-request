/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package controllers

import javax.inject.Inject

import models.Task.CompletableByType
import models.{State, Task}
import modules.User
import org.webjars.WebJarAssetLocator
import org.webjars.play.WebJarsUtil
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Results.EmptyContent
import play.api.mvc._
import play.api.{Configuration, Environment, Mode}
import play.twirl.api.Html
import utils.{DataFacade, MetadataService, OAuth, UserAction}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Comment, Node}


class Application @Inject()
  (env: Environment, dataFacade: DataFacade, userAction: UserAction, oauth: OAuth, metadataService: MetadataService, configuration: Configuration, webJarsUtil: WebJarsUtil, user: User)
  (indexView: views.html.Index, devSelectUserView: views.html.dev.SelectUser, newRequestView: views.html.NewRequest, newRequestWithNameView: views.html.NewRequestWithName, requestView: views.html.Request, commentsView: views.html.partials.Comments, formTestView: views.html.FormTest, loginView: views.html.Login)
  (implicit ec: ExecutionContext)
  extends InjectedController {

  private[controllers] def completableByWithDefaults(maybeCompletableBy: Option[Task.CompletableBy], maybeRequestOwner: Option[String], maybeProvidedValue: Option[String]): Option[(CompletableByType.CompletableByType, String)] = {
    (maybeCompletableBy, maybeRequestOwner, maybeProvidedValue) match {
      case (Some(Task.CompletableBy(completableByType, Some(completableByValue))), _, _) => Some(completableByType -> completableByValue)
      case (Some(Task.CompletableBy(completableByType, None)), _, Some(providedValue)) => Some(completableByType -> providedValue)
      case (None, Some(requestOwner), _) => Some(CompletableByType.Email -> requestOwner)
      case _ => None
    }
  }

  def index = userAction.async { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      val requestsFuture = if (userInfo.isAdmin) {
        dataFacade.allRequests()
      }
      else {
        dataFacade.requestsForUser(userInfo.email)
      }

      requestsFuture.map { requests =>
        Ok(indexView(requests, userInfo))
      }
    }
  }

  def newRequest(maybeName: Option[String]) = userAction.async { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      maybeName.fold {
        Future.successful(Ok(newRequestView(userInfo)))
      } { name =>
        metadataService.fetchMetadata.map { metadata =>
          metadata.tasks.get("start").fold(InternalServerError("Could not find task named 'start'")) { metaTask =>
            Ok(newRequestWithNameView(name, metaTask, metadata.groups, userInfo))
          }
        }
      }
    }
  }

  def createRequest(name: String) = userAction.async(parse.json) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      metadataService.fetchMetadata.flatMap { metadata =>
        metadata.tasks.get("start").fold(Future.successful(InternalServerError("Could not find task named 'start'"))) { metaTask =>
          dataFacade.createRequest(name, userInfo.email).flatMap { request =>
            completableByWithDefaults(metaTask.completableBy, Some(userInfo.email), None).fold {
              Future.successful(BadRequest("Could not determine who can complete the task"))
            } { case (completableByType, completableByValue) =>
              dataFacade.createTask(request.slug, metaTask, completableByType, completableByValue, Some(userInfo.email), Json.toJson(userRequest.body).asOpt[JsObject], State.Completed).map { task =>
                Ok(Json.toJson(request))
              }
            }
          }
        }
      }
    }
  }

  def request(requestSlug: String) = userAction.async { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      dataFacade.request(userInfo.email, requestSlug).flatMap { case (request, isAdmin, canCancelRequest) =>
        dataFacade.requestTasks(userInfo.email, request.slug).flatMap { tasks =>
          metadataService.fetchMetadata.map { metadata =>
            Ok(requestView(metadata, request, tasks, userInfo, isAdmin, canCancelRequest))
          }
        }
      }
    }
  }

  def updateRequest(requestSlug: String, state: State.State) = userAction.async { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      dataFacade.updateRequest(userInfo.email, requestSlug, state).map { request =>
        Redirect(routes.Application.request(request.slug))
      }
    }
  }

  def addTask(requestSlug: String) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      val maybeTaskPrototypeKey = userRequest.body.get("taskPrototypeKey").flatMap(_.headOption)
      val maybeCompletableBy = userRequest.body.get("completableBy").flatMap(_.headOption).filterNot(_.isEmpty)

      dataFacade.request(userInfo.email, requestSlug).flatMap { case (request, _, _) =>
        maybeTaskPrototypeKey.fold(Future.successful(BadRequest("No taskPrototypeKey specified"))) { taskPrototypeKey =>
          metadataService.fetchMetadata.flatMap { metadata =>
            metadata.tasks.get(taskPrototypeKey).fold(Future.successful(InternalServerError(s"Could not find task prototype $taskPrototypeKey"))) { taskPrototype =>
              completableByWithDefaults(taskPrototype.completableBy, Some(request.creatorEmail), maybeCompletableBy).fold {
                Future.successful(BadRequest("Could not determine who can complete the task"))
              } { case (completableByType, completableByValue) =>
                dataFacade.createTask(requestSlug, taskPrototype, completableByType, completableByValue).map { _ =>
                  Redirect(routes.Application.request(request.slug))
                }
              }
            }
          }
        }
      }
    }
  }

  private lazy val maybeJsObject: BodyParser[Option[JsObject]] = {
    parse.tolerantText.map { s =>
      Try(Json.parse(s).as[JsObject]).toOption
    }
  }

  def updateTask(requestSlug: String, taskId: Int, state: State.State) = userAction.async(maybeJsObject) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      dataFacade.updateTask(userInfo.email, taskId, state, Some(userInfo.email), userRequest.body).map { task =>
        render {
          case Accepts.Html() => Redirect(routes.Application.request(requestSlug))
          case Accepts.Json() => Ok(Json.toJson(task))
        }
      }
    }
  }

  def commentOnTask(requestSlug: String, taskId: Int) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      val maybeContents = userRequest.body.get("contents").flatMap(_.headOption).filterNot(_.isEmpty)

      maybeContents.fold(Future.successful(BadRequest("The contents were empty"))) { contents =>
        dataFacade.commentOnTask(requestSlug, taskId, userInfo.email, contents).map { comment =>
          Redirect(routes.Application.request(requestSlug))
        }
      }
    }
  }

  def commentsOnTask(requestSlug: String, taskId: Int) = userAction.async(maybeJsObject) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      dataFacade.commentsOnTask(taskId).map { comments =>
        Ok(commentsView(comments))
      }
    }
  }

  def formTest = userAction { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Redirect(oauth.authUrl)) { userInfo =>
      Ok(formTestView(userInfo))
    }
  }

  def oauthCallback(code: String, state: Option[String]) = Action.async { implicit request =>
    oauth.accessToken(oauth.tokenUrl(code)).flatMap { accessToken =>
      user.email(accessToken).flatMap { email =>
        metadataService.fetchMetadata.map { metadata =>
          val isAdmin = metadata.groups("admin").contains(email)
          val url = state.getOrElse(controllers.routes.Application.index().url)

          // todo: putting this info in the session means we can't easily invalidate it later
          Redirect(url).withSession("email" -> email, "isAdmin" -> isAdmin.toString)
        }
      }
    }
  }

  def logout() = Action {
    Ok(loginView()).withNewSession
  }


  def devOauthAuthorize(response_type: String, client_id: String, redirect_uri: String) = Action { implicit request =>
    env.mode match {
      case Mode.Prod => Unauthorized
      case _ => Ok(devSelectUserView(request))
    }
  }

  def devOauthToken(grant_type: String, code: String, redirect_uri: String, client_id: String, client_secret: String) = Action {
    val json = Json.obj(
      "access_token" -> code
    )

    env.mode match {
      case Mode.Prod => Unauthorized
      case _ => Ok(json)
    }
  }

  def devMetadata = Action {
    env.mode match {
      case Mode.Prod =>
        Unauthorized
      case _ =>
        env.getExistingFile(metadataService.defaultMetadataFile).fold(InternalServerError(s"${metadataService.defaultMetadataFile} not found")) { metadataFile =>
          Ok.sendFile(metadataFile)
        }
    }
  }

  def wellKnown(key: String) = Action {
    configuration.getOptional[String]("wellknown").fold(NotFound(EmptyContent())) { wellKnownKeyValue =>
      if (wellKnownKeyValue.startsWith(key + "=")) {
        Ok(wellKnownKeyValue.stripPrefix(key + "="))
      }
      else {
        NotFound(EmptyContent())
      }
    }
  }

  private[controllers] def svgSymbol(path: String, symbol: String): Node = {
    webJarsUtil.locate(path).path.flatMap { filePath =>
      val maybeInputStream = env.resourceAsStream(WebJarAssetLocator.WEBJARS_PATH_PREFIX + "/" + filePath)
      maybeInputStream.fold[Try[Node]](Failure(new Exception("Could not read file"))) { inputStream =>
        val elem = scala.xml.XML.load(inputStream)
        inputStream.close()

        val maybeSymbol = elem.child.find { node =>
          node \@ "id" == symbol
        } flatMap (_.child.headOption)

        maybeSymbol.fold[Try[Node]](Failure(new Exception(s"Could not find symbol $symbol")))(Success(_))
      }
    } fold (
      { t => Comment(s"Error getting SVG: ${t.getMessage}") },
      { identity }
    )
  }

  private def svgInline(path: String, symbol: String): Html = {
    Html(svgSymbol(path, symbol).toString())
  }

}
