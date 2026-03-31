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
 *   - creators: [[creatorWorkersPer10]] per 10 of poolTarget (throttle when
 *     pool exceeds poolTarget)
 *   - readers: [[readerWorkersPer10]] per 10 of poolTarget — GET a random user
 *     every [[readerDelay]]
 *   - updaters: [[updaterWorkersPer10]] per 10 of poolTarget — UPDATE a random
 *     user every [[updaterDelay]]
 *   - 1 error injector — UPDATE with a blank name every [[updaterDelay]]
 *     (exercises ValidationError)
 *   - 1 deleter fiber — DELETE a random user every [[deleterDelay]] when pool >
 *     poolMin
 *   - 1 lister fiber — LIST all users every [[listerDelay]]
 *
 * Configuration is loaded via [[LoadSimulatorConfig]] from `application.conf`
 * (HOCON), falling back to environment variables.
 *
 * A stats reporter prints per-interval throughput and error breakdown to
 * stdout. Press Ctrl-C to stop cleanly.
 */
object LoadSimulator {

  import Codecs.json.derived

  /** Shared context passed to every worker fiber. */
  private final case class WorkerCtx(
    nats: Nats,
    pool: Pool,
    stats: Ref[Stats],
    cfg: LoadSimulatorConfig
  )

  private enum Op { case Create, Get, Update, Delete, List }

  private type Pool = Ref[Vector[String]]

