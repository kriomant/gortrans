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
import java.util
import net.kriomant.gortrans.android_utils.Observable
import com.sun.xml.internal.ws.client.ClientTransportException

object VehiclesWatcher {
	trait Listener {
		def onVehiclesLocationUpdateStarted()
		def onVehiclesLocationUpdateCancelled()
		def onVehiclesLocationUpdated(vehicles: Either[String, Seq[VehicleInfo]])
	}
}
class VehiclesWatcher(
	context: Activity,
	vehiclesToTrack: Set[(VehicleType.Value, String, String)],
	listener: VehiclesWatcher.Listener
) extends Observable[Either[String, Seq[VehicleInfo]]] {
	private final val TAG = classOf[VehiclesWatcher].getName

	val handler = new Handler
	def client: Client = context.getApplication.asInstanceOf[CustomApplication].dataManager.client

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
		Toast.makeText(context, R.string.vehicles_tracking_turned_on, Toast.LENGTH_SHORT).show()
	}

	def stopUpdatingVehiclesLocation() {
		handler.removeCallbacks(updateVehiclesLocationRunnable)
		Toast.makeText(context, R.string.vehicles_tracking_turned_off, Toast.LENGTH_SHORT).show()

		listener.onVehiclesLocationUpdateCancelled()
	}

	class TrackVehiclesTask extends AsyncTaskBridge[Unit, Either[String, Seq[VehicleInfo]]] {
		override def onPreExecute() {
			listener.onVehiclesLocationUpdateStarted()
		}

		override def doInBackgroundBridge(): Either[String, Seq[VehicleInfo]] = {
			val requests = vehiclesToTrack map { case (vehicleType, routeId, routeName) =>
				new RouteInfoRequest(vehicleType, routeId, routeName, DirectionsEx.Both)
			}
			val res = try {
				val json = DataManager.retryOnceIfEmpty { client.getVehiclesLocation(requests) }
				Right(parsing.parseVehiclesLocation(json, new util.Date))
			} catch {
				// TODO: Reuse code from DataManager.
				case ex @ (
					_: UnknownHostException |
					_: ConnectException |
					_: SocketException |
					_: EOFException |
					_: SocketTimeoutException |
					_: ClientException
				) => {
					Log.v(TAG, "Network failure during data fetching", ex)
					Left(context.getString(R.string.cant_fetch_vehicles))
				}

				case _: org.json.JSONException => Left(context.getString(R.string.cant_parse_vehicles))
			}

			set(res)
			res
		}

		override def onPostExecute(result: Either[String, Seq[VehicleInfo]]) {
			listener.onVehiclesLocationUpdated(result)
		}

		override def onCancelled() {
			listener.onVehiclesLocationUpdateCancelled()
		}
	}
}
