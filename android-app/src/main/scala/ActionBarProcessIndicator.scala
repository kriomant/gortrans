package net.kriomant.gortrans

import android.widget.Toast
import com.actionbarsherlock.app.SherlockActivity

class ActionBarProcessIndicator(activity: SherlockActivity) extends DataManager.ProcessIndicator {
	def startFetch() {
		Toast.makeText(activity, R.string.background_update_started, Toast.LENGTH_SHORT).show()
		activity.setSupportProgressBarIndeterminateVisibility(true)
		activity.setProgressBarIndeterminateVisibility(true)
	}

	def stopFetch() {
		activity.setSupportProgressBarIndeterminateVisibility(true)
		activity.setProgressBarIndeterminateVisibility(true)
	}

	def onSuccess() {
		Toast.makeText(activity, R.string.background_update_stopped, Toast.LENGTH_SHORT).show()
	}

	def onError() {
		Toast.makeText(activity, R.string.background_update_error, Toast.LENGTH_SHORT).show()
	}
}
