import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

lazy val root = project.in(file(".")).enablePlugins(PlayScala, AutomateHeaderPlugin)

name := "oss-request"

scalaVersion := "2.12.4"

resolvers ++= Seq(Resolver.mavenLocal, Resolver.jcenterRepo)

libraryDependencies ++= Seq(
  guice,
  ws,
  filters,
  jdbc,
  evolutions,

  "org.postgresql"         %  "postgresql"                         % "42.1.4",

  "io.getquill"            %% "quill-async-postgres"               % "2.3.1",

  "org.webjars"            %% "webjars-play"                       % "2.6.2",
  "org.webjars"            %  "salesforce-lightning-design-system" % "2.4.1",

  "org.scalatestplus.play" %% "scalatestplus-play"                 % "3.1.2" % "test"
)

pipelineStages := Seq(digest, gzip)

headerLicense := Some(HeaderLicense.Custom("Copyright (c) Salesforce.com, inc. 2017"))

headerMappings += FileType("html") -> HeaderCommentStyle.twirlStyleBlockComment

unmanagedSources.in(Compile, headerCreate) ++= sources.in(Compile, TwirlKeys.compileTemplates).value

javaOptions in Test := Seq("-Dlogger.resource=logback-test.xml")

// license report stuff

licenseConfigurations := Set("runtime")
