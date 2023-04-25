import sbtrelease.ReleaseStateTransformations.*
import Dependencies.*

val scala3Version = "3.3.0-RC4"

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
  organization := "uk.gov.nationalarchives.dp.client",
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
  scalaVersion := scala3Version,
  libraryDependencies ++= Seq(
    catsCore,
    scalaXml,
    sttpCore,
    sttpFs2,
    sttpUpickle,
    sttpZio,
    zioInteropCats,
    scalaTest % Test,
    wireMock % Test,
  )
) ++ releaseSettings

lazy val fs2Ref = LocalProject("fs2")
lazy val zioRef = LocalProject("zio")

lazy val root: Project = project
  .in(file("."))
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .aggregate(fs2Ref, zioRef)

lazy val fs2 = project
  .in(file("fs2"))
  .settings(commonSettings)
  .settings(
    libraryDependencies += sttpFs2
  ).dependsOn(root % "compile->compile;test->test")

lazy val zio = project
  .in(file("zio"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(zioInteropCats, sttpZio)
  ).dependsOn(root % "compile->compile;test->test")

scalacOptions ++= Seq("-Wunused:imports", "-Werror")
