/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import java.time.ZonedDateTime
import javax.inject.{Inject, Singleton}

import com.github.mauricio.async.db.SSLConfiguration
import com.github.mauricio.async.db.pool.{PartitionedConnectionPool, PoolConfiguration}
import com.github.mauricio.async.db.postgresql.pool.PostgreSQLConnectionFactory
import com.github.mauricio.async.db.postgresql.util.URLParser
import io.getquill.{PostgresAsyncContext, SnakeCase}
import models.State.State
import models.Task.CompletableByType.CompletableByType
import models.{Comment, Request, State, Task}
import org.slf4j.LoggerFactory
import play.api.inject.{ApplicationLifecycle, Binding, Module}
import play.api.libs.json.{JsObject, Json}
import play.api.{Configuration, Environment}

import scala.concurrent.{ExecutionContext, Future}

class DBModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[DB].to[DBImpl]
    )
  }
}

trait DB {
  def createRequest(name: String, creatorEmail: String): Future[Request]
  def allRequests(): Future[Seq[Request]]
  def requestsForUser(email: String): Future[Seq[Request]]
  def requestById(id: Int): Future[Request]
  def updateRequest(id: Int, state: State.State): Future[Request]
  def createTask(requestId: Int, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeCompletedBy: Option[String] = None, maybeData: Option[JsObject] = None, state: State = State.InProgress): Future[Task]
  def updateTask(taskId: Int, state: State, maybeCompletedBy: Option[String], maybeData: Option[JsObject]): Future[Task]
  def requestTasks(requestId: Int, maybeState: Option[State] = None): Future[Seq[Task]]
  def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment]
}

class DBImpl @Inject()(database: DatabaseWithCtx)(implicit ec: ExecutionContext) extends DB {
  import database.ctx._

  implicit val jsObjectDecoder = MappedEncoding[String, JsObject](Json.parse(_).as[JsObject])
  implicit val jsObjectEncoder = MappedEncoding[JsObject, String](_.toString())

  override def createRequest(name: String, creatorEmail: String): Future[Request] = {
    // todo: slug choice in transaction with create

    // figure out slug
    val defaultSlug = DB.slug(name)
    val takenSlugsFuture = run {
      quote {
        query[Request].map(_.slug).filter(_.startsWith(lift(defaultSlug)))
      }
    }

    takenSlugsFuture.map(DB.nextSlug(defaultSlug)).flatMap { slug =>
      val state = State.InProgress
      val createDate = ZonedDateTime.now()

      run {
        quote {
          query[Request].insert(
            _.name -> lift(name),
            _.slug -> lift(slug),
            _.state -> lift(state),
            _.creatorEmail -> lift(creatorEmail),
            _.createDate -> lift(createDate)
          ).returning(_.id)
        }
      } map { id =>
        Request(id, name, slug, createDate, creatorEmail, state, None)
      }
    }
  }

  override def allRequests(): Future[Seq[Request]] = {
    run {
      quote {
        query[Request]
      }
    }
  }

  override def updateRequest(id: Index, state: State): Future[Request] = {
    val maybeCompletedDate = if (state == State.Completed) Some(ZonedDateTime.now()) else None

    val updateFuture = run {
      quote {
        query[Request].filter(_.id == lift(id)).update(
          _.state -> lift(state),
          _.completedDate -> lift(maybeCompletedDate)
        )
      }
    }
    updateFuture.flatMap(_ => requestById(id))
  }

  override def requestsForUser(email: String): Future[Seq[Request]] = {
    run {
      quote {
        query[Request].filter(_.creatorEmail == lift(email))
      }
    }
  }

  override def requestById(id: Int): Future[Request] = {
    run {
      quote {
        query[Request].filter(_.id == lift(id))
      }
    } flatMap { result =>
      result.headOption.fold(Future.failed[Request](new Exception("Request not found")))(Future.successful)
    }
  }

  override def createTask(requestId: Int, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeCompletedBy: Option[String], maybeData: Option[JsObject] = None, state: State = State.InProgress): Future[Task] = {
    if (state == State.Completed && maybeCompletedBy.isEmpty) {
      Future.failed(new Exception("maybeCompletedBy was not specified"))
    }
    else {
      val maybeCompletedDate = if (state == State.Completed) Some(ZonedDateTime.now()) else None

      run {
        quote {
          query[Task].insert(
            _.completableByType -> lift(completableByType),
            _.completableByValue -> lift(completableByValue),
            _.completedBy -> lift(maybeCompletedBy),
            _.requestId -> lift(requestId),
            _.state -> lift(state),
            _.prototype -> lift(prototype),
            _.data -> lift(maybeData),
            _.completedDate -> lift(maybeCompletedDate)
          ).returning(_.id)
        }
      } map { id =>
        Task(id, completableByType, completableByValue, maybeCompletedBy, maybeCompletedDate, state, prototype, maybeData, requestId)
      }
    }
  }

