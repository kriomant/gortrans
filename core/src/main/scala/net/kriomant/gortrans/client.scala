package net.kriomant.gortrans

import java.io.{BufferedInputStream, InputStream, InputStreamReader}
import java.net.{HttpURLConnection, URL, URLEncoder}
import java.util

import net.kriomant.gortrans.core.{Direction, DirectionsEx, ScheduleType, VehicleType}
import net.kriomant.gortrans.utils.ReaderUtils
import org.json.{JSONArray, JSONObject}

import scala.collection.JavaConverters._

trait Logger {
  def debug(msg: String)

  def verbose(msg: String)
}

class ClientException(message: String, cause: Throwable = null) extends Exception(message, cause)

object Client {

  final val HOST = new URL("http://nskgortrans.ru")
  final val MAPS_HOST = new URL("http://maps.nskgortrans.ru")
  final val MOBILE_HOST = new URL("http://m.nskgortrans.ru")

  def readWholeStream(stream: InputStream): String = {
    val buffered = new BufferedInputStream(stream)
    new InputStreamReader(buffered).readAll()
  }

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

  private[this] var mapsSessionId: String = _

  // Note that route ends returned by this method are unreliable!
  // There are many routes for which this method returns stops
  // different from actual start and end stops as returned by
  // getRoutePoints.
  def getRoutesList: String = {
    fetch(new URL(MAPS_HOST, "listmarsh.php?r"))
  }

  def getRoutesInfo(requests: Traversable[Client.RouteInfoRequest]): String = {
    val params = requests map { r =>
      "%d-%s-%s-%s" format(r.vehicleType.id + 1, r.routeId, directionsExCodes(r.direction), r.routeName)
    } mkString "|"
    fetch(new URL(MAPS_HOST, "trasses.php?r=" + URLEncoder.encode(params, "UTF-8")))
  }

  private def fetch(url: URL): String = fetchWithConn(url) { (content, _) => content }

  def getStopsList(query: String = ""): String = {
    fetch(new URL(MAPS_HOST, "components/com_planrasp/helpers/grasp.php?q=%s&typeview=stops" format URLEncoder.encode(query, "UTF-8")))
  }

  def getAvailableScheduleTypes(vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value): String = {
    fetch(new URL(
      MAPS_HOST,
      "components/com_planrasp/helpers/grasp.php?m=%s&t=%d&r=%s" format(routeId, vehicleType.id + 1, directionCodes(direction))
    ))
  }

  def getRouteStops(vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value, schedule: Int): String = {
    fetch(new URL(
      MAPS_HOST,
      "components/com_planrasp/helpers/grasp.php?m=%s&t=%d&r=%s&sch=%d" format(routeId, vehicleType.id + 1, directionCodes(direction), schedule)
    ))
  }

  def getStopSchedule(stopId: Int, vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value, scheduleType: ScheduleType.Value): String = {
    fetch(
      new URL(
        MAPS_HOST,
        "components/com_planrasp/helpers/grasp.php?tv=mr&m=%s&t=%d&r=%s&sch=%d&s=%d&v=0" format(routeId, vehicleType.id + 1, directionCodes(direction), scheduleType.id, stopId)
      )
      // v=0 - detailed, v=1 - intervals
    )
  }

  def getVehiclesLocation(requests: Iterable[Client.RouteInfoRequest]): String = {
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
      val stream = new BufferedInputStream(conn.getInputStream)
      val content = new InputStreamReader(stream).readAll()
      logger.verbose("Response from %s: %s" format(url, content))
      content
    } finally {
      conn.disconnect()
    }
  }

  def directionsExCodes = Map(
    DirectionsEx.Forward -> "A",
    DirectionsEx.Backward -> "B",
    DirectionsEx.Both -> "W"
  )

  /** Returns expected arrivals and current server time. */
  def getExpectedArrivals(
                           routeId: String, vehicleType: VehicleType.Value, stopId: Int, direction: Direction.Value
                         ): (String, util.Date) = {
    val url = new URL(
      MOBILE_HOST,
      "index.php?m=%s&t=1&tt=%s&s=%s&r=%s&p=1" format(
        routeId, vehicleType.id + 1, stopId, directionCodes(direction)
      )
    )

    fetchWithConn(url) { (content, conn) =>
      val date = new util.Date(conn.getHeaderFieldDate("Date", new util.Date().getTime))
      (content, date)
    }
  }

  def directionCodes = Map(
    Direction.Forward -> "A",
    Direction.Backward -> "B"
  )

  private def fetchWithConn[T](url: URL)(f: (String, HttpURLConnection) => T): T = {
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    try {
      // TODO: Use more effective android.util.JsonReader on API level 11.
      val content = readWholeStream(conn.getInputStream)
      logger.verbose("Response from %s: %s" format(url, content))
      f(content, conn)
    } finally {
      conn.disconnect()
    }
  }

  def getNews: String = {
    fetch(new URL(HOST, "/"))
  }

  private def updateSessionId() {
    val cookies = {
      val conn = MAPS_HOST.openConnection().asInstanceOf[HttpURLConnection]
      try {
        val headers = conn.getHeaderFields
        if (headers == null)
          throw new ClientException("Can't get response headers")

        logger.verbose("Headers: %s" format headers)

        val cookieHeaders = headers.asScala.flatMap {
          case (null, _) => Seq() // HTTP response status is stored with `null` key.
          case (name, value) if name equalsIgnoreCase "Set-Cookie" => value.asScala
          case _ => Seq()
        }
        cookieHeaders.map {
          header =>
            val text = header.takeWhile(_ != ';')
            val parts = text.split("=", 2)
            (parts(0), parts(1))
        } toMap
      } finally {
        conn.disconnect()
      }
    }

    cookies.get("PHPSESSID") match {
      case Some(sessionId) =>
        logger.debug("PHPSESSID: %s" format mapsSessionId)
        mapsSessionId = sessionId

      case None => throw new ClientException("Session cookie not found")
    }
  }
}