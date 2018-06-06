/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package controllers

import javax.inject.Inject
import models.Task.CompletableByType
import models.{State, Task}
import modules.{Auth, DB, NotifyProvider}
import org.webjars.WebJarAssetLocator
import org.webjars.play.WebJarsUtil
import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import play.api.{Configuration, Environment}
import play.twirl.api.Html
import utils.{DataFacade, MetadataService, UserAction, UserInfo, UserRequest}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Comment, Node}


class Application @Inject()
  (env: Environment, dataFacade: DataFacade, userAction: UserAction, auth: Auth, metadataService: MetadataService, configuration: Configuration, webJarsUtil: WebJarsUtil, notifyProvider: NotifyProvider)
  (requestsView: views.html.Requests, newRequestView: views.html.NewRequest, newRequestWithNameView: views.html.NewRequestWithName, requestView: views.html.Request, commentsView: views.html.partials.Comments, formTestView: views.html.FormTest, notifyTestView: views.html.NotifyTest, loginView: views.html.Login, pickEmailView: views.html.PickEmail, errorView: views.html.Error, openUserTasksView: views.html.OpenUserTasks)
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

  private def withUserInfo[A](f: UserInfo => Future[Result])(implicit userRequest: UserRequest[A]): Future[Result] = {
    userRequest.maybeUserInfo.fold(auth.authUrl.map(Redirect(_)))(f)
  }

  def requests = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      val requestsFuture = if (userInfo.isAdmin) {
        dataFacade.allRequests()
      }
      else {
        dataFacade.requestsForUser(userInfo.email)
      }

      requestsFuture.map { requests =>
        Ok(requestsView(requests, userInfo))
      }
    }
  }

  def newRequest(maybeName: Option[String]) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
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
    withUserInfo { userInfo =>
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
    withUserInfo { userInfo =>
      dataFacade.request(userInfo.email, requestSlug).flatMap { case (request, isAdmin, canCancelRequest) =>
        dataFacade.requestTasks(userInfo.email, request.slug).flatMap { tasks =>
          metadataService.fetchMetadata.map { metadata =>
            Ok(requestView(metadata, request, tasks, userInfo, isAdmin, canCancelRequest))
          }
        }
      } recover {
        case rnf: DB.RequestNotFound => NotFound(errorView(rnf.getMessage, userInfo))
      }
    }
  }

  def updateRequest(requestSlug: String, state: State.State) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.updateRequest(userInfo.email, requestSlug, state).map { request =>
        Redirect(routes.Application.request(request.slug))
      }
    }
  }

  def addTask(requestSlug: String) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    withUserInfo { userInfo =>
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
    withUserInfo { userInfo =>
      dataFacade.updateTask(userInfo.email, taskId, state, Some(userInfo.email), userRequest.body).map { task =>
        render {
          case Accepts.Html() => Redirect(routes.Application.request(requestSlug))
          case Accepts.Json() => Ok(Json.toJson(task))
        }
      }
    }
  }

  def commentOnTask(requestSlug: String, taskId: Int) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    withUserInfo { userInfo =>
      val maybeContents = userRequest.body.get("contents").flatMap(_.headOption).filterNot(_.isEmpty)

      maybeContents.fold(Future.successful(BadRequest("The contents were empty"))) { contents =>
        dataFacade.commentOnTask(requestSlug, taskId, userInfo.email, contents).map { comment =>
          Redirect(routes.Application.request(requestSlug))
        }
      }
    }
  }

  def commentsOnTask(requestSlug: String, taskId: Int) = userAction.async(maybeJsObject) { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.commentsOnTask(taskId).map { comments =>
        Ok(commentsView(comments))
      }
    }
  }

  def openUserTasks = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      for {
        metadata <- metadataService.fetchMetadata
        userGroups = metadata.groups.collect {
          case (group, emails) if emails.contains(userInfo.email) => group
        }
        tasksForUser <- dataFacade.tasksForUser(userInfo.email, State.InProgress)
        tasksForGroups <- dataFacade.tasksForGroups(userGroups.toSet, State.InProgress)
      } yield {
        val tasks = (tasksForUser ++ tasksForGroups).distinct
        Ok(openUserTasksView(tasks, metadata.groups, userInfo))
      }
    }
  }

  def formTest = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      Future.successful(Ok(formTestView(userInfo)))
    }
  }

  def notifyTest = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      Future.successful(Ok(notifyTestView(userInfo)))
    }
  }

  def notifyTestSend = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      val maybeInfo = for {
        form <- userRequest.body.asFormUrlEncoded

        recipients <- form.get("recipient")
        recipient <- recipients.headOption
        if !recipient.isEmpty

        messages <- form.get("message")
        message <- messages.headOption
        if !message.isEmpty
      } yield recipient -> message

      maybeInfo.fold {
        Future.successful(BadRequest(notifyTestView(userInfo, Some(Failure(new Exception("Missing form value"))))))
      } { case (recipient, message) =>
        notifyProvider.sendMessage(Set(recipient), "Notify Test", message).map { result =>
          val message = result match {
            case s: String => s
            case _ => "Test Successful"
          }
          Ok(notifyTestView(userInfo, Some(Success(message))))
        } recover {
          case t: Throwable => Ok(notifyTestView(userInfo, Some(Failure(t))))
        }
      }
    }
  }

  private def login(state: Option[String])(emails: Set[String])(implicit request: RequestHeader): Future[Result] = {
    if (emails.size > 1) {
      metadataService.fetchMetadata.map { metadata =>
        val emailsWithIsAdmin = emails.map { email =>
          email -> metadata.admins.contains(email)
        }.toMap

        Ok(pickEmailView(emailsWithIsAdmin)).withSession("emails" -> emails.mkString(","))
      }

    }
    else if (emails.size == 1) {
      val email = emails.head

      metadataService.fetchMetadata.map { metadata =>
        val isAdmin = metadata.groups("admin").contains(email)
        val url = state.getOrElse(controllers.routes.Application.openUserTasks().url)

        // todo: putting this info in the session means we can't easily invalidate it later
        Redirect(url).withSession("email" -> email, "isAdmin" -> isAdmin.toString)
      }
    }
    else {
      Future.successful(BadRequest("Could not determine user email"))
    }
  }

  def callback(code: Option[String], state: Option[String]) = Action.async { implicit request =>
    auth.emails(code).flatMap(login(state)).recover {
      case e: Exception => Unauthorized(e.getMessage)
    }
  }

  def acs() = Action.async(parse.formUrlEncoded) { implicit request =>
    auth.emails(request.body.get("SAMLResponse").flatMap(_.headOption)).flatMap(login(None)).recover {
      case e: Exception => Unauthorized(e.getMessage)
    }
  }

  def selectEmail(email: String) = Action.async { request =>
    val maybeValidEmail = request.session.get("emails").map(_.split(",")).getOrElse(Array.empty[String]).find(_ == email)
    maybeValidEmail.fold(Future.successful(Unauthorized("Email invalid"))) { validEmail =>
      metadataService.fetchMetadata.map { metadata =>
        val isAdmin = metadata.groups("admin").contains(validEmail)
        val url = controllers.routes.Application.openUserTasks().url

        // todo: putting this info in the session means we can't easily invalidate it later
        Redirect(url).withSession("email" -> validEmail, "isAdmin" -> isAdmin.toString)
      }
    }
  }

  def logout() = Action.async { implicit request =>
    auth.authUrl.map { authUrl =>
      Ok(loginView(authUrl)).withNewSession
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
