import sbt.*
object Dependencies {
  lazy val sttpVersion = "4.0.22"
  private lazy val scalaTestVersion = "3.2.20"
  private lazy val scalaCacheVersion = "1.0.0-M6"
  private lazy val nettyVersion = "4.2.12.Final"

  lazy val catsRetry = "com.github.cb372" %% "cats-retry" % "4.0.0"
  lazy val catsCore = "org.typelevel" %% "cats-core" % "2.13.0"
  lazy val fs2Core = "co.fs2" %% "fs2-core" % "3.13.0"
  lazy val nettyBuffer = "io.netty" % "netty-buffer" % nettyVersion
  lazy val nettyCodecHttp2 = "io.netty" % "netty-codec-http2" % nettyVersion
  lazy val nettyCodecHttp = "io.netty" % "netty-codec-http" % nettyVersion
  lazy val nettyCodec = "io.netty" % "netty-codec" % nettyVersion
  lazy val nettyCommon = "io.netty" % "netty-common" % nettyVersion
  lazy val nettyHandler = "io.netty" % "netty-handler" % nettyVersion
  lazy val nettyResolver = "io.netty" % "netty-resolver" % nettyVersion
  lazy val nettyTransportClasses = "io.netty" % "netty-transport-classes-epoll" % nettyVersion
  lazy val nettyTransport = "io.netty" % "netty-transport" % nettyVersion
  lazy val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion
  lazy val scalaCacheCore = "com.github.cb372" %% "scalacache-core" % scalaCacheVersion
  lazy val scalaCacheCaffeine = "com.github.cb372" %% "scalacache-caffeine" % scalaCacheVersion
  lazy val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "2.4.0"
  lazy val sttpCore = "com.softwaremill.sttp.client4" %% "core" % sttpVersion
  lazy val sttpSlf4j = "com.softwaremill.sttp.client4" %% "slf4j-backend" % sttpVersion
  lazy val sttpFs2 = "com.softwaremill.sttp.client4" %% "fs2" % sttpVersion
  lazy val log4Cats = "org.typelevel" %% "log4cats-slf4j" % "2.8.0"
  lazy val sttpCirce = "com.softwaremill.sttp.client4" %% "circe" % sttpVersion
  lazy val secretsManagerClient = "uk.gov.nationalarchives" %% "da-secretsmanager-client" % "0.1.155"
  lazy val mockito = "org.scalatestplus" %% "mockito-5-10" % "3.2.18.0"
  lazy val wireMock = "com.github.tomakehurst" % "wiremock-jre8" % "3.0.1"
}
