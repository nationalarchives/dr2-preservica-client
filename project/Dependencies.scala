import sbt.*
object Dependencies {
  lazy val sttpVersion = "3.9.7"
  private lazy val scalaTestVersion = "3.2.18"
  private lazy val scalaCacheVersion = "1.0.0-M6"

  lazy val catsCore = "org.typelevel" %% "cats-core" % "2.12.0"
  lazy val circeGeneric = "io.circe" %% "circe-generic" % "0.14.7"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion
  lazy val scalaCacheCore = "com.github.cb372" %% "scalacache-core" % scalaCacheVersion
  lazy val scalaCacheCaffeine = "com.github.cb372" %% "scalacache-caffeine" % scalaCacheVersion
  lazy val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "2.3.0"
  lazy val sttpCore = "com.softwaremill.sttp.client3" %% "core" % sttpVersion
  lazy val sttpFs2 = "com.softwaremill.sttp.client3" %% "fs2" % sttpVersion
  lazy val log4Cats = "org.typelevel" %% "log4cats-slf4j" % "2.7.0"
  lazy val sttpCirce = "com.softwaremill.sttp.client3" %% "circe" % sttpVersion
  lazy val awsSecretsManager = "software.amazon.awssdk" % "secretsmanager" % "2.26.1"
  lazy val mockito = "org.scalatestplus" %% "mockito-5-10" % s"$scalaTestVersion.0"
  lazy val wireMock = "com.github.tomakehurst" % "wiremock-jre8" % "2.35.2"
}
