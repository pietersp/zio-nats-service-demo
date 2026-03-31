package demo.client

import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.nats.*

object ClientLayers {

  /**
   * HOCON-first ConfigProvider: reads from `application.conf` on the classpath,
   * falling back to env vars / system properties.
   */
  private val configProvider: ConfigProvider =
    TypesafeConfigProvider.fromResourcePath().orElse(ConfigProvider.defaultProvider)

  /**
   * Bootstrap layer that installs the HOCON ConfigProvider at the runtime level
   * so `ZIO.config[LoadSimulatorConfig]` picks it up.
   */
  private val hoconBootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.setConfigProvider(configProvider)

  /**
   * Full layer stack for the load client.
   *
   * Requires:
   *   - Core NATS connection (no JetStream/KV)
   *   - [[LoadSimulatorConfig]] read from HOCON (application.conf), falling
   *     back to environment variables
   *
   * Reads `NATS_URL` from the environment (defaults to
   * `nats://localhost:4222`).
   */
  val full: ZLayer[Any, Throwable, Nats & LoadSimulatorConfig] =
    ZLayer.make[Nats & LoadSimulatorConfig](
      hoconBootstrap,
      NatsConfig.live,
      Nats.live,
      ZLayer(ZIO.config[LoadSimulatorConfig])
    )
}
