import com.typesafe.sbt.license.{DepModuleInfo, LicenseInfo}
import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

lazy val root = project.in(file(".")).enablePlugins(PlayScala, AutomateHeaderPlugin)

name := "oss-request"

scalaVersion := "2.12.6"

resolvers ++= Seq(Resolver.mavenLocal, Resolver.jcenterRepo)

libraryDependencies ++= Seq(
  guice,
  ws,
  filters,
  jdbc,
  evolutions,
  ehcache,

  "org.slf4j"              %  "log4j-over-slf4j"                   % "1.7.25",
  "io.airbrake"            %  "javabrake"                          % "0.1.6",

  "org.postgresql"         %  "postgresql"                         % "42.1.4",

  "io.getquill"            %% "quill-async-postgres"               % "2.5.4",

  "org.eclipse.jgit"       %  "org.eclipse.jgit"                   % "4.10.0.201712302008-r",

  "com.onelogin"           %  "java-saml-core"                     % "2.2.0",

  "javax.cache"            %  "cache-api"                          % "1.1.0",

  "org.webjars"            %% "webjars-play"                       % "2.6.3",
  "org.webjars"            %  "salesforce-lightning-design-system" % "2.4.1",
  "org.webjars"            %  "handlebars"                         % "4.0.11-1",
  "org.webjars"            %  "alpaca"                             % "1.5.23",
  "org.webjars.npm"        %  "jsoneditor"                         % "5.17.1",
  "org.planet42"           %% "laika-core"                         % "0.7.5",

  "org.scalatestplus.play" %% "scalatestplus-play"                 % "3.1.2" % "test"
)

pipelineStages := Seq(digest, gzip)

WebKeys.webJars in Assets := Seq.empty[File]

headerLicense := Some(HeaderLicense.Custom("Copyright (c) Salesforce.com, inc. 2018"))

headerMappings += FileType("html") -> HeaderCommentStyle.twirlStyleBlockComment

unmanagedSources.in(Compile, headerCreate) ++= sources.in(Compile, TwirlKeys.compileTemplates).value

javaOptions in Test := Seq("-Dlogger.resource=logback-test.xml")

// license report stuff

licenseConfigurations := Set("runtime")

licenseOverrides := {
  case DepModuleInfo("ch.qos.logback", _, _) =>
    LicenseInfo(LicenseCategory.EPL, "Eclipse Public License - v 1.0", "http://www.eclipse.org/legal/epl-v10.html")
  case DepModuleInfo("javax.transaction", "jta", _) =>
    LicenseInfo(LicenseCategory.CDDL, "CDDL + GPLv2 with classpath exception", "https://opensource.org/licenses/CDDL-1.0")
  case DepModuleInfo("tyrex", _, _) =>
    LicenseInfo(LicenseCategory.BSD, "BSD-like", "http://tyrex.sourceforge.net/license.html")
}
