package demo.client

import zio.*

/**
 * Configuration for the load simulator.
 *
 * Loaded from `application.conf` / `application.json` on the classpath via
 * HOCON, falling back to environment variables, then the defaults below.
 *
 * HOCON example (application.conf):
 * {{{
 * load-simulator {
 *   pool-target = 500
 *   pool-min = 50
 *   call-timeout = 3s
 *   report-every = 10s
 *   reader-delay = 50ms
 *   updater-delay = 100ms
 *   deleter-delay = 250ms
 *   lister-delay = 2s
 *   creator-throttle-delay = 1s
 *   creator-workers-per-10 = 2
 *   reader-workers-per-10 = 3
 *   updater-workers-per-10 = 2
 * }
 * }}}
 *
 * Or via env vars (uppercase, hyphenated → underscore):
 * LOAD_SIMULATOR_POOL_TARGET, LOAD_SIMULATOR_POOL_MIN, etc.
 */
case class LoadSimulatorConfig(
  poolTarget: Int,
  poolMin: Int,
  callTimeout: Duration,
  reportEvery: Duration,
  readerDelay: Duration,
  updaterDelay: Duration,
  deleterDelay: Duration,
  listerDelay: Duration,
  creatorThrottleDelay: Duration,
  creatorWorkersPer10: Int,
  readerWorkersPer10: Int,
  updaterWorkersPer10: Int
)

object LoadSimulatorConfig {

  private val ns = "load-simulator"

  given Config[LoadSimulatorConfig] =
    (
      Config.int("pool-target").nested(ns).withDefault(1000) ++
        Config.int("pool-min").nested(ns).withDefault(80) ++
        Config.duration("call-timeout").nested(ns).withDefault(5.seconds) ++
        Config.duration("report-every").nested(ns).withDefault(5.seconds) ++
        Config.duration("reader-delay").nested(ns).withDefault(30.millis) ++
        Config.duration("updater-delay").nested(ns).withDefault(80.millis) ++
        Config.duration("deleter-delay").nested(ns).withDefault(200.millis) ++
        Config.duration("lister-delay").nested(ns).withDefault(1.second) ++
        Config.duration("creator-throttle-delay").nested(ns).withDefault(500.millis) ++
        Config.int("creator-workers-per-10").nested(ns).withDefault(1) ++
        Config.int("reader-workers-per-10").nested(ns).withDefault(2) ++
        Config.int("updater-workers-per-10").nested(ns).withDefault(1)
    ).map {
      case (
            poolTarget,
            poolMin,
            callTimeout,
            reportEvery,
            readerDelay,
            updaterDelay,
            deleterDelay,
            listerDelay,
            creatorThrottleDelay,
            creatorWorkersPer10,
            readerWorkersPer10,
            updaterWorkersPer10
          ) =>
        LoadSimulatorConfig(
          poolTarget,
          poolMin,
          callTimeout,
          reportEvery,
          readerDelay,
          updaterDelay,
          deleterDelay,
          listerDelay,
          creatorThrottleDelay,
          creatorWorkersPer10,
          readerWorkersPer10,
          updaterWorkersPer10
        )
    }
}
