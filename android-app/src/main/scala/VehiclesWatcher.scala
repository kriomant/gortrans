package net.kriomant.gortrans

import android.os.Handler
import net.kriomant.gortrans.parsing.VehicleInfo
import net.kriomant.gortrans.Client.RouteInfoRequest
import net.kriomant.gortrans.core.{VehicleType, DirectionsEx}
import android.app.Activity
import android.widget.Toast
import java.net.{SocketTimeoutException, SocketException, ConnectException, UnknownHostException}
import java.io.EOFException
import android.util.Log

trait VehiclesWatcher { this: Activity =>
	private final val TAG = classOf[VehiclesWatcher].getName

	val handler: Handler
	def client: Client = getApplication.asInstanceOf[CustomApplication].dataManager.client

	def getVehiclesToTrack: (VehicleType.Value, String, String) // type, routeId, routeName
	def onVehiclesLocationUpdateStarted()
	def onVehiclesLocationUpdateCancelled()
	def onVehiclesLocationUpdated(vehicles: Either[String, Seq[VehicleInfo]])

	private[this] final val VEHICLES_LOCATION_UPDATE_PERIOD = 20000 /* ms */

	private final val updateVehiclesLocationRunnable = new Runnable {
		def run() {
			updateVehiclesLocation()
			handler.postDelayed(this, VEHICLES_LOCATION_UPDATE_PERIOD)
		}
	}

	def updateVehiclesLocation() {
		val task = new TrackVehiclesTask
		task.execute()
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

	class TrackVehiclesTask extends AsyncTaskBridge[Unit, Either[String, Seq[VehicleInfo]]] {
		override def onPreExecute() {
			onVehiclesLocationUpdateStarted()
		}

		override def doInBackgroundBridge(): Either[String, Seq[VehicleInfo]] = {
			val (vehicleType, routeId, routeName) = getVehiclesToTrack
			val request = new RouteInfoRequest(vehicleType, routeId, routeName, DirectionsEx.Both)
			try {
				val json = DataManager.retryOnceIfEmpty { client.getVehiclesLocation(Seq(request)) }
				Right(parsing.parseVehiclesLocation(json))
			} catch {
				// TODO: Reuse code from DataManager.
				case ex @ (
					_: UnknownHostException |
					_: ConnectException |
					_: SocketException |
					_: EOFException |
					_: SocketTimeoutException
				) => {
					Log.v(TAG, "Network failure during data fetching", ex)
					Left(getString(R.string.cant_fetch_vehicles))
				}

				case _: org.json.JSONException => Left(getString(R.string.cant_parse_vehicles))
			}
		}

		override def onPostExecute(result: Either[String, Seq[VehicleInfo]]) {
			onVehiclesLocationUpdated(result)
		}

		override def onCancelled() {
			onVehiclesLocationUpdateCancelled()
		}
	}
}
