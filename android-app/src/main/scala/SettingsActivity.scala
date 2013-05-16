package net.kriomant.gortrans

import com.actionbarsherlock.app.SherlockPreferenceActivity
import android.content.{Context, Intent}

object SettingsActivity {
	def createIntent(caller: Context): Intent = {
		new Intent(caller, classOf[SettingsActivity])
	}
}

class SettingsActivity extends SherlockPreferenceActivity {
}