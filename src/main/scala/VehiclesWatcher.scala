package net.kriomant.gortrans

import android.os.Handler
import net.kriomant.gortrans.parsing.VehicleInfo
import net.kriomant.gortrans.Client.RouteInfoRequest
import net.kriomant.gortrans.core.{VehicleType, DirectionsEx}
import android.app.Activity
import android.widget.Toast

trait VehiclesWatcher { this: Activity =>

	val handler: Handler
	val client = new Client

	def getVehiclesToTrack: (VehicleType.Value, String, String) // type, routeId, routeName
	def onVehiclesLocationUpdateStarted()
	def onVehiclesLocationUpdateCancelled()
	def onVehiclesLocationUpdated(vehicles: Seq[VehicleInfo])

	private[this] final val VEHICLES_LOCATION_UPDATE_PERIOD = 20000 /* ms */

	private final val updateVehiclesLocationRunnable = new Runnable {
		def run() {
			updateVehiclesLocation()
			handler.postDelayed(this, VEHICLES_LOCATION_UPDATE_PERIOD)
		}
	}

	def updateVehiclesLocation() {
		val task = new TrackVehiclesTask
		task.execute(Unit)
	}

	def startUpdatingVehiclesLocation() {
		handler.post(updateVehiclesLocationRunnable)
		Toast.makeText(this, R.string.vehicles_tracking_turned_on, Toast.LENGTH_SHORT).show()
	}

	def stopUpdatingVehiclesLocation() {
		handler.removeCallbacks(updateVehiclesLocationRunnable)
		Toast.makeText(this, R.string.vehicles_tracking_turned_off, Toast.LENGTH_SHORT).show()

		onVehiclesLocationUpdateCancelled()
	}

	class TrackVehiclesTask extends AsyncTaskBridge[Unit, Unit, Seq[VehicleInfo]] {
		override def onPreExecute() {
			onVehiclesLocationUpdateStarted()
		}

		override def doInBackgroundBridge(param: Unit): Seq[VehicleInfo] = {
			val (vehicleType, routeId, routeName) = getVehiclesToTrack
			val request = new RouteInfoRequest(vehicleType, routeId, routeName, DirectionsEx.Both)
			val json = client.getVehiclesLocation(Seq(request))
			parsing.parseVehiclesLocation(json)
		}

		override def onPostExecute(result: Seq[VehicleInfo]) {
			onVehiclesLocationUpdated(result)
		}

		override def onCancelled() {
			onVehiclesLocationUpdateCancelled()
		}
	}
}
