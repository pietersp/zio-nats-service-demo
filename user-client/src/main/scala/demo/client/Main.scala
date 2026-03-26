package demo.client

import zio.*

/**
 * Entry point for the load-simulation client.
 *
 * Runs [[LoadSimulator]] indefinitely against a live user service. Start the
 * service first (`sbt "userService/run"`), then run this client in a separate
 * terminal.
 *
 * Prerequisites:
 *   - A JetStream-enabled NATS server: `docker run -p 4222:4222 nats -js`
 *   - `user-service` running in another process
 *   - Optionally set `NATS_URL` to override the default `nats://localhost:4222`
 *
 * Run with: sbt "userClient/run"
 */
object Main extends ZIOAppDefault {

  val run: ZIO[Any, Throwable, Unit] =
    LoadSimulator.run.provide(ClientLayers.full)
}
