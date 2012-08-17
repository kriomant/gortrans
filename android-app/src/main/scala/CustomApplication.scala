package net.kriomant.gortrans

import android.app.Application
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy

class CustomApplication extends Application {
	var database: Database = null
	var dataManager: DataManager = null

	if (android.os.Build.VERSION.SDK_INT >= 9) {
		// Turn strict mode off.
		StrictMode.setThreadPolicy((new ThreadPolicy.Builder).build())
	}


	override def onCreate() {
		database = Database.getWritable(this)
		dataManager = new DataManager(this, database)
	}

	override def onTerminate() {
		database.close()
	}
}