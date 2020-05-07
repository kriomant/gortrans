package net.kriomant.gortrans

import android.app.Activity
import com.google.analytics.tracking.android.EasyTracker

protected trait BaseActivity extends Activity {
  override def onStart() {
    super.onStart()
    EasyTracker.getInstance().activityStart(this)
  }

  override def onStop() {
    EasyTracker.getInstance().activityStop(this)
    super.onStop()
  }
}
