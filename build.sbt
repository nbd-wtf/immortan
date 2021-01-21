name := "immortan"

version := "0.1"

scalaVersion := "2.11.12"

// Core lib

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "3.2.3"

libraryDependencies += "com.softwaremill.quicklens" % "quicklens_2.11" % "1.6.1"

libraryDependencies += "org.scodec" % "scodec-core_2.11" % "1.11.3"

libraryDependencies += "commons-codec" % "commons-codec" % "1.10"

libraryDependencies += "io.reactivex" % "rxscala_2.11" % "0.27.0"

libraryDependencies += "org.json4s" % "json4s-jackson_2.11" % "3.6.7" // Electrum

libraryDependencies += "io.spray" % "spray-json_2.11" % "1.3.5" // Immortan

libraryDependencies += "com.typesafe.akka" % "akka-actor_2.11" % "2.3.14"

libraryDependencies += "org.openlabtesting.netty" % "netty-all" % "4.1.48.Final"

libraryDependencies += "com.softwaremill.sttp" % "json4s_2.11" % "1.3.9"

// Bitcoinj

libraryDependencies += "com.squareup.okhttp3" % "okhttp" % "3.12.11"

libraryDependencies += "org.bouncycastle" % "bcprov-jdk15to18" % "1.68"

libraryDependencies += "com.google.protobuf" % "protobuf-java" % "3.9.2"

libraryDependencies += "com.google.guava" % "guava" % "29.0-android"

libraryDependencies += "net.jcip" % "jcip-annotations" % "1.0"

libraryDependencies += "com.squareup.okio" % "okio" % "1.15.0"

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.30"

enablePlugins(ReproducibleBuildsPlugin)