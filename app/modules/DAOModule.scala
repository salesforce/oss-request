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
import models.{Comment, ProjectRequest, State, Task}
import org.slf4j.LoggerFactory
import play.api.inject.{ApplicationLifecycle, Binding, Module}
import play.api.libs.json.{JsObject, Json}
import play.api.{Configuration, Environment}

import scala.concurrent.{ExecutionContext, Future}

class DAOModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[DAO].to[DAOImpl]
    )
  }
}

trait DAO {
  def createRequest(name: String, creatorEmail: String): Future[ProjectRequest]
  def allRequests(): Future[Seq[ProjectRequest]]
  def requestsForUser(email: String): Future[Seq[ProjectRequest]]
  def createTask(projectRequestId: Int, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeData: Option[JsObject] = None, state: State = State.InProgress): Future[Task]
  def updateTaskState(taskId: Int, state: State): Future[Long]
  def requestTasks(projectRequestId: Int, maybeState: Option[State] = None): Future[Seq[Task]]
  def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment]
}

class DAOImpl @Inject()(database: DatabaseWithCtx)(implicit ec: ExecutionContext) extends DAO {
  import database.ctx._

  implicit val jsObjectDecoder = MappedEncoding[String, JsObject](Json.parse(_).as[JsObject])
  implicit val jsObjectEncoder = MappedEncoding[JsObject, String](_.toString())

  override def createRequest(name: String, creatorEmail: String): Future[ProjectRequest] = {
    // todo: slug choice in transaction with create

    // figure out slug
    val defaultSlug = DAO.slug(name)
    val takenSlugsFuture = run {
      quote {
        query[ProjectRequest].map(_.slug).filter(_.startsWith(lift(defaultSlug)))
      }
    }

    takenSlugsFuture.map(DAO.nextSlug(defaultSlug)).flatMap { slug =>
      val state = State.InProgress
      val createDate = ZonedDateTime.now()

      run {
        quote {
          query[ProjectRequest].insert(
            _.name -> lift(name),
            _.slug -> lift(slug),
            _.state -> lift(state),
            _.creatorEmail -> lift(creatorEmail),
            _.createDate -> lift(createDate)
          ).returning(_.id)
        }
      } map { id =>
        ProjectRequest(id, name, slug, createDate, creatorEmail, state)
      }
    }
  }

  override def allRequests(): Future[Seq[ProjectRequest]] = {
    run {
      quote {
        query[ProjectRequest]
      }
    }
  }

  override def requestsForUser(email: String): Future[Seq[ProjectRequest]] = {
    run {
      quote {
        query[ProjectRequest].filter(_.creatorEmail == lift(email))
      }
    }
  }

  override def createTask(projectRequestId: Int, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeData: Option[JsObject] = None, state: State = State.InProgress): Future[Task] = {
    run {
      quote {
        query[Task].insert(
          _.completableByType -> lift(completableByType),
          _.completableByValue -> lift(completableByValue),
          _.projectRequestId -> lift(projectRequestId),
          _.state -> lift(state),
          _.prototype -> lift(prototype),
          _.data -> lift(maybeData)
        ).returning(_.id)
      }
    } map { id =>
      Task(id, completableByType, completableByValue, state, prototype, maybeData, projectRequestId)
    }
  }

  override def updateTaskState(taskId: Int, state: State): Future[Long] = {
    run {
      quote {
        query[Task].filter(_.id == lift(taskId)).update(_.state -> lift(state))
      }
    }
  }

  override def requestTasks(projectRequestId: Int, maybeState: Option[State] = None): Future[Seq[Task]] = {
    maybeState.fold {
      run {
        quote {
          query[Task].filter(_.projectRequestId == lift(projectRequestId))
        }
      }
    } { state =>
      run {
        quote {
          query[Task].filter { task =>
            task.projectRequestId == lift(projectRequestId) && task.state == lift(state)
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

object DAO {
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
