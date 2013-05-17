package net.kriomant.gortrans

import com.actionbarsherlock.app.SherlockPreferenceActivity
import android.content.{Context, Intent}
import android.os.Bundle

object SettingsActivity {
	def createIntent(caller: Context): Intent = {
		new Intent(caller, classOf[SettingsActivity])
	}

	final val KEY_USE_NEW_MAP = "use_new_map"
}

class SettingsActivity extends SherlockPreferenceActivity {
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		addPreferencesFromResource(R.xml.preferences)
	}
}

