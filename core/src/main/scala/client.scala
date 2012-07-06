package net.kriomant.gortrans

import java.io.{InputStreamReader, BufferedInputStream}
import utils.readerUtils
import java.net.{URLEncoder, HttpURLConnection, URL}
import net.kriomant.gortrans.core.{ScheduleType, VehicleType, Direction, DirectionsEx}
import scala.collection.JavaConverters._
import org.json.{JSONObject, JSONArray}

trait Logger {
	def debug(msg: String)
	def verbose(msg: String)
}

object Client {
	case class RouteInfoRequest(
		vehicleType: VehicleType.Value,
		routeId: String,
		routeName: String,
		direction: DirectionsEx.Value
	)
}
/** Client for maps.nskgortrans.ru site.
	*/
class Client(logger: Logger) {
	import Client._

	final val HOST = new URL("http://nskgortrans.ru")
	final val MAPS_HOST = new URL("http://maps.nskgortrans.ru")
	final val MOBILE_HOST = new URL("http://m.nskgortrans.ru")

	private[this] var mapsSessionId: String = null

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

	def getRoutesInfo(requests: Traversable[Client.RouteInfoRequest]): String = {
		val params = requests map { r =>
			"%d-%s-%s-%s" format (r.vehicleType.id+1, r.routeId, directionsExCodes(r.direction), r.routeName)
		} mkString "|"
		fetch(new URL(MAPS_HOST, "gsearch.php?r=" + URLEncoder.encode(params, "UTF-8")))
	}

	def getStopsList(query: String = ""): String = {
		fetch(new URL(HOST, "components/com_planrasp/helpers/grasp.php?q=%s&typeview=stops" format URLEncoder.encode(query, "UTF-8")))
	}

	def getAvailableScheduleTypes(vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value): String = {
		fetch(new URL(
			HOST,
			"components/com_planrasp/helpers/grasp.php?m=%s&t=%d&r=%s" format (routeId, vehicleType.id+1, directionCodes(direction))
		))
	}

	def getRouteStops(vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value, schedule: Int): String = {
		fetch(new URL(
			HOST,
			"components/com_planrasp/helpers/grasp.php?m=%s&t=%d&r=%s&sch=%d" format (routeId, vehicleType.id+1, directionCodes(direction), schedule)
		))
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

	def getVehiclesLocation(requests: Iterable[Client.RouteInfoRequest]) = {
		if (mapsSessionId == null)
			updateSessionId()

		val params = requests map {
			r =>
				"%d-%s-%s-%s" format(r.vehicleType.id + 1, r.routeId, directionsExCodes(r.direction), r.routeName)
		} mkString "|"
		val url = new URL(MAPS_HOST, "markers.php?r=%s" format URLEncoder.encode(params, "UTF-8"))

		val conn = url.openConnection().asInstanceOf[HttpURLConnection]
		try {
			val watchList = new JSONArray(Seq(
				new JSONObject(Map(
					"name" -> "list",
					"routes" -> new JSONArray(requests.map(r =>
						new JSONObject(Map(
							"type" -> r.vehicleType.id,
							"way" -> r.routeName,
							"marsh" -> r.routeId,
							"left" -> "",
							"right" -> ""
						).asJava)
					).asJavaCollection),
					"zoom" -> 13,
					"latlng" -> "(55.0,83.0)"
				).asJava)
			).asJavaCollection)
			val cookie = "PHPSESSID=%s; value=%s" format(mapsSessionId, URLEncoder.encode(watchList.toString, "UTF-8"))
			conn.addRequestProperty("X-Requested-With", "XMLHttpRequest")
			conn.addRequestProperty("Cookie", cookie)
			val stream = new BufferedInputStream(conn.getInputStream())
			val content = new InputStreamReader(stream).readAll()
			logger.verbose("Response from %s: %s" format(url, content))
			content
		} finally {
			conn.disconnect()
		}
	}

	def getExpectedArrivals(routeId: String, vehicleType: VehicleType.Value, stopId: Int, direction: Direction.Value): String = {
		fetch(
			new URL(
				MOBILE_HOST,
				"index.php?m=%s&t=1&tt=%s&s=%s&r=%s&p=1" format (
					routeId, vehicleType.id+1, stopId, directionCodes(direction)
				)
			)
		)
	}

	private def updateSessionId() {
		val cookies = {
			val conn = MAPS_HOST.openConnection().asInstanceOf[HttpURLConnection]
			try {
				println(conn.getHeaderFields)
				val cookieHeaders = Option(conn.getHeaderFields().get("Set-Cookie")).getOrElse(new java.util.ArrayList[String])
				cookieHeaders.asScala.map {
					header =>
						val text = header.takeWhile(_ != ';')
						val parts = text.split("=", 2)
						(parts(0), parts(1))
				} toMap
			} finally {
				conn.disconnect()
			}
		}

		mapsSessionId = cookies("PHPSESSID")
		logger.debug("PHPSESSID: %s" format mapsSessionId)
	}

	private def fetch(url: URL): String = {
		val conn = url.openConnection().asInstanceOf[HttpURLConnection]
		try {
			val stream = new BufferedInputStream(conn.getInputStream())
			// TODO: Use more effective android.util.JsonReader on API level 11.
			val content = new InputStreamReader(stream).readAll()
			logger.verbose("Response from %s: %s" format (url, content))
			content
		} finally {
			conn.disconnect()
		}
	}
}