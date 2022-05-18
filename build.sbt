name                   := "immortan"
organization           := "com.fiatjaf"
scalaVersion           := "2.13.8"
version                := "0.6.0-SNAPSHOT"
sonatypeProfileName    := "com.fiatjaf"
homepage               := Some(url("https://github.com/fiatjaf/immortan"))
scmInfo                := Some(ScmInfo(url("https://github.com/fiatjaf/immortan"), "git@github.com:fiatjaf/immortan.git"))
licenses               += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
developers             := List(
  Developer(id="fiatjaf", name="fiatjaf", email="fiatjaf@gmail.com", url=url("https://fiatjaf.com/")),
  Developer(id="akumaigorodski", name="akumaigorodski", email="akumaigorodski@gmail.com", url=url("https://sbw.app/"))
)
publishMavenStyle      := true
publishTo              := sonatypePublishToBundle.value
sonatypeCredentialHost := "s01.oss.sonatype.org"
libraryDependencies   ++= Seq(
  "com.google.guava" % "guava" % "31.1-jre",
  "org.scala-lang.modules" % "scala-parser-combinators_2.13" % "2.1.0",
  "fr.acinq.secp256k1" % "secp256k1-kmp-jni-jvm" % "0.6.3",
  "org.scodec" % "scodec-core_2.13" % "1.11.9",
  "commons-codec" % "commons-codec" % "1.10",
  "io.reactivex" % "rxscala_2.13" % "0.27.0",
  "org.json4s" % "json4s-native_2.13" % "3.6.7",
  "io.spray" % "spray-json_2.13" % "1.3.5",
  "com.typesafe.akka" % "akka-actor_2.13" % "2.6.9",
  "io.netty" % "netty-all" % "4.1.42.Final",
  "com.softwaremill.quicklens" % "quicklens_2.13" % "1.8.4",
  "org.bouncycastle" % "bcprov-jdk15to18" % "1.68",
  "com.sparrowwallet" % "hummingbird" % "1.6.2"
)
scalacOptions        ++= Seq("-deprecation", "-feature")
