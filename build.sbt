import sbtrelease.ReleaseStateTransformations.*
import Dependencies.*

lazy val scala3Version = "3.4.1"

ThisBuild / scalaVersion := scala3Version

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
      url("https://github.com/nationalarchives/dr2-preservica-client"),
      "git@github.com:nationalarchives/dr2-preservica-client.git"
    )
  ),
  developers := List(
    Developer(
      id = "tna-digital-archiving-jenkins",
      name = "TNA Digital Archiving",
      email = "digitalpreservation@nationalarchives.gov.uk",
      url = url("https://github.com/nationalarchives/dr2-preservica-client")
    )
  ),
  description := "A client to communicate with the Preservica API",
  licenses := List("MIT" -> new URL("https://choosealicense.com/licenses/mit/")),
  homepage := Some(url("https://github.com/nationalarchives/dr2-preservica-client"))
)

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  libraryDependencies ++= Seq(
    awsSecretsManager,
    catsCore,
    circeGeneric,
    dynamoFormatters,
    scalaCacheCore,
    scalaCacheCaffeine,
    log4Cats,
    scalaXml,
    sttpCore,
    sttpFs2,
    sttpCirce,
    mockito % Test,
    scalaTest % Test,
    wireMock % Test
  ),
  version := version.value,
  scalacOptions ++= Seq("-Wunused:imports", "-Werror", "-deprecation", "-Xmax-inlines", "50"),
  Test / fork := true,
  Test / envVars := Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_ACCESS_KEY" -> "test")
) ++ releaseSettings

lazy val fs2Ref = LocalProject("fs2")

lazy val root: Project = project
  .in(file("."))
  .settings(commonSettings)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    name := "preservica-client-root"
  )
  .aggregate(fs2Ref)

lazy val fs2 = project
  .in(file("fs2"))
  .settings(commonSettings)
  .settings(
    name := "preservica-client-fs2",
    libraryDependencies += sttpFs2
  )
  .dependsOn(root % "compile->compile;test->test")

lazy val docs = (project in file("site-docs"))
  .settings(
    name := "dr2-preservica-client",
    description := "Documentation for the Scala Preservica client",
    publish / skip := true
  )
  .enablePlugins(ParadoxSitePlugin, ScalaUnidocPlugin, SitePreviewPlugin)
  .settings(
    paradoxProperties += (
      "version" -> (ThisBuild / version).value.split("-").head
    ),
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    ScalaUnidoc / siteSubdirName := "api",
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, ScalaUnidoc / siteSubdirName)
  )
  .dependsOn(root % "compile->compile")
  .dependsOn(fs2 % "compile->compile")
