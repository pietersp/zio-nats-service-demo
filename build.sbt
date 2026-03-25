lazy val root = project
  .in(file("."))
  .settings(
    name         := "zio-nats-service-demo",
    scalaVersion := "3.3.7",
    scalacOptions ++= Seq("-deprecation", "-feature"),
    // Fork a separate JVM so Ctrl-C sends SIGINT to the app process directly,
    // allowing ZIO's shutdown hook to run and close the NATS connection cleanly.
    run / fork := true,
    libraryDependencies ++= Seq(
      "io.github.pietersp" %% "zio-nats" % "0.1.0-RC6"
    )
  )
