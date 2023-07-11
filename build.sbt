import sbtrelease.ReleaseStateTransformations._
import Dependencies._

lazy val scala2Version = "2.13.10"

lazy val releaseSettings = Seq(
  useGpgPinentry := true,
  publishTo := sonatypePublishToBundle.value,
  publishMavenStyle := true,

  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommand("publishSigned"),
    releaseStepCommand("sonatypeBundleRelease"),
    setNextVersion,
    commitNextVersion,
    pushChanges
  ),
  resolvers +=
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  version := (ThisBuild / version).value,
  organization := "uk.gov.nationalarchives",
  organizationName := "National Archives",

  scmInfo := Some(
    ScmInfo(
      url("https://github.com/nationalarchives/dp-preservica-client"),
      "git@github.com:nationalarchives/dp-preservica-client.git"
    )
  ),
  developers := List(
    Developer(
      id = "tna-digital-archiving-jenkins",
      name = "TNA Digital Archiving",
      email = "digitalpreservation@nationalarchives.gov.uk",
      url = url("https://github.com/nationalarchives/dp-preservica-client")
    )
  ),
  description := "A client to communicate with the Preservica API",
  licenses := List("MIT" -> new URL("https://choosealicense.com/licenses/mit/")),
  homepage := Some(url("https://github.com/nationalarchives/dp-preservica-client"))
)

lazy val commonSettings = Seq(
  scalaVersion := scala2Version,
  libraryDependencies ++= Seq(
    awsSecretsManager,
    catsCore,
    log4Cats,
    scalaCacheCaffeine,
    scalaXml,
    sttpCore,
    sttpFs2,
    sttpUpickle,
    sttpZio,
    zioInteropCats,
    scalaTest % Test,
    wireMock % Test,
  ),
  Test / fork := true,
  Test / envVars := Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_ACCESS_KEY" -> "test")
) ++ releaseSettings

lazy val fs2Ref = LocalProject("fs2")
lazy val zioRef = LocalProject("zio")

lazy val root: Project = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    name := "preservica-client-root"
  )
  .aggregate(fs2Ref, zioRef)

lazy val fs2 = project
  .in(file("fs2"))
  .settings(commonSettings)
  .settings(
    name := "preservica-client-fs2",
    libraryDependencies += sttpFs2
  ).dependsOn(root % "compile->compile;test->test")

lazy val zio = project
  .in(file("zio"))
  .settings(commonSettings)
  .settings(
    name := "preservica-client-zio",
    libraryDependencies ++= Seq(zioInteropCats, sttpZio)
  ).dependsOn(root % "compile->compile;test->test")


scalacOptions ++= Seq("-Wunused:imports", "-Werror")

