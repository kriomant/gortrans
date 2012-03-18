package net.kriomant.gortrans

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.location.{LocationManager, Location, LocationListener}
import android.util.Log

trait TrackLocation extends Activity {
	private[this] final val MINIMUM_LOCATION_UPDATE_INTERVAL = 0 /* ms */
	private[this] final val MINIMUM_LOCATION_DISTANCE_CHANGE = 0 /* m */

	def onLocationUpdated(location: Location)

	var locationManager: LocationManager = null
	var gpsEnabled = false
	var currentLocation: Location = null

	object locationListener extends LocationListener {
		def onStatusChanged(provider: String, status: Int, extras: Bundle) {}

		def onProviderEnabled(provider: String) {
			val lastKnownLocation = locationManager.getLastKnownLocation(provider)
			if (lastKnownLocation != null)
				onLocationUpdated(lastKnownLocation)
		}

		def onProviderDisabled(provider: String) {
			onLocationUpdated(null)
		}

		def onLocationChanged(location: Location) {
			onLocationUpdated(location)
		}
	}

	def setGpsEnabled(enabled: Boolean) {
		if (enabled != gpsEnabled) {
			if (enabled)
				enableGps()
			else
				disableGps()
		}
	}
	
	private def enableGps() {
		Log.d("TrackLocation", "Enable GPS")
		locationManager.requestLocationUpdates(
			LocationManager.GPS_PROVIDER,
			MINIMUM_LOCATION_UPDATE_INTERVAL, MINIMUM_LOCATION_DISTANCE_CHANGE,
			locationListener
		)

		gpsEnabled = true

		val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
		if (lastKnownLocation != null) {
			currentLocation = lastKnownLocation
			onLocationUpdated(currentLocation)
		}
	}

	private def disableGps() {
		Log.d("TrackLocation", "Disable GPS")
		locationManager.removeUpdates(locationListener)

		gpsEnabled = false

		currentLocation = null
		onLocationUpdated(null)
	}

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		locationManager = getSystemService(Context.LOCATION_SERVICE).asInstanceOf[LocationManager]
		
		if (gpsEnabled)
			enableGps()
	}

	override def onResume() {
		super.onResume()

		if (gpsEnabled)
			enableGps()
	}

	override def onPause() {
		if (gpsEnabled)
			disableGps()

		super.onPause()
	}
}