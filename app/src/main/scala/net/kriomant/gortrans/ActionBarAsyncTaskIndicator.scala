package net.kriomant.gortrans

import android.os.Bundle
import android.support.v7.app.ActionBarActivity
import android.view.Window

trait ActionBarAsyncTaskIndicator extends ActionBarActivity {
  activity: ActionBarActivity =>
  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    supportRequestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
  }

  trait AsyncProcessIndicator[Progress, Result] extends AsyncTaskBridge[Progress, Result] {
    override def onPreExecute() {
      activity.setSupportProgressBarIndeterminateVisibility(true)
    }

    override def onPostExecute(result: Result) {
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

