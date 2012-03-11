package net.kriomant.gortrans

import android.app.Application

class CustomApplication extends Application {
	val dataManager = new DataManager(this)
}