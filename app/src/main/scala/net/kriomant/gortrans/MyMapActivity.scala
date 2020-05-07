package net.kriomant.gortrans

import android.os.Bundle
import com.actionbarsherlock.app.SherlockMapActivity
import com.google.analytics.tracking.android.EasyTracker
import com.google.android.gms.common.GooglePlayServicesUtil
import com.google.android.maps.MapView

class MyMapActivity extends SherlockMapActivity {
  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_my_map)
    val mapView = findViewById(R.id.my_map_view).asInstanceOf[MapView]
    mapView.setBuiltInZoomControls(true)
    EasyTracker.getInstance()
    GooglePlayServicesUtil.isGooglePlayServicesAvailable(this)
  }

  override def isRouteDisplayed = false
}
