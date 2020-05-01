import AndroidKeys._
import sbt.Keys._
import sbt.{Def, _}

import scala.xml.XML

object General {
  lazy val fullAndroidSettings: Seq[Def.Setting[_]] =
    General.settings ++
      General.scalaSettings ++
      AndroidProject.androidSettings ++
      TypedResources.settings ++
      proguardSettings ++
      AndroidManifestGenerator.settings ++
      AndroidMarketPublish.settings ++ Seq(
      keyalias in Android := "change-me",
      libraryDependencies += "org.ccil.cowan.tagsoup" % "tagsoup" % "1.2",

      libraryDependencies += "com.actionbarsherlock" % "actionbarsherlock" % "4.3.1" artifacts Artifact("actionbarsherlock", "apklib", "apklib") exclude("com.google.android", "support-v4"),
      libraryDependencies += "com.actionbarsherlock" % "plugin-maps" % "4.2.0" from "https://github.com/downloads/JakeWharton/ActionBarSherlock-Plugin-Maps/actionbarsherlock-plugin-maps-4.2.0.jar",
      // Prevent ProGuard from stripping ActionBarSherlock (and support library) implementation classes which are used through reflection.
      proguardOption in Android ~= {
        _ + " -keep class android.support.v4.** { *; } -keep interface android.support.v4.** { *; } "
      },
      proguardOption in Android ~= {
        _ + " -keep class com.actionbarsherlock.** { *; } -keep interface com.actionbarsherlock.** { *; } -keepattributes *Annotation* "
      },
      proguardOption in Android ~= {
        _ + " -keep class net.kriomant.gortrans.compatibility.* { *; } "
      },
      // and some more libraries
      proguardOption in Android ~= {
        _ + " -keep class org.osmdroid.** { *; } -keep interface org.osmdroid.** { *; } "
      },
      proguardOption in Android ~= {
        _ + " -keep class org.metalev.** { *; } -keep interface org.metalev.** { *; } "
      },
      proguardOption in Android ~= {
        _ + " -keep class com.google.android.gms.** { *; } -keep interface com.google.android.gms.** { *; } "
      },

      // Workaround for https://issues.scala-lang.org/browse/SI-5397. Doesn't work for me, unfortunately.
      proguardOption in Android ~= {
        _ + " -keep class scala.collection.immutable.StringLike { public protected *; } -keep class scala.collection.SeqLike { public java.lang.String toString(); } "
      },

      // Add Google Maps library.
      googleMapsJar <<= (sdkPath in Android, apiLevel in Android) { (path, apiLevel) =>
        (path / "add-ons" / "addon-google_apis-google-%d".format(apiLevel)
          / "libs" / "maps.jar")
      },
      unmanagedJars in Compile <+= googleMapsJar map { jar => Attributed.blank(jar) },
      libraryJarPath in Android <+= googleMapsJar,

      // Add Android support library.
      androidSupportJar <<= (sdkPath in Android) { path =>
        path / "extras" / "android" / "support" / "v13" / "android-support-v13.jar"
      },
      unmanagedJars in Compile <+= androidSupportJar map { jar => Attributed.blank(jar) },

      libraryDependencies += "org.osmdroid" % "osmdroid-android" % "3.0.8",
      libraryDependencies += "org.slf4j" % "slf4j-android" % "1.6.1-RC1",

      // Use Google Play services SDK.
      googlePlayServices <<= (sdkPath in Android) { path =>
        path / "extras" / "google" / "google_play_services_froyo" / "libproject" / "google-play-services_lib"
      },
      extractApkLibDependencies in Android <+= googlePlayServices map { path =>
        LibraryProject(
          pkgName = "com.google.android.gms",
          manifest = path / "AndroidManifest.xml",
          sources = Set(),
          resDir = Some(path / "res"),
          assetsDir = None
        )
      },
      unmanagedJars in Compile <+= googlePlayServices map { path => Attributed.blank(path / "libs" / "google-play-services.jar") },
      proguardOption in Android ~= {
        _ + " -keep class * extends java.util.ListResourceBundle { protected java.lang.Object[][] getContents(); } "
      },
      proguardOption in Android ~= {
        _ + " -keep class com.google.android.gms.maps.SupportMapFragment { *; } "
      },

      customResourcesPath in Android <<= (mainResPath in Android) map {
        _ / "values" / "generated.xml"
      },
      customResources in Android := Seq(),
      generateCustomResources in(Android, packageDebug) <<= (customResourcesPath in Android, customResources in(Android, packageDebug), streams) map generateCustomResourcesTask,
      generateCustomResources in(Android, packageRelease) <<= (customResourcesPath in Android, customResources in(Android, packageRelease), streams) map generateCustomResourcesTask,

      // Generate BuildConfig.java for library projects.
      aaptGenerate in Android <<= (aaptGenerate in Android, manifestPackage in Android, extractApkLibDependencies in Android, managedJavaPath in Android) map {
        (sources, package_, apklibs, javaPath) =>

          def createBuildConfig(packageName: String) = {
            var path = javaPath
            packageName.split('.').foreach {
              path /= _
            }
            path.mkdirs()
            val buildConfig = path / "BuildConfig.java"
            IO.write(buildConfig,
              """
						package %s;
						public final class BuildConfig {
							public static final boolean DEBUG = %s;
						}""".format(packageName, false))
            buildConfig
          }

          sources ++ Seq(createBuildConfig(package_)) ++ apklibs.map(lib => createBuildConfig(lib.pkgName))
      },
      packageDebug in Android <<= (packageDebug in Android) dependsOn (generateCustomResources in(Android, packageDebug)),
      packageRelease in Android <<= (packageRelease in Android) dependsOn (generateCustomResources in(Android, packageRelease))
    )
  val apiLevel = SettingKey[Int]("api-level", "Target Android API level")
  val googleMapsJar = SettingKey[File]("google-maps-jar", "Google Maps JAR path")
  val googlePlayServices = SettingKey[File]("google-play-services-sdk", "Google Play services SDK")
  val androidSupportJar = SettingKey[File]("android-support-jar", "Google Support Library JAR path")
  // Generating custom resources file containing some project settings.
  val customResourcesPath = TaskKey[File]("custom-resources-path", "Path to custom resources file")
  val customResources = SettingKey[Seq[(String, String)]]("custom-resources", "Custom resources")
  val generateCustomResources = TaskKey[File]("generate-custom-resources",
    "Generate resources file based on build settings"
  )
  val settings: Seq[Def.Setting[_]] = Defaults.defaultSettings ++ Seq(
    name := "GorTrans",
    version := "1.0.14",
    versionCode := 41,
    apiLevel := 19,
    platformName in Android <<= (apiLevel in Android) {
      _ formatted "android-%d"
    }
  )

