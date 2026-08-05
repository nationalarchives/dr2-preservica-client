import sbtrelease.ReleaseStateTransformations.*
import Dependencies.*

lazy val scala3Version = "3.8.4"
lazy val codeArtifactDomain = "dr2"
lazy val codeArtifactRepository = "maven"
lazy val codeArtifactOwner = sys.env.getOrElse("MANAGEMENT_ACCOUNT", "132603323695")
lazy val codeArtifactRegion = sys.env.getOrElse("AWS_REGION", "eu-west-2")
lazy val codeArtifactHost =
  s"$codeArtifactDomain-$codeArtifactOwner.d.codeartifact.$codeArtifactRegion.amazonaws.com"
lazy val codeArtifactRepositoryUrl =
  s"https://$codeArtifactHost/maven/$codeArtifactRepository/"
lazy val codeArtifactPublishUrl =
  sys.env.getOrElse("CODEARTIFACT_REPOSITORY_ENDPOINT", codeArtifactRepositoryUrl)

ThisBuild / scalaVersion := scala3Version

lazy val publishingSettings = Seq(
  useGpgPinentry := true,
  publishTo := Some("codeartifact" at codeArtifactPublishUrl),
  credentials ++= sys.env
    .get("CODEARTIFACT_AUTH_TOKEN")
    .map(token => Credentials("CodeArtifact", codeArtifactHost, "aws", token))
    .toSeq,
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
    setNextVersion,
    commitNextVersion,
    pushChanges
  ),
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
      id = "tna-da-bot",
      name = "TNA Digital Archiving",
      email = "181243999+tna-da-bot@users.noreply.github.com",
      url = url("https://github.com/nationalarchives/dr2-preservica-client")
    )
  ),
  description := "A client to communicate with the Preservica API",
  licenses := List("MIT" -> new URL("https://choosealicense.com/licenses/mit/")),
  homepage := Some(url("https://github.com/nationalarchives/dr2-preservica-client"))
)

lazy val nettyOverrides = Seq(
  nettyBuffer,
  nettyCodecHttp2,
  nettyCodecHttp,
  nettyCodec,
  nettyCommon,
  nettyHandler,
  nettyResolver,
  nettyTransportClasses,
  nettyTransport
)

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  libraryDependencies ++= Seq(
    secretsManagerClient,
    catsCore,
    catsRetry,
    fs2Core,
    scalaCacheCore,
    scalaCacheCaffeine,
    log4Cats,
    scalaXml,
    sttpCore,
    sttpFs2,
    sttpSlf4j,
    sttpCirce,
    mockito % Test,
    scalaTest % Test,
    wireMock % Test
  ),
  version := version.value,
  scalacOptions ++= Seq("-Wunused:imports", "-Werror", "-deprecation", "-Xmax-inlines", "50"),
  Test / fork := true,
  Test / envVars := Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_ACCESS_KEY" -> "test")
) ++ publishingSettings

lazy val fs2Ref = LocalProject("fs2")

lazy val root: Project = project
  .in(file("."))
  .settings(commonSettings)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    name := "preservica-client-root",
    dependencyOverrides ++= nettyOverrides
  )
  .aggregate(fs2Ref)

lazy val fs2 = project
  .in(file("fs2"))
  .settings(commonSettings)
  .settings(
    name := "preservica-client-fs2",
    libraryDependencies ++= Seq(sttpFs2),
    dependencyOverrides ++= nettyOverrides
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
    dependencyOverrides ++= nettyOverrides,
    paradoxProperties += (
      "version" -> (ThisBuild / version).value.split("-").head
    ),
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    ScalaUnidoc / siteSubdirName := "api",
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, ScalaUnidoc / siteSubdirName)
  )
  .dependsOn(root % "compile->compile")
  .dependsOn(fs2 % "compile->compile")
