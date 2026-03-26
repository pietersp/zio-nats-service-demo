package demo.client

import zio.*
import zio.nats.*

object ClientLayers {

  /**
   * Minimal layer stack for the load client.
   *
   * Only a Core NATS connection is needed — no JetStream, no KV. The client
   * communicates with the user service via `requestService`, which uses plain
   * NATS request-reply.
   *
   * Reads `NATS_URL` from the environment (defaults to `nats://localhost:4222`).
   */
  val full: ZLayer[Any, Throwable, Nats] =
    ZLayer.make[Nats](
      NatsConfig.live,
      Nats.live
    )
}
