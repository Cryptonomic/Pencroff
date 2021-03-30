name := "ingestor"

version := "0.1"
scalaVersion := "2.13.2"

val circeVersion = "0.13.0"
val akkaHttpVersion = "10.1.8"
val http4sVersion = "1.0.0-M3"
val pureConfigVersion = "0.12.3"
val catsVersion = "2.1.1"
libraryDependencies ++= Seq(
  "org.apache.kudu"             % "kudu-client"         % "1.11.1",
  "ch.qos.logback"              % "logback-classic"     % "1.2.3",
  "com.typesafe.scala-logging" %% "scala-logging"       % "3.9.2",
  "com.typesafe.akka"          %% "akka-actor-typed"    % "2.6.6",
  "org.typelevel"              %% "cats-core"           % catsVersion,
  "org.typelevel"              %% "cats-kernel"         % catsVersion,
  "org.typelevel"              %% "cats-macros"         % catsVersion,
  "com.github.pureconfig"      %% "pureconfig"          % pureConfigVersion,
  "com.github.pureconfig"      %% "pureconfig-enum"     % pureConfigVersion,
  "io.circe"                   %% "circe-core"          % circeVersion,
  "io.circe"                   %% "circe-parser"        % circeVersion,
  "io.circe"                   %% "circe-generic"       % circeVersion,
  "io.circe"                   %% "circe-optics"        % circeVersion,
  "com.github.scopt"           %% "scopt"               % "3.7.1",
  "org.http4s"                 %% "http4s-dsl"          % http4sVersion,
  "org.http4s"                 %% "http4s-blaze-server" % http4sVersion,
  "org.http4s"                 %% "http4s-blaze-client" % http4sVersion
)

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

assemblyMergeStrategy in assembly := {
  case "META-INF/io.netty.versions.properties" => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

assemblyJarName in assembly := s"pencroff-${version.value}.jar"
scalacOptions += "-deprecation"
