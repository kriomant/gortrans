import sbt._

import Keys._
import AndroidKeys._
import scala.util.matching.Regex
import xml.XML

object General {
  val apiLevel = SettingKey[Int]("api-level", "Target Android API level")
  val googleMapsJar = SettingKey[File]("google-maps-jar", "Google Maps JAR path")
	val androidSupportJar = SettingKey[File]("android-support-jar", "Google Support Library JAR path")

	// Generating custom resources file containing some project settings.
	val customResourcesPath = TaskKey[File]("custom-resources-path", "Path to custom resources file")
	val customResources = SettingKey[Seq[(String, String)]]("custom-resources", "Custom resources")
	val generateCustomResources = TaskKey[File]("generate-custom-resources",
		"Generate resources file based on build settings"
	)
	def generateCustomResourcesTask(path: File, res: Seq[(String, String)], streams: TaskStreams) = {
			val xml =
				<resources>
	{res map (r => <string name={r._1}>{r._2}</string>)}
</resources>
			XML.save(path.toString, xml)
			streams.log.info("Generated %s" format path.toString)
			path
	}

  val settings = Defaults.defaultSettings ++ Seq (
    name := "GorTrans",
    version := "0.1.3",
    versionCode := 14,
    scalaVersion := "2.8.2",
    apiLevel := 15,
    platformName in Android <<= (apiLevel in Android) { _ formatted "android-%d" }
  )

  val proguardSettings = Seq (
    useProguard in Android := true,
	  proguardOption in Android ~= { _ + " -dontnote scala.** " }
  )

  lazy val fullAndroidSettings =
    General.settings ++
    AndroidProject.androidSettings ++
    TypedResources.settings ++
    proguardSettings ++
    AndroidManifestGenerator.settings ++
    AndroidMarketPublish.settings ++ Seq (
      keyalias in Android := "change-me",
      libraryDependencies += "org.ccil.cowan.tagsoup" % "tagsoup" % "1.2",

			resolvers += "ActionBarSherlock" at  "http://r.jakewharton.com/maven/release/",
			libraryDependencies += "com.actionbarsherlock" % "library" % "4.0.2" artifacts(Artifact("library", "apklib", "apklib")),
	    libraryDependencies += "com.actionbarsherlock" % "plugin-maps" % "4.0.0",
      // Prevent ProGuard from stripping ActionBarSherlock implementation classes which are used through reflection.
      proguardOption in Android ~= { _ + " -keep class android.support.v4.app.** { *; } -keep class android.support.v4.content.Loader* -keep interface android.support.v4.app.** { *; } -keep class com.actionbarsherlock.** { *; } -keep interface com.actionbarsherlock.** { *; } -keepattributes *Annotation* " },

      googleMapsJar <<= (sdkPath in Android, apiLevel in Android) { (path, apiLevel) =>
          (path / "add-ons" / "addon-google_apis-google-%d".format(apiLevel)
          / "libs" / "maps.jar")
      },

      // Add Google Maps library.
      unmanagedJars in Compile <+= googleMapsJar map { jar => Attributed.blank(jar) },
      libraryJarPath in Android <+= googleMapsJar,

      customResourcesPath in Android <<= (mainResPath in Android) map { _ / "values" / "generated.xml" },
      customResources in Android := Seq(),
      generateCustomResources in (Android, packageDebug) <<= (customResourcesPath in Android, customResources in (Android, packageDebug), streams) map generateCustomResourcesTask,
	    generateCustomResources in (Android, packageRelease) <<= (customResourcesPath in Android, customResources in (Android, packageRelease), streams) map generateCustomResourcesTask,

      packageDebug in Android <<= (packageDebug in Android) dependsOn (generateCustomResources in (Android, packageDebug)),
	    packageRelease in Android <<= (packageRelease in Android) dependsOn (generateCustomResources in (Android, packageRelease))
    )
}

object AndroidBuild extends Build {
  lazy val root = Project (
    "root",
    file(".")
  ) aggregate (core, androidApp, androidTests, explorer, checker)

	lazy val core = Project(
	  "core",
	  file("core"),
	  settings = Defaults.defaultSettings ++ Seq(
		  scalaVersion := "2.8.2",
		  scalacOptions += "-deprecation",

	    libraryDependencies ++= Seq(
		    "org.json" % "json" % "20090211",
       	"org.ccil.cowan.tagsoup" % "tagsoup" % "1.2",
		    "org.scalatest" %% "scalatest" % "1.7.1" % "test"
	    )
	  )
	)

  lazy val androidApp = Project(
    "android-app",
    file("android-app"),
    settings = General.fullAndroidSettings ++ Seq(
	    scalacOptions += "-deprecation"
    )
  ) dependsOn (core)

  lazy val androidTests = Project (
    "android-tests",
    file("android-app/tests"),
    settings = General.settings ++
               AndroidTest.settings ++
               General.proguardSettings ++ Seq (
      name := "GorTransTests"
    )
  ) dependsOn androidApp

	lazy val explorer = Project(
	  "explorer",
	  file("explorer"),
	  settings = Defaults.defaultSettings ++ Seq(
		  scalaVersion := "2.8.2",
	    scalacOptions += "-deprecation",

		  libraryDependencies ++= Seq(
			  "org.json" % "json" % "20090211"
		  ),

	    unmanagedJars in Compile ++= Seq(
	      Attributed.blank(file("/usr/share/java/swt.jar"))
	    ),

	    fork in run := true,
	    javaOptions in run += "-Djava.library.path=/usr/lib/jni"
	  )
	) dependsOn (core)

	lazy val checker = Project(
		"checker",
		file("checker"),
		settings = Defaults.defaultSettings ++ Seq(
			scalaVersion := "2.8.2",
			scalacOptions += "-deprecation",

			libraryDependencies ++= Seq(
				"org.json" % "json" % "20090211",
				"ch.qos.logback" % "logback-classic" % "1.0.5"
			)
		)
	) dependsOn (core)
}
