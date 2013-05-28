package net.kriomant.gortrans

import com.actionbarsherlock.app.SherlockPreferenceActivity
import android.content.{Context, Intent}
import android.os.Bundle
import android.preference.CheckBoxPreference
import com.google.android.gms.common.{ConnectionResult, GooglePlayServicesUtil}

object SettingsActivity {
	def createIntent(caller: Context): Intent = {
		new Intent(caller, classOf[SettingsActivity])
	}

	final val KEY_USE_NEW_MAP = "use_new_map"

	def isNewMapAvailable(context: Context): Boolean = {
		val status = GooglePlayServicesUtil.isGooglePlayServicesAvailable(context)
		status == ConnectionResult.SUCCESS || GooglePlayServicesUtil.isUserRecoverableError(status)
	}
}

class SettingsActivity extends SherlockPreferenceActivity {
	import SettingsActivity._

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		addPreferencesFromResource(R.xml.preferences)

		val useNewMapPref = findPreference(KEY_USE_NEW_MAP).asInstanceOf[CheckBoxPreference]
		useNewMapPref.setEnabled(isNewMapAvailable(this))
	}
}

