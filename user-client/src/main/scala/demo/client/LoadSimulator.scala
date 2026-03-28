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
 * Worker counts scale dynamically with poolTarget:
 *   - creators: 1 per 1k of poolTarget (throttle when pool exceeds poolTarget)
 *   - readers: 1 per 500 of poolTarget — GET a random user every ~30 ms
 *   - updaters: 1 per 1k of poolTarget — UPDATE a random user every ~80 ms
 *   - 1 error injector — UPDATE with a blank name every ~500 ms (exercises
 *     ValidationError)
 *   - 1 deleter fiber — DELETE a random user every ~200 ms when pool > 80
 *   - 1 lister fiber — LIST all users every 3 s
 *
 * A stats reporter prints per-5-second throughput and error breakdown to
 * stdout. Press Ctrl-C to stop cleanly.
 */
object LoadSimulator {

  import Codecs.json.derived

  private val callTimeout = 5.seconds
  private val poolTarget  = 1000
  private val poolMin     = 80
  private val reportEvery = 5.seconds

  private def scaledWorkers(n: Int): Int = (n / 10).max(1)

  private val numCreators = scaledWorkers(poolTarget)     // 1 per 1k target
  private val numReaders  = scaledWorkers(poolTarget * 2) // 1 per 500 target
  private val numUpdaters = scaledWorkers(poolTarget)     // 1 per 1k target

  // --------------------------------------------------------------------------
  // Stats
  // --------------------------------------------------------------------------

