package net.kriomant.gortrans

import android.app.Application
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy

class CustomApplication extends Application {
	val dataManager = new DataManager(this)

	if (android.os.Build.VERSION.SDK_INT >= 9) {
		// Turn strict mode off.
		StrictMode.setThreadPolicy((new ThreadPolicy.Builder).build())
	}
}