  def taskById(taskId: Int): Future[Task] = {
    run {
      quote {
        query[Task].filter(_.id == lift(taskId))
      }
    } flatMap { result =>
      result.headOption.fold(Future.failed[Task](new Exception("Task not found")))(Future.successful)
    }
  }

  override def updateTask(taskId: Int, state: State, maybeCompletedBy: Option[String], maybeData: Option[JsObject]): Future[Task] = {
    if (state == State.Completed && maybeCompletedBy.isEmpty) {
      Future.failed(new Exception("maybeCompletedBy was not specified"))
    }
    else {
      val maybeCompletedDate = if (state == State.Completed) Some(ZonedDateTime.now()) else None
      val updateFuture = run {
        quote {
          query[Task].filter(_.id == lift(taskId)).update(
            _.completedBy -> lift(maybeCompletedBy),
            _.state -> lift(state),
            _.completedDate -> lift(maybeCompletedDate),
            _.data -> lift(maybeData)
          )
        }
      }
      updateFuture.flatMap(_ => taskById(taskId))
    }
  }

  override def requestTasks(requestId: Int, maybeState: Option[State] = None): Future[Seq[Task]] = {
    maybeState.fold {
      run {
        quote {
          query[Task].filter(_.requestId == lift(requestId))
        }
      }
    } { state =>
      run {
        quote {
          query[Task].filter { task =>
            task.requestId == lift(requestId) && task.state == lift(state)
          }
        }
      }
    }
  }

  override def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment] = {
    val createDate = ZonedDateTime.now()
    run {
      quote {
        query[Comment].insert(
          _.taskId -> lift(taskId),
          _.creatorEmail -> lift(email),
          _.createDate -> lift(createDate),
          _.contents -> lift(contents)
        ).returning(_.id)
      }
    } map { id =>
      Comment(id, email, createDate, contents, taskId)
    }
  }
}

object DB {
  def slug(s: String): String = {
    s.toLowerCase.replaceAllLiterally("  ", " ").replaceAllLiterally(" ", "-").replaceAll("[^a-z0-9-]", "")
  }

  def nextSlug(default: String)(existingSlugs: Seq[String]): String = {
    if (existingSlugs.isEmpty) {
      default
    }
    else {
      Stream.from(1).map(default + "-" + _).dropWhile(existingSlugs.contains).head
    }
  }
}

@Singleton
class DatabaseWithCtx @Inject()(lifecycle: ApplicationLifecycle, playConfig: Configuration) (implicit ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(this.getClass)

  private val maybeDbUrl = playConfig.getOptional[String]("db.default.url")

  private val config = maybeDbUrl.map(URLParser.parse(_)).getOrElse(URLParser.DEFAULT)

  private val configWithMaybeSsl = playConfig.getOptional[String]("db.default.sslmode").fold(config) { sslmode =>
    val sslConfig = SSLConfiguration(Map("sslmode" -> sslmode))
    config.copy(ssl = sslConfig)
  }

  private val connectionFactory = new PostgreSQLConnectionFactory(configWithMaybeSsl)

  private val defaultPoolConfig = PoolConfiguration.Default

  private val maxObjects = playConfig.getOptional[Int]("db.default.max-objects").getOrElse(defaultPoolConfig.maxObjects)
  private val maxIdleMillis = playConfig.getOptional[Long]("db.default.max-idle-millis").getOrElse(defaultPoolConfig.maxIdle)
  private val maxQueueSize = playConfig.getOptional[Int]("db.default.max-queue-size").getOrElse(defaultPoolConfig.maxQueueSize)
  private val validationInterval = playConfig.getOptional[Long]("db.default.max-queue-size").getOrElse(defaultPoolConfig.validationInterval)

  private val poolConfig = new PoolConfiguration(maxObjects, maxIdleMillis, maxQueueSize, validationInterval)

  private val numberOfPartitions = playConfig.getOptional[Int]("db.default.number-of-partitions").getOrElse(4)

  private val pool = new PartitionedConnectionPool(
    connectionFactory,
    poolConfig,
    numberOfPartitions,
    ec
  )

  lifecycle.addStopHook { () =>
    pool.close
  }

  val ctx = new PostgresAsyncContext(SnakeCase, pool)

}
