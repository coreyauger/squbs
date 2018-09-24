import Versions._

name := "squbs-zkcluster"

organization := "im.conversant"

publishTo := Some("NextWave Repo" at "https://repository.conversant.im/artifactory/nxtwv-maven/")

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaV,
  "com.typesafe.akka" %% "akka-remote" % akkaV,
  "com.typesafe.akka" %% "akka-slf4j" % akkaV,
  "org.apache.curator" % "curator-recipes" % curatorV,
  "org.apache.curator" % "curator-framework" % curatorV exclude("org.jboss.netty", "netty"),
  "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingV,
  "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
  "org.scalatest" %% "scalatest" % scalatestV % "test->*",
  "org.mockito" % "mockito-core" % "2.15.0" % "test",
  "org.apache.curator" % "curator-test" % curatorV % "test",
  "ch.qos.logback" % "logback-classic" % logbackInTestV % "test"
)

(testOptions in Test) += Tests.Argument(TestFrameworks.ScalaTest, "-h", "report/squbs-zkcluster")

parallelExecution := false

cleanFiles += baseDirectory.value / "zookeeper"

updateOptions := updateOptions.value.withCachedResolution(true)
