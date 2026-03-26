package demo.service

import demo.Codecs
import demo.domain.*
import zio.*
import zio.nats.*
import zio.nats.kv.KeyValue

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Business logic for the user service.
 *
 * Abstracts over the persistence layer so the handlers in [[demo.Main]] stay
 * thin. The live implementation stores users in a NATS KV bucket keyed by user
 * ID.
 */
trait UserService {
  def createUser(req: CreateUserRequest): IO[ValidationError, User]
  def getUser(id: String): IO[UserNotFound, User]
  def updateUser(req: UpdateUserRequest): IO[UserNotFound | ValidationError, User]
  def deleteUser(id: String): IO[UserNotFound, DeleteUserResponse]
  def listUsers(req: ListUsersRequest): UIO[ListUsersResponse]
}

object UserService {

  // Accessor methods so call sites use ZIO.serviceWithZIO implicitly.

  def createUser(req: CreateUserRequest): ZIO[UserService, ValidationError, User] =
    ZIO.serviceWithZIO[UserService](_.createUser(req))

  def getUser(id: String): ZIO[UserService, UserNotFound, User] =
    ZIO.serviceWithZIO[UserService](_.getUser(id))

  def updateUser(req: UpdateUserRequest): ZIO[UserService, UserNotFound | ValidationError, User] =
    ZIO.serviceWithZIO[UserService](_.updateUser(req))

  def deleteUser(id: String): ZIO[UserService, UserNotFound, DeleteUserResponse] =
    ZIO.serviceWithZIO[UserService](_.deleteUser(id))

  def listUsers(req: ListUsersRequest): ZIO[UserService, Nothing, ListUsersResponse] =
    ZIO.serviceWithZIO[UserService](_.listUsers(req))

  /** Build the live service from a [[KeyValue]] bucket. */
  val live: ZLayer[KeyValue, Nothing, UserService] =
    ZLayer.fromFunction(UserServiceLive.apply _)

}

private final class UserServiceLive(kv: KeyValue) extends UserService {

  import Codecs.json.derived

  def createUser(req: CreateUserRequest): IO[ValidationError, User] =
    for {
      _   <- ZIO.fail(ValidationError("name", "must not be blank")).when(req.name.isBlank)
      _   <- ZIO.fail(ValidationError("email", "must not be blank")).when(req.email.isBlank)
      id  <- ZIO.succeed(UUID.randomUUID().toString)
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      user = User(id = id, name = req.name, email = req.email, createdAt = now)
      _   <- kv.put(id, user).orDie
    } yield user

  def getUser(id: String): IO[UserNotFound, User] =
    kv.get[User](id).orDie.flatMap {
      case Some(env) => ZIO.succeed(env.value)
      case None      => ZIO.fail(UserNotFound(id))
    }

  def updateUser(req: UpdateUserRequest): IO[UserNotFound | ValidationError, User] =
    for {
      _ <- ZIO
             .fail(ValidationError("name", "must not be blank"))
             .when(req.name.exists(_.isBlank))
      _ <- ZIO
             .fail(ValidationError("email", "must not be blank"))
             .when(req.email.exists(_.isBlank))
      existing <- getUser(req.id)
      updated   = existing.copy(
                  name = req.name.getOrElse(existing.name),
                  email = req.email.getOrElse(existing.email)
                )
      _ <- kv.put(req.id, updated).orDie
    } yield updated

  def deleteUser(id: String): IO[UserNotFound, DeleteUserResponse] =
    getUser(id) *> kv.delete(id).orDie.as(DeleteUserResponse(id))

  def listUsers(req: ListUsersRequest): UIO[ListUsersResponse] =
    (for {
      keys  <- kv.keys()
      users <- ZIO.foreachPar(keys)(id => kv.get[User](id).map(_.map(_.value))).withParallelism(4)
    } yield ListUsersResponse(users.flatten)).orDie
}
