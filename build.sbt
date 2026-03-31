ThisBuild / scalaVersion := "3.3.7"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")
ThisBuild / Compile / semanticdbEnabled := true
ThisBuild / run / fork                  := true

val zioNats           = "io.github.pietersp" %% "zio-nats"            % "0.1.0-RC10"
val zioConfigTypesafe = "dev.zio"            %% "zio-config-typesafe" % "4.0.4"

/**
 * sbt-assembly merge strategy.
 *
 * sbt-assembly performs a deduplicate check BEFORE applying merge strategies.
 * If the same entry path appears in two JARs with different contents, assembly
 * fails immediately — the strategy never gets a chance to resolve it.
 *
 * Multi-release JARs (bcprov-lts8on, jspecify) both ship conflicting copies of
 * META-INF/versions/9/module-info.class and OSGI-INF/MANIFEST.MF, so these
 * paths must be explicitly discarded.
 */
ThisBuild / assemblyMergeStrategy := {
  // Multi-release JAR entries (Java 9+ class files)
  case PathList("META-INF", "versions", _*) => MergeStrategy.discard
  // JP9 module descriptors inside META-INF/services
  case PathList("META-INF", "services", xs @ _*) if xs.last == "module-info" => MergeStrategy.discard
  // Standalone module-info.class files
  case s if s.endsWith("module-info.class") => MergeStrategy.discard
  // HOCON reference files from multiple libraries
  case "reference.conf" => MergeStrategy.concat
  // OSGI / JAR manifests
  case PathList("META-INF", xs @ _*) if xs.last.contains("MANIFEST") => MergeStrategy.discard
  // JAR signature files (e.g. from bouncycastle)
  case s if s.endsWith(".SF") || s.endsWith(".RSA") || s.endsWith(".DSA") || s.endsWith(".EC") =>
    MergeStrategy.discard
  case x =>
    val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
    oldStrategy(x)
}

/**
 * Shared API contract: domain models, error types, and endpoint descriptors.
 * Both the service and client depend on this project.
 */
lazy val userApi = project
  .in(file("user-api"))
  .settings(
    name := "user-api",
    libraryDependencies += zioNats,
    assembly / skip := true
  )

/** NATS microservice: user management backed by NATS KV. */
lazy val userService = project
  .in(file("user-service"))
  .dependsOn(userApi)
  .settings(
    name := "user-service",
    libraryDependencies += zioNats,
    assembly / mainClass       := Some("demo.Main"),
    assembly / assemblyJarName := "user-service.jar"
  )

/**
 * Load-simulation client: exercises all user service endpoints under concurrent
 * load. Run independently from the service (separate JVM process).
 */
lazy val userClient = project
  .in(file("user-client"))
  .dependsOn(userApi)
  .settings(
    name := "user-client",
    libraryDependencies ++= Seq(zioNats, zioConfigTypesafe),
    assembly / mainClass       := Some("demo.client.Main"),
    assembly / assemblyJarName := "user-client.jar"
  )

lazy val root = project
  .in(file("."))
  .aggregate(userApi, userService, userClient)
  .settings(
    name                                 := "zio-nats-service-demo",
    Compile / unmanagedSourceDirectories := Nil,
    Test / unmanagedSourceDirectories    := Nil
  )
