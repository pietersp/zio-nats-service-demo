package demo

import demo.endpoints.UserEndpoints
import demo.service.UserService
import zio.*
import zio.nats.*

/**
 * Entry point for the user microservice.
 *
 * Starts a NATS microservice named `"user-service"` with five endpoints grouped
 * under the `"users"` subject prefix. The service runs until the process is
 * interrupted.
 *
 * Prerequisites:
 *   - A JetStream-enabled NATS server: `docker run -p 4222:4222 nats -js`
 *   - Optionally set `NATS_URL` to override the default `nats://localhost:4222`
 *
 * Run with: sbt "userService/run"
 */
object Main extends ZIOAppDefault {

  val run: ZIO[Any, Throwable, Unit] =
    ZIO
      .serviceWithZIO[Nats] { nats =>
        ZIO.serviceWithZIO[UserService] { users =>
          ZIO.scoped {
            for {
              svc <- nats.service(
                       ServiceConfig(
                         name = "user-service",
                         version = "1.0.0",
                         description = Some("CRUD user management backed by NATS KV")
                       ),
                       UserEndpoints.createUser.handle(users.createUser),
                       UserEndpoints.getUser.handle(req => users.getUser(req.id)),
                       UserEndpoints.updateUser.handle(users.updateUser),
                       UserEndpoints.deleteUser.handle(req => users.deleteUser(req.id)),
                       UserEndpoints.listUsers.handle(users.listUsers)
                     )

              _ <- Console.printLine(s"user-service started [id=${svc.id}]")
              _ <- Console.printLine("Listening on: users.{create,get,update,delete,list}")
              _ <- Console.printLine("Press Ctrl-C to stop.")
              _ <- ZIO.never
            } yield ()
          }
        }
      }
      .provide(AppLayers.full)
}
