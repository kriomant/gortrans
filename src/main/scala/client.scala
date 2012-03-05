package net.kriomant.gortrans

import java.io.{InputStreamReader, BufferedInputStream}
import utils.readerUtils
import java.net.{URLEncoder, HttpURLConnection, URL}
import net.kriomant.gortrans.core.{ScheduleType, VehicleType, Direction, DirectionsEx}
import android.util.Log

object Client {
	case class RouteInfoRequest(
		vehicleType: VehicleType.Value,
		routeId: String,
		direction: DirectionsEx.Value
	)
}
/** Client for maps.nskgortrans.ru site.
	*/
class Client {
	final val HOST = new URL("http://nskgortrans.ru")
	final val MAPS_HOST = new URL("http://maps.nskgortrans.ru")

	// Note that route ends returned by this method are unreliable!
	// There are many routes for which this method returns stops
	// different from actual start and end stops as returned by
	// getRoutePoints.
	def getRoutesList(): String = {
		fetch(new URL(MAPS_HOST, "listmarsh.php?r"))
	}

	def directionCodes = Map(
		Direction.Forward -> "A",
		Direction.Backward -> "B"
	)

	def directionsExCodes = Map(
		DirectionsEx.Forward -> "A",
		DirectionsEx.Backward -> "B",
		DirectionsEx.Both -> "W"
	)
	
	def getRoutesInfo(requests: Seq[Client.RouteInfoRequest]): String = {
		val params = requests map { r =>
			"%d-%s-%s-%s" format (r.vehicleType.id+1, r.routeId, directionsExCodes(r.direction), r.routeId)
		} mkString "|"
		fetch(new URL(MAPS_HOST, "gsearch.php?r=" + URLEncoder.encode(params)))
	}

	def getStopsList(query: String = ""): String = {
		fetch(new URL(HOST, "components/com_planrasp/helpers/grasp.php?q=%s&typeview=stops" format URLEncoder.encode(query)))
	}

	def getStopSchedule(stopId: Int, vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value, scheduleType: ScheduleType.Value) = {
		fetch(
			new URL(
				HOST,
				"components/com_planrasp/helpers/grasp.php?tv=mr&m=%s&t=%d&r=%s&sch=%d&s=%d&v=0" format (routeId, vehicleType.id+1, directionCodes(direction), scheduleType.id, stopId)
			)
			// v=0 - detailed, v=1 - intervals
		)
	}

	private def fetch(url: URL): String = {
		val conn = url.openConnection().asInstanceOf[HttpURLConnection]
		try {
			val stream = new BufferedInputStream(conn.getInputStream())
			// TODO: Use more effective android.util.JsonReader on API level 11.
			val content = new InputStreamReader(stream).readAll()
			Log.v("Client", "Response from %s: %s" format (url, content))
			content
		} finally {
			conn.disconnect()
		}
	}
}