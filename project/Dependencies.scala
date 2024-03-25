import sbt._
object Dependencies {
  lazy val sttpVersion = "3.9.5"
  lazy val scalaCacheVersion = "1.0.0-M6"

  lazy val catsCore = "org.typelevel" %% "cats-core" % "2.10.0"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.18"
  lazy val scalaCacheCore = "com.github.cb372" %% "scalacache-core" % scalaCacheVersion
  lazy val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "2.2.0"
  lazy val dynamoFormatters = "uk.gov.nationalarchives" %% "dynamo-formatters" % "0.0.9"
  lazy val sttpCore = "com.softwaremill.sttp.client3" %% "core" % sttpVersion
  lazy val sttpFs2 = "com.softwaremill.sttp.client3" %% "fs2" % sttpVersion
  lazy val log4Cats = "org.typelevel" %% "log4cats-slf4j" % "2.6.0"
  lazy val sttpUpickle = "com.softwaremill.sttp.client3" %% "upickle" % sttpVersion
  lazy val sttpZio = "com.softwaremill.sttp.client3" %% "zio" % sttpVersion
  lazy val awsSecretsManager = "software.amazon.awssdk" % "secretsmanager" % "2.25.16"
  lazy val mockito = "org.mockito" %% "mockito-scala" % "1.17.30"
  lazy val wireMock = "com.github.tomakehurst" % "wiremock-jre8" % "2.35.2"
  lazy val zioInteropCats = "dev.zio" %% "zio-interop-cats" % "23.1.0.1"
}