  val scalaSettings = Seq(
    scalaVersion := "2.10.2"
  )

  val proguardSettings = Seq(
    useProguard in Android := true,
    proguardOption in Android ~= {
      _ + " -dontnote scala.** "
    }
  )

  def generateCustomResourcesTask(path: File, res: Seq[(String, String)], streams: TaskStreams): File = {
    val xml =
      <resources>
        {res map (r => <string name={r._1}>
        {r._2}
      </string>)}
      </resources>
    XML.save(path.toString, xml)
    streams.log.info("Generated %s" format path.toString)
    path
  }
}

object AndroidBuild extends Build {
  lazy val root = Project(
    "root",
    file(".")
  ) aggregate(core, androidApp, explorer, checker)

  lazy val core = Project(
    "core",
    file("core"),
    settings = Defaults.defaultSettings ++ General.scalaSettings ++ Seq(
      libraryDependencies ++= Seq(
        "org.json" % "json" % "20090211",
        "org.ccil.cowan.tagsoup" % "tagsoup" % "1.2",
        "org.scalatest" %% "scalatest" % "1.9.1" % "test"
      )
    )
  )

  lazy val androidApp = Project(
    "android-app",
    file("android-app"),
    settings = General.fullAndroidSettings ++ Seq(
      scalacOptions += "-deprecation"
    )
  ) dependsOn core

  lazy val androidTests = Project(
    "android-tests",
    file("android-app/tests"),
    settings = General.settings ++
      General.scalaSettings ++
      AndroidTest.settings ++
      General.proguardSettings ++ Seq(
      name := "GorTransTests"
    )
  ) dependsOn androidApp

  lazy val explorer = Project(
    "explorer",
    file("explorer"),
    settings = Defaults.defaultSettings ++ General.scalaSettings ++ Seq(
      resolvers += "swt-repo" at "http://maven-eclipse.github.io/maven",
      libraryDependencies += {
        val os = (sys.props("os.name"), sys.props("os.arch")) match {
          case ("Linux", _) => "gtk.linux.x86"
          case ("Mac OS X", "amd64" | "x86_64") => "cocoa.macosx.x86_64"
          case ("Mac OS X", _) => "cocoa.macosx.x86"
          case (os, "amd64") if os.startsWith("Windows") => "win32.win32.x86_64"
          case (os, _) if os.startsWith("Windows") => "win32.win32.x86"
          case (os, arch) => sys.error("Cannot obtain lib for OS '" + os + "' and architecture '" + arch + "'")
        }
        val artifact = "org.eclipse.swt." + os
        "org.eclipse.swt" % artifact % "4.6.1"
      },

      libraryDependencies ++= Seq(
        "org.json" % "json" % "20090211"
      ),

      fork in run := true
    )
  ) dependsOn core

  lazy val checker = Project(
    "checker",
    file("checker"),
    settings = Defaults.defaultSettings ++ General.scalaSettings ++ Seq(
      libraryDependencies ++= Seq(
        "org.json" % "json" % "20090211",
        "ch.qos.logback" % "logback-classic" % "1.0.5"
      )
    )
  ) dependsOn core
}
