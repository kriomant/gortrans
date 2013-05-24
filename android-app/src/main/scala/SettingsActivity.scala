package net.kriomant.gortrans

import com.actionbarsherlock.app.SherlockPreferenceActivity
import android.content.{Context, Intent}
import android.os.Bundle
import android.preference.CheckBoxPreference
import com.google.android.gms.common.GooglePlayServicesUtil

object SettingsActivity {
	def createIntent(caller: Context): Intent = {
		new Intent(caller, classOf[SettingsActivity])
	}

	final val KEY_USE_NEW_MAP = "use_new_map"
}

class SettingsActivity extends SherlockPreferenceActivity {
	import SettingsActivity._

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		addPreferencesFromResource(R.xml.preferences)

		val useNewMapPref = findPreference(KEY_USE_NEW_MAP).asInstanceOf[CheckBoxPreference]
		useNewMapPref.setEnabled(
			GooglePlayServicesUtil.isUserRecoverableError(GooglePlayServicesUtil.isGooglePlayServicesAvailable(this))
		)
	}
}

