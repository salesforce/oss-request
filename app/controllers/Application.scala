/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package controllers

import javax.inject.Inject

import models.{State, Task}
import models.Task.{CompletableByType, TaskType}
import modules.DAO
import org.webjars.WebJarAssetLocator
import org.webjars.play.WebJarsUtil
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Results.EmptyContent
import play.api.mvc._
import play.api.{Configuration, Environment, Mode}
import play.twirl.api.Html
import utils.dev.DevUsers
import utils.{MetadataService, Oauth, UserAction, UserInfo}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Comment, Node}


class Application @Inject()
  (env: Environment, dao: DAO, userAction: UserAction, oauth: Oauth, metadataService: MetadataService, configuration: Configuration, webJarsUtil: WebJarsUtil, devUsers: DevUsers)
  (indexView: views.html.Index, devSelectUserView: views.html.dev.SelectUser, newRequestView: views.html.NewRequest, newRequestWithNameView: views.html.NewRequestWithName, requestView: views.html.Request)
  (implicit ec: ExecutionContext)
  extends InjectedController {

  // todo: test cause it doesn't seem to be working correctly
  private[controllers] def completableByWithDefaults(maybeCompletableBy: Option[Task.CompletableBy], defaultWhenNoCompletableBy: Option[String], defaultWhenNoCompletableByValue: Option[String]): Option[(CompletableByType.CompletableByType, String)] = {
    maybeCompletableBy.flatMap { completableBy =>
      completableBy.value.orElse(defaultWhenNoCompletableByValue).map { value =>
        completableBy.`type` -> value
      }
    } orElse defaultWhenNoCompletableBy.map(CompletableByType.Email -> _)
  }

  def index = userAction.async { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      val requestsFuture = if (userInfo.isAdmin) {
        dao.allRequests()
      }
      else {
        dao.requestsForUser(userInfo.email)
      }

      requestsFuture.map { requests =>
        Ok(indexView(requests, userInfo))
      }
    }
  }

  def newRequest(maybeName: Option[String]) = userAction.async { implicit userRequest =>
    maybeName.fold {
      Future.successful(Ok(newRequestView()))
    } { name =>
      metadataService.fetchMetadata.map { metadata =>
        metadata.tasks.get("start").fold(InternalServerError("Could not find task named 'start'")) { metaTask =>
          Ok(newRequestWithNameView(name, metaTask, metadata.groups))
        }
      }
    }
  }

  def createRequest(name: String) = userAction.async(parse.json) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      metadataService.fetchMetadata.flatMap { metadata =>
        metadata.tasks.get("start").fold(Future.successful(InternalServerError("Could not find task named 'start'"))) { metaTask =>
          dao.createRequest(name, userInfo.email).flatMap { request =>
            completableByWithDefaults(metaTask.completableBy, Some(userInfo.email), None).fold {
              Future.successful(BadRequest("Could not determine who can complete the task"))
            } { case (completableByType, completableByValue) =>
              dao.createTask(request.id, metaTask, completableByType, completableByValue, Some(userInfo.email), Json.toJson(userRequest.body).asOpt[JsObject], State.Completed).map { task =>
                Ok(Json.toJson(request))
              }
            }
          }
        }
      }
    }
  }

  def requestById(id: Int) = Action.async { implicit request =>
    dao.requestById(id).map { request =>
      Redirect(routes.Application.requestBySlug(request.slug))
    }
  }

  def requestBySlug(slug: String) = Action.async { implicit request =>
    dao.requestBySlug(slug).flatMap { request =>
      dao.requestTasks(request.id).flatMap { tasks =>
        metadataService.fetchMetadata.map { metadata =>
          Ok(requestView(metadata, request, tasks))
        }
      }
    }
  }

  def updateRequest(id: Int, state: State.State) = userAction.async { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      dao.updateRequest(id, state).map { request =>
        Redirect(routes.Application.requestBySlug(request.slug))
      }
    }
  }

  def addTask(requestId: Int) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      val maybeTaskPrototypeKey = userRequest.body.get("taskPrototypeKey").flatMap(_.headOption)
      val maybeCompletableBy = userRequest.body.get("completableBy").flatMap(_.headOption).filterNot(_.isEmpty)

      dao.requestById(requestId).flatMap { request =>
        maybeTaskPrototypeKey.fold(Future.successful(BadRequest("No taskPrototypeKey specified"))) { taskPrototypeKey =>
          metadataService.fetchMetadata.flatMap { metadata =>
            metadata.tasks.get(taskPrototypeKey).fold(Future.successful(InternalServerError(s"Could not find task prototype $taskPrototypeKey"))) { taskPrototype =>
              completableByWithDefaults(taskPrototype.completableBy, Some(request.creatorEmail), maybeCompletableBy).fold {
                Future.successful(BadRequest("Could not determine who can complete the task"))
              } { case (completableByType, completableByValue) =>
                dao.createTask(requestId, taskPrototype, completableByType, completableByValue).map { _ =>
                  Redirect(routes.Application.requestBySlug(request.slug))
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

  def updateTask(taskId: Int, state: State.State) = userAction.async(maybeJsObject) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      dao.updateTask(taskId, state, Some(userInfo.email), userRequest.body).flatMap { task =>
        dao.requestById(task.requestId).map { request =>
          render {
            case Accepts.Html() => Redirect(routes.Application.requestBySlug(request.slug))
            case Accepts.Json() => Ok(Json.toJson(task))
          }
        }
      }
    }
  }

  def oauthCallback(code: String, state: Option[String]) = Action.async { implicit request =>
    oauth.accessToken(oauth.tokenUrl(code)).flatMap { accessToken =>
      oauth.email(oauth.userinfoUrl(), accessToken).flatMap { email =>
        metadataService.fetchMetadata.map { metadata =>
          val isAdmin = metadata.groups("admin").contains(email)
          val url = state.getOrElse(controllers.routes.Application.index().url)

          // todo: putting this info in the session means we can't easily invalidate it later
          Redirect(url).withSession("email" -> email, "isAdmin" -> isAdmin.toString)
        }
      }
    }
  }


  def devOauthAuthorize(response_type: String, client_id: String, redirect_uri: String) = Action { implicit request =>
    env.mode match {
      case Mode.Prod => Unauthorized
      case _ => Ok(devSelectUserView(request))
    }
  }

  def devOauthUserinfo = Action { implicit request =>
    env.mode match {
      case Mode.Prod =>
        Unauthorized
      case _ =>
        val maybeToken = request.headers.get(AUTHORIZATION).map(_.stripPrefix("Bearer "))

        val maybeUser = maybeToken.flatMap { token =>
          devUsers.users.find(_.token == token)
        }

        maybeUser.fold(Unauthorized("User not found")) { user =>
          val json = Json.obj(
            "email" -> user.email
          )
          Ok(json)
        }
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
