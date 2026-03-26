package demo.client

import demo.Codecs
import demo.domain.*
import demo.endpoints.UserEndpoints
import zio.*
import zio.nats.*

import scala.util.Random

/**
 * Simulates concurrent production-like traffic against the user service.
 *
 * Spawns the following concurrent worker fibers:
 *   - 3 creator fibers  — create users; throttle when pool exceeds 200 users
 *   - 8 reader fibers   — GET a random user every ~30 ms
 *   - 3 updater fibers  — UPDATE a random user every ~80 ms
 *   - 1 error injector  — UPDATE with a blank name every ~500 ms (exercises ValidationError)
 *   - 1 deleter fiber   — DELETE a random user every ~200 ms when pool > 80
 *   - 1 lister fiber    — LIST all users every 3 s
 *
 * A stats reporter prints per-5-second throughput and error breakdown to stdout.
 * Press Ctrl-C to stop cleanly.
 */
object LoadSimulator {

  import Codecs.json.derived

  private val callTimeout = 5.seconds
  private val poolTarget  = 200
  private val poolMin     = 80
  private val reportEvery = 5.seconds

  // --------------------------------------------------------------------------
  // Stats
  // --------------------------------------------------------------------------

  private final case class Stats(
    creates:       Long = 0,
    getOk:         Long = 0,
    getMissed:     Long = 0,
    updateOk:      Long = 0,
    updateMissed:  Long = 0,
    updateInvalid: Long = 0,
    deletes:       Long = 0,
    lists:         Long = 0,
    infraErrors:   Long = 0
  ) {
    val totalOps: Long =
      creates + getOk + getMissed + updateOk + updateMissed + updateInvalid + deletes + lists

    def report(intervalSecs: Double, poolSize: Int): String = {
      def rate(n: Long) = f"${n / intervalSecs}%.0f/s"
      val totalRate     = f"${totalOps / intervalSecs}%.0f"
      s"""╔═══ Load Report [${intervalSecs.toInt}s interval | $totalRate ops/s | pool: $poolSize users] ═══╗
         |  create  $creates%5d  (${rate(creates)})
         |  get     $getOk%5d ok  $getMissed%4d not-found  (${rate(getOk + getMissed)})
         |  update  $updateOk%5d ok  $updateMissed%4d not-found  $updateInvalid%4d validation  (${rate(updateOk + updateMissed + updateInvalid)})
         |  delete  $deletes%5d  (${rate(deletes)})
         |  list    $lists%5d
         |  infra errors: $infraErrors
         |╚════════════════════════════════════════════════════════════╝""".stripMargin
    }
  }

  // --------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------

  private type Pool = Ref[Vector[String]]

  private val firstNames = Vector(
    "Alice", "Bob", "Carol", "David", "Eve", "Frank", "Grace", "Hank",
    "Iris", "Jack", "Kate", "Liam", "Mia", "Noah", "Olivia", "Paul",
    "Quinn", "Rose", "Sam", "Tina", "Uma", "Victor", "Wendy", "Yara"
  )
  private val domains = Vector("example.com", "test.org", "demo.io", "mail.net")

  private def randomName(): String =
    firstNames(Random.nextInt(firstNames.size))

  private def randomEmail(name: String): String =
    s"${name.toLowerCase}${Random.nextInt(9000) + 1000}@${domains(Random.nextInt(domains.size))}"

  /** Pick a random ID from the pool; returns None if the pool is empty. */
  private def randomId(pool: Pool): UIO[Option[String]] =
    pool.get.map { v =>
      if v.isEmpty then None
      else Some(v(Random.nextInt(v.size)))
    }

  // --------------------------------------------------------------------------
  // Workers
  // --------------------------------------------------------------------------

  /** Creates a user; throttles to once per 500 ms when the pool is at capacity. */
  private def creatorLoop(nats: Nats, pool: Pool, stats: Ref[Stats]): UIO[Nothing] = {
    val step = pool.get.map(_.size).flatMap { size =>
      if size >= poolTarget then ZIO.sleep(500.millis)
      else {
        val name  = randomName()
        val email = randomEmail(name)
        nats
          .requestService(UserEndpoints.createUser, CreateUserRequest(name, email), callTimeout)
          .foldZIO(
            _ => stats.update(s => s.copy(infraErrors = s.infraErrors + 1)),
            user =>
              pool.update(_ :+ user.id) *>
              stats.update(s => s.copy(creates = s.creates + 1))
          )
      }
    }
    step.forever
  }

  /** Gets a random user every ~30 ms. */
  private def readerLoop(nats: Nats, pool: Pool, stats: Ref[Stats]): UIO[Nothing] = {
    val step = randomId(pool).flatMap {
      case None     => ZIO.sleep(50.millis)
      case Some(id) =>
        nats
          .requestService(UserEndpoints.getUser, GetUserRequest(id), callTimeout)
          .foldZIO(
            {
              case _: UserNotFound => stats.update(s => s.copy(getMissed = s.getMissed + 1))
              case _               => stats.update(s => s.copy(infraErrors = s.infraErrors + 1))
            },
            _ => stats.update(s => s.copy(getOk = s.getOk + 1))
          ) *> ZIO.sleep(30.millis)
    }
    step.forever
  }