  private final case class Stats(
    creates: Long = 0,
    getOk: Long = 0,
    getMissed: Long = 0,
    updateOk: Long = 0,
    updateMissed: Long = 0,
    updateInvalid: Long = 0,
    deletes: Long = 0,
    lists: Long = 0,
    infraErrors: Long = 0,
    createInfraErrors: Long = 0,
    createInfraErrorMsg: String = "",
    getInfraErrors: Long = 0,
    getInfraErrorMsg: String = "",
    updateInfraErrors: Long = 0,
    updateInfraErrorMsg: String = "",
    deleteInfraErrors: Long = 0,
    deleteInfraErrorMsg: String = "",
    listInfraErrors: Long = 0,
    listInfraErrorMsg: String = "",
    lastInfraError: String = ""
  ) {
    val totalOps: Long =
      creates + getOk + getMissed + updateOk + updateMissed + updateInvalid + deletes + lists

    def report(intervalSecs: Double, poolSize: Int, numCreators: Int, numReaders: Int, numUpdaters: Int): String = {
      def rate(n: Long) = f"${n / intervalSecs}%.0f/s"
      def rateDecimal(n: Long) = f"${n / intervalSecs}%.3f/s"
      def infraLine(count: Long, msg: String) = if (count > 0) f"$count%5d  ${msg.take(60)}" else "     -"
      val totalRate     = f"${totalOps / intervalSecs}%.0f"
      val content       = f" Load Report [${intervalSecs.toInt}s interval | $totalRate ops/s | pool: $poolSize users] "
      val width         = content.length + 8
      val topLine       = "╔═══" + content + "═══╗"
      val botLine       = "╚" + ("═" * (width - 2)) + "╝"
      val header        = f"""  ${"OP"}%-8s ${"WORKERS"}%8s ${"RATE"}%8s  ${"NOT_FOUND"}%9s  ${"VALIDATION"}%10s  ${"INFRA"}%62s"""
      val createLine    = f"""  ${"create"}%-8s ${numCreators}%8d ${rate(creates)}%8s  ${"-"}%9s  ${updateInvalid}%10d  ${infraLine(createInfraErrors, createInfraErrorMsg)}%62s"""
      val getLine       = f"""  ${"get"}%-8s ${numReaders}%8d ${rate(getOk + getMissed)}%8s  ${getMissed}%9d  ${"-"}%10s  ${infraLine(getInfraErrors, getInfraErrorMsg)}%62s"""
      val updateLine    = f"""  ${"update"}%-8s ${numUpdaters}%8d ${rate(updateOk + updateMissed + updateInvalid)}%8s  ${updateMissed}%9d  ${updateInvalid}%10d  ${infraLine(updateInfraErrors, updateInfraErrorMsg)}%62s"""
      val deleteLine    = f"""  ${"delete"}%-8s ${1}%8d ${rate(deletes)}%8s  ${0}%9d  ${"-"}%10s  ${infraLine(deleteInfraErrors, deleteInfraErrorMsg)}%62s"""
      val listLine      = f"""  ${"list"}%-8s ${1}%8d ${rateDecimal(lists)}%8s  ${"-"}%9s  ${"-"}%10s  ${infraLine(listInfraErrors, listInfraErrorMsg)}%62s"""
      s"""$topLine\n$header\n$createLine\n$getLine\n$updateLine\n$deleteLine\n$listLine\n  infra errors: $infraErrors  $lastInfraError\n$botLine"""
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

  /**
   * Creates a user; throttles to once per 500 ms when the pool is at capacity.
   */
  private def creatorLoop(nats: Nats, pool: Pool, stats: Ref[Stats]): UIO[Nothing] = {
    val step = pool.get.map(_.size).flatMap { size =>
      if size >= poolTarget then ZIO.sleep(500.millis)
      else {
        val name  = randomName()
        val email = randomEmail(name)
        nats
          .requestService(UserEndpoints.createUser, CreateUserRequest(name, email), callTimeout)
          .foldZIO(
            {
              case _: ValidationError => ZIO.unit // Business error - don't count as infra
              case err: NatsError => stats.update(s => s.copy(
                infraErrors = s.infraErrors + 1,
                createInfraErrors = s.createInfraErrors + 1,
                createInfraErrorMsg = err.toString,
                lastInfraError = err.toString))
            },
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
              case err: NatsError  => stats.update(s => s.copy(
                infraErrors = s.infraErrors + 1,
                getInfraErrors = s.getInfraErrors + 1,
                getInfraErrorMsg = err.toString,
                lastInfraError = err.toString))
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
              case err: NatsError       => stats.update(s => s.copy(
                infraErrors = s.infraErrors + 1,
                updateInfraErrors = s.updateInfraErrors + 1,
                updateInfraErrorMsg = err.toString,
                lastInfraError = err.toString))
            },
            _ => stats.update(s => s.copy(updateOk = s.updateOk + 1))
          ) *> ZIO.sleep(80.millis)
    }
    step.forever
  }

  /**
   * Deletes a random user every ~200 ms, but only when the pool exceeds
   * poolMin.
   */
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
                  {
                    case _: UserNotFound => ZIO.unit // Optimistic delete - already removed from pool
                    case err: NatsError => stats.update(s => s.copy(
                      infraErrors = s.infraErrors + 1,
                      deleteInfraErrors = s.deleteInfraErrors + 1,
                      deleteInfraErrorMsg = err.toString,
                      lastInfraError = err.toString))
                  },
                  _ => stats.update(s => s.copy(deletes = s.deletes + 1))
                ) *> ZIO.sleep(200.millis)
        }
    }
    step.forever
  }

  /** Lists all users every second. */
  private def listerLoop(nats: Nats, stats: Ref[Stats]): UIO[Nothing] = {
    val step =
      nats
        .requestService(UserEndpoints.listUsers, ListUsersRequest(), callTimeout)
        .foldZIO(
          { case err: NatsError => stats.update(s => s.copy(
            infraErrors = s.infraErrors + 1,
            listInfraErrors = s.listInfraErrors + 1,
            listInfraErrorMsg = err.toString,
            lastInfraError = err.toString)) },
          _ => stats.update(s => s.copy(lists = s.lists + 1))
        ) *> ZIO.sleep(1.seconds)
    step.forever
  }

  /**
   * Snapshots and resets the stats counters every [[reportEvery]], then prints
   * the interval report to stdout.
   */
  private def reporterLoop(pool: Pool, stats: Ref[Stats]): UIO[Nothing] = {
    val step =
      pool.get.map(_.size).flatMap { poolSize =>
        stats.getAndUpdate(s => Stats(lastInfraError = s.lastInfraError)).flatMap { snapshot =>
          Console
            .printLine(
              snapshot.report(reportEvery.toSeconds.toDouble, poolSize, numCreators, numReaders, numUpdaters)
            )
            .orDie
        }
      }.delay(reportEvery)
    step.forever
  }

  // --------------------------------------------------------------------------
  // Entry point
  // --------------------------------------------------------------------------

  /**
   * Runs the load simulator indefinitely (until interrupted).
   *
   * All worker fibers are started concurrently via
   * [[ZIO.collectAllParDiscard]]. The first infra error that crashes a fiber
   * will interrupt the others.
   */
  val run: ZIO[Nats, Nothing, Unit] =
    for {
      nats  <- ZIO.service[Nats]
      pool  <- Ref.make(Vector.empty[String])
      stats <- Ref.make(Stats())
      _     <- Console.printLine("Load simulator started. Press Ctrl-C to stop.").orDie
      _     <- ZIO.collectAllParDiscard(
             List.fill(numCreators)(creatorLoop(nats, pool, stats))
               ::: List.fill(numReaders)(readerLoop(nats, pool, stats))
               ::: List.fill(numUpdaters)(updaterLoop(nats, pool, stats, injectErrors = false))
               ::: List(updaterLoop(nats, pool, stats, injectErrors = true))
               ::: List(deleterLoop(nats, pool, stats), listerLoop(nats, stats), reporterLoop(pool, stats))
           )
    } yield ()
}
