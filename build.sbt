name := "mtprototype"

version := "0.1"

scalaVersion := "2.12.7"

resolvers += "Artima Maven Repository" at "http://repo.artima.com/releases"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.17",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.17" % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.17" % Test,
  "org.scodec" %% "scodec-bits" % "1.1.6",
  "org.scodec" %% "scodec-core" % "1.10.3",
  "org.scalactic" %% "scalactic" % "3.0.5",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
)

addSbtPlugin("com.artima.supersafe" % "sbtplugin" % "1.1.3")