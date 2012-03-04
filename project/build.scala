import sbt._

import Keys._
import AndroidKeys._
import scala.util.matching.Regex

object General {
  val apiLevel = SettingKey[Int]("api-level", "Target Android API level")
  val googleMapsJar = SettingKey[File]("google-maps-jar", "Google Maps JAR path")

  val settings = Defaults.defaultSettings ++ Seq (
    name := "GorTrans",
    version := "0.1",
    versionCode := 0,
    scalaVersion := "2.8.2",
    platformName in Android := "android-8",

    apiLevel in Android <<= (platformName in Android) { platform =>
      val platformNameRegex = """android-(\d+)""".r
      val platformNameRegex(apiLevel) = platform
      apiLevel.toInt
    }
  )

  val proguardSettings = Seq (
    useProguard in Android := true
  )

  lazy val fullAndroidSettings =
    General.settings ++
    AndroidProject.androidSettings ++
    TypedResources.settings ++
    proguardSettings ++
    AndroidManifestGenerator.settings ++
    AndroidMarketPublish.settings ++ Seq (
      keyalias in Android := "change-me",
      libraryDependencies += "org.scalatest" %% "scalatest" % "1.7.RC1" % "test",
      libraryDependencies += "org.ccil.cowan.tagsoup" % "tagsoup" % "1.2",

      googleMapsJar <<= (sdkPath in Android, apiLevel in Android) { (path, apiLevel) =>
          (path / "add-ons" / "addon-google_apis-google_inc_-%d".format(apiLevel)
          / "libs" / "maps.jar")
      },

      // Add Google Maps library.
      unmanagedJars in Compile <+= googleMapsJar map { jar => Attributed.blank(jar) },
      libraryJarPath in Android <+= googleMapsJar
    )
}

object AndroidBuild extends Build {
  lazy val main = Project (
    "GorTrans",
    file("."),
    settings = General.fullAndroidSettings
  )

  lazy val tests = Project (
    "tests",
    file("tests"),
    settings = General.settings ++
               AndroidTest.settings ++
               General.proguardSettings ++ Seq (
      name := "GorTransTests"
    )
  ) dependsOn main
}
