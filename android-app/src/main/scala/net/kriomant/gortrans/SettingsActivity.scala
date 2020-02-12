package net.kriomant.gortrans

import android.app.ActivityManager
import android.content.{Context, Intent}
import android.os.Bundle
import android.preference.CheckBoxPreference
import com.actionbarsherlock.app.SherlockPreferenceActivity
import com.actionbarsherlock.view.MenuItem
import com.google.android.gms.common.{ConnectionResult, GooglePlayServicesUtil}

object SettingsActivity {
  final val KEY_USE_NEW_MAP = "use_new_map"

  def createIntent(caller: Context): Intent = {
    new Intent(caller, classOf[SettingsActivity])
  }

  def isOldGoogleMapAvailable(context: Context): Boolean = {
    context.getPackageManager.getSystemSharedLibraryNames contains "com.google.android.maps"
  }

  def isNewMapAvailable(context: Context): Boolean = {
    val openGlEs2Available = {
      val manager = context.getSystemService(Context.ACTIVITY_SERVICE).asInstanceOf[ActivityManager]
      manager.getDeviceConfigurationInfo.reqGlEsVersion >= 0x20000
    }

    def googlePlayServicesAvailable = {
      val status = GooglePlayServicesUtil.isGooglePlayServicesAvailable(context)
      status == ConnectionResult.SUCCESS || GooglePlayServicesUtil.isUserRecoverableError(status)
    }

    openGlEs2Available && googlePlayServicesAvailable
  }
}

class SettingsActivity extends SherlockPreferenceActivity with BaseActivity {

  import SettingsActivity._

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    addPreferencesFromResource(R.xml.preferences)

    val useNewMapPref = findPreference(KEY_USE_NEW_MAP).asInstanceOf[CheckBoxPreference]
    useNewMapPref.setEnabled(isNewMapAvailable(this))

    val actionBar = getSupportActionBar
    actionBar.setDisplayShowHomeEnabled(true)
    actionBar.setDisplayHomeAsUpEnabled(true)
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
    case android.R.id.home =>
      finish()
      true

    case _ => super.onOptionsItemSelected(item)
  }
}

