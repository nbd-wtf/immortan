name := "immortan"

version := "0.1"

scalaVersion := "2.11.12"

libraryDependencies += "com.typesafe.akka" % "akka-testkit_2.11" % "2.5.32"

libraryDependencies += "org.scodec" % "scodec-core_2.11" % "1.11.3"

libraryDependencies += "commons-codec" % "commons-codec" % "1.10"

libraryDependencies += "io.reactivex" % "rxscala_2.11" % "0.27.0"

libraryDependencies += "org.json4s" % "json4s-jackson_2.11" % "3.6.7" // Electrum

libraryDependencies += "io.spray" % "spray-json_2.11" % "1.3.5" // Immortan

libraryDependencies += "com.typesafe.akka" % "akka-actor_2.11" % "2.3.14"

libraryDependencies += "io.netty" % "netty-all" % "4.1.42.Final"

libraryDependencies += "com.softwaremill.quicklens" % "quicklens_2.11" % "1.6.1"

libraryDependencies += "org.bouncycastle" % "bcprov-jdk15to18" % "1.68"

libraryDependencies += "com.google.guava" % "guava" % "29.0-android"

// Testing

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "3.1.1"

libraryDependencies += "org.xerial" % "sqlite-jdbc" % "3.27.2.1"

enablePlugins(ReproducibleBuildsPlugin)