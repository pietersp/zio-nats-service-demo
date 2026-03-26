package demo

import zio.*
import zio.nats.*
import zio.nats.kv.{KeyValue, KeyValueManagement}
import demo.service.UserService

object AppLayers {

  private val bucketName = "users"

  /**
   * Scoped layer that ensures the `"users"` KV bucket exists and returns a
   * [[KeyValue]] handle to it.
   *
   * `JetStreamApiError` from `create` is suppressed: it is the expected
   * response when the bucket was already created by a previous run.
   */
  private val keyValueLayer: ZLayer[Nats & KeyValueManagement, NatsError, KeyValue] =
    ZLayer.scoped {
      for {
        kvm <- ZIO.service[KeyValueManagement]
        _   <- kvm
               .create(KeyValueConfig(name = bucketName))
               .catchSome { case _: NatsError.JetStreamApiError => ZIO.unit }
        kv <- KeyValue.bucket(bucketName)
      } yield kv
    }

  /**
   * Full application layer stack, wired automatically by `ZLayer.make`.
   *
   * Reads NATS connection settings from the environment via [[NatsConfig.live]]
   * (defaults to `nats://localhost:4222`). Both `Nats` and `UserService` are
   * kept in the output so [[Main]] can access both.
   */
  val full: ZLayer[Any, Throwable, Nats & UserService] =
    ZLayer.make[Nats & UserService](
      NatsConfig.live,
      Nats.live,
      KeyValueManagement.live,
      keyValueLayer,
      UserService.live
    )
}