  private val firstNames = Vector(
    "Alice",
    "Bob",
    "Carol",
    "David",
    "Eve",
    "Frank",
    "Grace",
    "Hank",
    "Iris",
    "Jack",
    "Kate",
    "Liam",
    "Mia",
    "Noah",
    "Olivia",
    "Paul",
    "Quinn",
    "Rose",
    "Sam",
    "Tina",
    "Uma",
    "Victor",
    "Wendy",
    "Yara"
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

  private def scaledWorkers(n: Int, per10: Int): Int = ((n * per10) / 10).max(1)

  private def workerCounts(cfg: LoadSimulatorConfig): (Int, Int, Int) =
    (
      scaledWorkers(cfg.poolTarget, cfg.creatorWorkersPer10),
      scaledWorkers(cfg.poolTarget, cfg.readerWorkersPer10),
      scaledWorkers(cfg.poolTarget, cfg.updaterWorkersPer10)
    )

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
      def rate(n: Long)                       = f"${n / intervalSecs}%.0f/s"
      def rateDecimal(n: Long)                = f"${n / intervalSecs}%.3f/s"
      def infraLine(count: Long, msg: String) =
        if (count > 0) f"$count%5d  ${msg.take(60)}" else "     -"

      def createRow(workers: Int) =
        f"""  ${"create"}%-8s ${workers}%8d ${rate(creates)}%8s  ${"-"}%9s  ${updateInvalid}%10d  ${infraLine(
            createInfraErrors,
            createInfraErrorMsg
          )}%62s"""

      def getRow(workers: Int) =
        f"""  ${"get"}%-8s ${workers}%8d ${rate(getOk + getMissed)}%8s  ${getMissed}%9d  ${"-"}%10s  ${infraLine(
            getInfraErrors,
            getInfraErrorMsg
          )}%62s"""

      def updateRow(workers: Int) =
        f"""  ${"update"}%-8s ${workers}%8d ${rate(
            updateOk + updateMissed + updateInvalid
          )}%8s  ${updateMissed}%9d  ${updateInvalid}%10d  ${infraLine(updateInfraErrors, updateInfraErrorMsg)}%62s"""

      def deleteRow =
        f"""  ${"delete"}%-8s ${1}%8d ${rate(deletes)}%8s  ${0}%9d  ${"-"}%10s  ${infraLine(
            deleteInfraErrors,
            deleteInfraErrorMsg
          )}%62s"""

      def listRow =
        f"""  ${"list"}%-8s ${1}%8d ${rateDecimal(lists)}%8s  ${"-"}%9s  ${"-"}%10s  ${infraLine(
            listInfraErrors,
            listInfraErrorMsg
          )}%62s"""

      val totalRate = f"${totalOps / intervalSecs}%.0f"
      val content   = f" Load Report [${intervalSecs.toInt}s interval | $totalRate ops/s | pool: $poolSize users] "
      val width     = content.length + 8
      val topLine   = "╔═══" + content + "═══╗"
      val botLine   = "╚" + ("═" * (width - 2)) + "╝"
      val header    =
        f"""  ${"OP"}%-8s ${"WORKERS"}%8s ${"RATE"}%8s  ${"NOT_FOUND"}%9s  ${"VALIDATION"}%10s  ${"INFRA"}%62s"""

      s"""$topLine
         |$header
         |${createRow(numCreators)}
         |${getRow(numReaders)}
         |${updateRow(numUpdaters)}
         |${deleteRow}
         |${listRow}
         |  infra errors: $infraErrors  $lastInfraError
         |$botLine""".stripMargin
    }
  }

  // --------------------------------------------------------------------------
  // Infra error tracking
  // --------------------------------------------------------------------------

  private def trackInfra(stats: Ref[Stats], op: Op)(err: NatsError): UIO[Unit] =
    stats.update { s =>
      val base = s.copy(infraErrors = s.infraErrors + 1, lastInfraError = err.toString)
      op match
        case Op.Create => base.copy(createInfraErrors = s.createInfraErrors + 1, createInfraErrorMsg = err.toString)
        case Op.Get    => base.copy(getInfraErrors = s.getInfraErrors + 1, getInfraErrorMsg = err.toString)
        case Op.Update => base.copy(updateInfraErrors = s.updateInfraErrors + 1, updateInfraErrorMsg = err.toString)
        case Op.Delete => base.copy(deleteInfraErrors = s.deleteInfraErrors + 1, deleteInfraErrorMsg = err.toString)
        case Op.List   => base.copy(listInfraErrors = s.listInfraErrors + 1, listInfraErrorMsg = err.toString)
    }

  // --------------------------------------------------------------------------
  // Workers
  // --------------------------------------------------------------------------

  /**
   * Creates a user; throttles when the pool is at capacity.
   */
  private def creatorLoop(ctx: WorkerCtx): UIO[Nothing] = {
    val WorkerCtx(nats, pool, stats, cfg) = ctx
    val step                              = pool.get.map(_.size).flatMap { size =>
      if size >= cfg.poolTarget then ZIO.sleep(cfg.creatorThrottleDelay)
      else {
        val name  = randomName()
        val email = randomEmail(name)
        nats
          .requestService(UserEndpoints.createUser, CreateUserRequest(name, email), cfg.callTimeout)
          .foldZIO(
            {
              case _: ValidationError => ZIO.unit
              case err: NatsError     => trackInfra(stats, Op.Create)(err)
            },
            user =>
              pool.update(_ :+ user.id) *>
                stats.update(s => s.copy(creates = s.creates + 1))
          )
      }
    }
    step.forever
  }

  /** Gets a random user. */
  private def readerLoop(ctx: WorkerCtx): UIO[Nothing] = {
    val WorkerCtx(nats, pool, stats, cfg) = ctx
    val step                              = randomId(pool).flatMap {
      case None     => ZIO.sleep(cfg.readerDelay)
      case Some(id) =>
        nats
          .requestService(UserEndpoints.getUser, GetUserRequest(id), cfg.callTimeout)
          .foldZIO(
            {
              case _: UserNotFound => stats.update(s => s.copy(getMissed = s.getMissed + 1))
              case err: NatsError  => trackInfra(stats, Op.Get)(err)
            },
            _ => stats.update(s => s.copy(getOk = s.getOk + 1))
          ) *> ZIO.sleep(cfg.readerDelay)
    }
    step.forever
  }

  /**
   * Updates a random user.
   *
   * When `injectErrors` is true, one in five requests sends a blank name to
   * exercise the ValidationError path.
   */
  private def updaterLoop(ctx: WorkerCtx, injectErrors: Boolean): UIO[Nothing] = {
    val WorkerCtx(nats, pool, stats, cfg) = ctx
    val step                              = randomId(pool).flatMap {
      case None     => ZIO.sleep(cfg.updaterDelay)
      case Some(id) =>
        val newName =
          if injectErrors && Random.nextInt(5) == 0 then ""
          else randomName()
        nats
          .requestService(
            UserEndpoints.updateUser,
            UpdateUserRequest(id, name = Some(newName)),
            cfg.callTimeout
          )
          .foldZIO(
            {
              case _: UserNotFound    => stats.update(s => s.copy(updateMissed = s.updateMissed + 1))
              case _: ValidationError => stats.update(s => s.copy(updateInvalid = s.updateInvalid + 1))
              case err: NatsError     => trackInfra(stats, Op.Update)(err)
            },
            _ => stats.update(s => s.copy(updateOk = s.updateOk + 1))
          ) *> ZIO.sleep(cfg.updaterDelay)
    }
    step.forever
  }

  /**
   * Deletes a random user, but only when the pool exceeds poolMin.
   */
  private def deleterLoop(ctx: WorkerCtx): UIO[Nothing] = {
    val WorkerCtx(nats, pool, stats, cfg) = ctx
    val step                              = pool.get.map(_.size).flatMap { size =>
      if size <= cfg.poolMin then ZIO.sleep(cfg.deleterDelay)
      else
        randomId(pool).flatMap {
          case None     => ZIO.sleep(cfg.deleterDelay)
          case Some(id) =>
            nats
              .requestService(UserEndpoints.deleteUser, DeleteUserRequest(id), cfg.callTimeout)
              .foldZIO(
                {
                  case _: UserNotFound => pool.update(_.filterNot(_ == id))
                  case err: NatsError  => trackInfra(stats, Op.Delete)(err)
                },
                _ => pool.update(_.filterNot(_ == id)) *> stats.update(s => s.copy(deletes = s.deletes + 1))
              ) *> ZIO.sleep(cfg.deleterDelay)
        }
    }
    step.forever
  }

  /** Lists all users. */
  private def listerLoop(ctx: WorkerCtx): UIO[Nothing] = {
    val WorkerCtx(nats, pool, stats, cfg) = ctx
    val step                              =
      nats
        .requestService(UserEndpoints.listUsers, ListUsersRequest(), cfg.callTimeout)
        .foldZIO(
          { case err: NatsError => trackInfra(stats, Op.List)(err) },
          _ => stats.update(s => s.copy(lists = s.lists + 1))
        ) *> ZIO.sleep(cfg.listerDelay)
    step.forever
  }

  /**
   * Snapshots and resets the stats counters every [[reportEvery]], then prints
   * the interval report to stdout.
   */
  private def reporterLoop(ctx: WorkerCtx): UIO[Nothing] = {
    val WorkerCtx(_, pool, stats, cfg)         = ctx
    val (numCreators, numReaders, numUpdaters) = workerCounts(cfg)
    val step                                   =
      pool.get
        .map(_.size)
        .flatMap { poolSize =>
          stats.getAndUpdate(s => Stats(lastInfraError = s.lastInfraError)).flatMap { snapshot =>
            Console
              .printLine(
                snapshot.report(cfg.reportEvery.toSeconds.toDouble, poolSize, numCreators, numReaders, numUpdaters)
              )
              .orDie
          }
        }
        .delay(cfg.reportEvery)
    step.forever
  }

  // --------------------------------------------------------------------------
  // Entry point
  // --------------------------------------------------------------------------

  /**
   * Runs the load simulator indefinitely (until interrupted).
   *
   * All worker fibers are started concurrently via
   * [[ZIO.collectAllParDiscard]].
   */
  val run: ZIO[Nats & LoadSimulatorConfig, Nothing, Unit] =
    for {
      cfg                                   <- ZIO.service[LoadSimulatorConfig]
      nats                                  <- ZIO.service[Nats]
      pool                                  <- Ref.make(Vector.empty[String])
      stats                                 <- Ref.make(Stats())
      _                                     <- Console.printLine("Load simulator started. Press Ctrl-C to stop.").orDie
      ctx                                    = WorkerCtx(nats, pool, stats, cfg)
      (numCreators, numReaders, numUpdaters) = workerCounts(cfg)
      _                                     <-
        ZIO.collectAllParDiscard(
          List.fill(numCreators)(creatorLoop(ctx))
            ::: List.fill(numReaders)(readerLoop(ctx))
            ::: List.fill(numUpdaters)(updaterLoop(ctx, injectErrors = false))
            ::: List(updaterLoop(ctx, injectErrors = true))
            ::: List(deleterLoop(ctx), listerLoop(ctx), reporterLoop(ctx))
        )
    } yield ()
}
