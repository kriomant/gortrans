package net.kriomant.gortrans

import com.actionbarsherlock.app.SherlockActivity
import android.os.Bundle
import com.actionbarsherlock.view.Window

trait SherlockAsyncTaskIndicator extends SherlockActivity { activity: SherlockActivity =>
	override def onCreate(savedInstanceState: Bundle) {
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)

		super.onCreate(savedInstanceState)
	}

	trait AsyncProcessIndicator[Progress, Result] extends AsyncTaskBridge[Progress, Result] {
		override def onPreExecute() {
			activity.setSupportProgressBarIndeterminateVisibility(true)
			super.onPreExecute()
		}

		override def onPostExecute(result: Result) {
			super.onPostExecute(result)
			activity.setSupportProgressBarIndeterminateVisibility(false)
		}

		override def onCancelled(result: Result) {
			super.onCancelled(result)
			activity.setSupportProgressBarIndeterminateVisibility(false)
		}

		override def onCancelled() {
			super.onCancelled()
			activity.setSupportProgressBarIndeterminateVisibility(false)
		}
	}
}

