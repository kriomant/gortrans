package net.kriomant.gortrans

import java.io.File

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.util.Log
import com.google.analytics.tracking.android.EasyTracker

class CustomApplication extends Application {
  private[this] final val TAG = getClass.getName

  var database: Database = _
  var dataManager: DataManager = _

  if (android.os.Build.VERSION.SDK_INT >= 9) {
    // Turn strict mode off.
    StrictMode.setThreadPolicy((new ThreadPolicy.Builder).build())
  }


  override def onCreate() {
    upgrade()

    database = Database.getWritable(this)
    dataManager = new DataManager(this, database)

    Service.init(this)

    initializeGoogleAnalytics()
  }

  private def initializeGoogleAnalytics() {
    EasyTracker.getInstance().setContext(this)
  }

  override def onTerminate() {
    database.close()
  }

  /** Perform upgrade actions if needed. */
  private def upgrade() {
    val packageInfo = getPackageManager.getPackageInfo(getPackageName, 0)
    val prefs = getSharedPreferences("upgrade", Context.MODE_PRIVATE)

    val lastStartedVersionCode = prefs.getInt("versionCode", 0)

    if (lastStartedVersionCode < packageInfo.versionCode) {
      Log.i(TAG, "Last started version code: %d, current version code: %d" format(
        lastStartedVersionCode, packageInfo.versionCode
      ))

      val actions = upgradeActions.filter(_._1 > lastStartedVersionCode)
      Log.d(TAG, "There are %d upgrade actions to run" format actions.size)

      for ((versionCode, action) <- actions) {
        Log.i(TAG, "Perform upgrade action for version code %d" format versionCode)
        action()
        Log.i(TAG, "Upgrade action has finished successfuly, remember version code %d" format versionCode)

        val editor = prefs.edit()
        editor.putInt("versionCode", versionCode)
        if (android.os.Build.VERSION.SDK_INT >= 9)
        // apply() is faster than commit(), but it is instroduced in API 9
          editor.apply()
        else
        // fallback to slow commit()
          editor.commit()
      }
    }
  }

  final val upgradeActions = Seq[(Int, () => Unit)](
    20 -> clearCache _
  )

  private[this] def clearCache() {
    Log.i(TAG, "Clear cache")

    def clearDir(dir: File) {
      for (file <- dir.listFiles()) {
        if (file.isDirectory)
          clearDir(file)
        file.delete()
      }
    }

    try {
      clearDir(getCacheDir)
    } catch {
      case error: Exception => Log.e(TAG, "Failed to clear cache", error)
    }
  }
}