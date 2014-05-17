name := "Spray Client test"

version := "0.1"

scalaVersion := "2.10.3"

//scalaVersion := "2.11.0"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies ++= Seq(
//  "io.spray" % "spray-client" % "1.3.1",
//  "com.typesafe.akka" %% "akka-actor" % "2.3.2"
  "io.spray" % "spray-client" % "1.2.1",
  "com.typesafe.akka" %% "akka-actor" % "2.2.4"
)
