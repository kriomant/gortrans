package net.kriomant.gortrans

import java.io.{InputStreamReader, BufferedInputStream}
import utils.readerUtils
import net.kriomant.gortrans.core.VehicleType
import java.net.{URLEncoder, HttpURLConnection, URL}

object Client {
	object RouteDirection extends Enumeration {
		val Forward, Backward, Both = Value
	}

	case class RouteInfoRequest(
		vehicleType: VehicleType.Value,
		routeId: String,
		direction: RouteDirection.Value
	)
}
/** Client for maps.nskgortrans.ru site.
	*/
class Client {
	final val HOST = "maps.nskgortrans.ru"

	def getRoutesList(): String = {
		query("listmarsh.php?r")
	}

	def directionCodes = Map(
		Client.RouteDirection.Forward -> "A",
		Client.RouteDirection.Backward -> "B",
		Client.RouteDirection.Both -> "W"
	)
	
	def getRoutesInfo(requests: Seq[Client.RouteInfoRequest]): String = {
		val params = requests map { r =>
			"%d-%s-%s-%s" format (r.vehicleType.id+1, r.routeId, directionCodes(r.direction), r.routeId)
		} mkString "|"
		query("gsearch.php?r=" + URLEncoder.encode(params))
	}
	
	private def query(path: String): String = {
		val url = new URL("http", HOST, path)
		val conn = url.openConnection().asInstanceOf[HttpURLConnection]
		try {
			val stream = new BufferedInputStream(conn.getInputStream())
			// TODO: Use more effective android.util.JsonReader on API level 11.
			new InputStreamReader(stream).readAll()
		} finally {
			conn.disconnect()
		}
	}
}