  /**
   * Updates a random user every ~80 ms.
   *
   * When `injectErrors` is true, one in five requests sends a blank name to
   * exercise the ValidationError path.
   */
  private def updaterLoop(
    nats: Nats,
    pool: Pool,
    stats: Ref[Stats],
    injectErrors: Boolean
  ): UIO[Nothing] = {
    val step = randomId(pool).flatMap {
      case None     => ZIO.sleep(80.millis)
      case Some(id) =>
        val newName =
          if injectErrors && Random.nextInt(5) == 0 then "" // blank → ValidationError
          else randomName()
        nats
          .requestService(
            UserEndpoints.updateUser,
            UpdateUserRequest(id, name = Some(newName)),
            callTimeout
          )
          .foldZIO(
            {
              case _: UserNotFound    => stats.update(s => s.copy(updateMissed = s.updateMissed + 1))
              case _: ValidationError => stats.update(s => s.copy(updateInvalid = s.updateInvalid + 1))
              case _                  => stats.update(s => s.copy(infraErrors = s.infraErrors + 1))
            },
            _ => stats.update(s => s.copy(updateOk = s.updateOk + 1))
          ) *> ZIO.sleep(80.millis)
    }
    step.forever
  }

  /** Deletes a random user every ~200 ms, but only when the pool exceeds poolMin. */
  private def deleterLoop(nats: Nats, pool: Pool, stats: Ref[Stats]): UIO[Nothing] = {
    val step = pool.get.map(_.size).flatMap { size =>
      if size <= poolMin then ZIO.sleep(200.millis)
      else
        randomId(pool).flatMap {
          case None     => ZIO.sleep(200.millis)
          case Some(id) =>
            // Remove optimistically so concurrent deleters don't double-attempt the same ID.
            pool.update(_.filter(_ != id)) *>
            nats
              .requestService(UserEndpoints.deleteUser, DeleteUserRequest(id), callTimeout)
              .foldZIO(
                _ => stats.update(s => s.copy(infraErrors = s.infraErrors + 1)),
                _ => stats.update(s => s.copy(deletes = s.deletes + 1))
              ) *> ZIO.sleep(200.millis)
        }
    }
    step.forever
  }

  /** Lists all users every 3 seconds. */
  private def listerLoop(nats: Nats, stats: Ref[Stats]): UIO[Nothing] = {
    val step =
      nats
        .requestService(UserEndpoints.listUsers, ListUsersRequest(), callTimeout)
        .foldZIO(
          _ => stats.update(s => s.copy(infraErrors = s.infraErrors + 1)),
          _ => stats.update(s => s.copy(lists = s.lists + 1))
        ) *> ZIO.sleep(3.seconds)
    step.forever
  }

  /**
   * Snapshots and resets the stats counters every [[reportEvery]], then prints
   * the interval report to stdout.
   */
  private def reporterLoop(pool: Pool, stats: Ref[Stats]): UIO[Nothing] = {
    val step =
      ZIO.sleep(reportEvery) *>
      pool.get.map(_.size).flatMap { poolSize =>
        stats.getAndSet(Stats()).flatMap { snapshot =>
          Console.printLine(snapshot.report(reportEvery.toSeconds.toDouble, poolSize)).orDie
        }
      }
    step.forever
  }

  // --------------------------------------------------------------------------
  // Entry point
  // --------------------------------------------------------------------------

  /**
   * Runs the load simulator indefinitely (until interrupted).
   *
   * All worker fibers are started concurrently via [[ZIO.collectAllParDiscard]].
   * The first infra error that crashes a fiber will interrupt the others.
   */
  val run: ZIO[Nats, Nothing, Unit] =
    for {
      nats  <- ZIO.service[Nats]
      pool  <- Ref.make(Vector.empty[String])
      stats <- Ref.make(Stats())
      _     <- Console.printLine("Load simulator started. Press Ctrl-C to stop.").orDie
      _     <- ZIO.collectAllParDiscard(
                 Seq(
                   // 3 creator fibers
                   creatorLoop(nats, pool, stats),
                   creatorLoop(nats, pool, stats),
                   creatorLoop(nats, pool, stats),
                   // 8 reader fibers
                   readerLoop(nats, pool, stats),
                   readerLoop(nats, pool, stats),
                   readerLoop(nats, pool, stats),
                   readerLoop(nats, pool, stats),
                   readerLoop(nats, pool, stats),
                   readerLoop(nats, pool, stats),
                   readerLoop(nats, pool, stats),
                   readerLoop(nats, pool, stats),
                   // 3 regular updater fibers + 1 error-injecting updater
                   updaterLoop(nats, pool, stats, injectErrors = false),
                   updaterLoop(nats, pool, stats, injectErrors = false),
                   updaterLoop(nats, pool, stats, injectErrors = false),
                   updaterLoop(nats, pool, stats, injectErrors = true),
                   // deleter and lister
                   deleterLoop(nats, pool, stats),
                   listerLoop(nats, stats),
                   // stats reporter
                   reporterLoop(pool, stats)
                 )
               )
    } yield ()
}
