ThisBuild / scalaVersion := "3.3.7"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")
ThisBuild / Compile / semanticdbEnabled := true
ThisBuild / run / fork := true

val zioNats = "io.github.pietersp" %% "zio-nats" % "0.1.0-RC6"

val assemblySettings = Seq(
  assembly / assemblyMergeStrategy := {
    case "module-info.class" => MergeStrategy.discard
    case "reference.conf"    => MergeStrategy.concat
    case PathList("META-INF", xs @ _*) =>
      xs match {
        // Discard manifests and all JAR signature files (e.g. from bouncycastle)
        case "MANIFEST.MF" :: Nil                                                      => MergeStrategy.discard
        case x :: Nil if x.endsWith(".SF") || x.endsWith(".RSA") || x.endsWith(".DSA") || x.endsWith(".EC") =>
          MergeStrategy.discard
        case _ => MergeStrategy.first
      }
    case _ => MergeStrategy.first
  }
)

/** Shared API contract: domain models, error types, and endpoint descriptors.
  * Both the service and client depend on this project.
  */
lazy val userApi = project
  .in(file("user-api"))
  .settings(
    name := "user-api",
    libraryDependencies += zioNats
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
  .settings(assemblySettings)

/** Load-simulation client: exercises all user service endpoints under concurrent load.
  * Run independently from the service (separate JVM process).
  */
lazy val userClient = project
  .in(file("user-client"))
  .dependsOn(userApi)
  .settings(
    name := "user-client",
    libraryDependencies += zioNats,
    assembly / mainClass       := Some("demo.client.Main"),
    assembly / assemblyJarName := "user-client.jar"
  )
  .settings(assemblySettings)

lazy val root = project
  .in(file("."))
  .aggregate(userApi, userService, userClient)
  .settings(
    name := "zio-nats-service-demo",
    Compile / unmanagedSourceDirectories := Nil,
    Test / unmanagedSourceDirectories    := Nil
  )
