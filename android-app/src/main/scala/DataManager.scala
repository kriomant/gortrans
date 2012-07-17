package net.kriomant.gortrans

import java.io._
import net.kriomant.gortrans.utils.{closing, readerUtils}
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.parsing.{RoutePoint, RoutesInfo}
import net.kriomant.gortrans.Client.{RouteInfoRequest}
import android.content.Context
import android.util.Log
import java.util
import java.net.{UnknownHostException, ConnectException}

object DataManager {
	final val TAG = "DataManager"

	trait ProcessIndicator {
		def startFetch()
		def stopFetch()
		def onSuccess()
		def onError()
	}
}

class DataManager(context: Context) {
	import DataManager._

	private[this] final val TAG = "DataManager"

	object AndroidLogger extends Logger {
		def debug(msg: String) { Log.d("gortrans", msg) }
		def verbose(msg: String) { Log.v("gortrans", msg) }
	}
	val client = new Client(AndroidLogger)
	                                                           
	def getRoutesList(): RoutesInfo = {
		val cacheName = "routes.json"
		getCachedOrFetch(cacheName, () => client.getRoutesList(), parsing.parseRoutesJson(_))
	}

	/**
	 * @note Must be called from UI thread only.
	 */
	def requestRoutesList(getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator)(f: RoutesInfo => Unit) {
		val MAX_ROUTES_LIST_AGE = 4 * 24 * 60 * 60 * 1000 /* ms = 4 days */
		requestData(
			"routes.json", MAX_ROUTES_LIST_AGE,
			client.getRoutesList, parsing.parseRoutesJson,
			getIndicator, updateIndicator, f
		)
	}

	def getStopsList(): Map[String, Int] = {
		val cacheName = "stops.txt"
		getCachedOrFetch(
			cacheName,
			() => client.getStopsList(""),
			parsing.parseStopsList(_)
		)
	}

	def requestStopsList(getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator)(f: Map[String, Int] => Unit) {
		val MAX_STOPS_LIST_AGE = 4 * 24 * 60 * 60 * 1000 /* ms = 4 days */
		requestData(
			"stops.txt",
			MAX_STOPS_LIST_AGE,
			client.getStopsList(""),
			parsing.parseStopsList(_),
			getIndicator, updateIndicator, f
		)
	}

	def getRoutePoints(vehicleType: VehicleType.Value, routeId: String, routeName: String): Seq[RoutePoint] = {
		val cacheName = "points/%s-%s.json".format(vehicleType.toString, routeId)
		getCachedOrFetch(
			cacheName,
			() => client.getRoutesInfo(Seq(RouteInfoRequest(vehicleType, routeId, routeName, DirectionsEx.Both))),
			json => parsing.parseRoutesPoints(json)(routeId)
		)
	}

	def requestRoutePoints(
		vehicleType: VehicleType.Value, routeId: String, routeName: String,
		getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator
	)(f: Seq[RoutePoint] => Unit) {
		val MAX_ROUTE_POINTS_AGE = 4 * 24 * 60 * 60 * 1000 /* ms = 4 days */
		requestData(
			"points/%s-%s.json" format (vehicleType.toString, routeId),
			MAX_ROUTE_POINTS_AGE,
			client.getRoutesInfo(Seq(RouteInfoRequest(vehicleType, routeId, routeName, DirectionsEx.Both))),
			json => parsing.parseRoutesPoints(json)(routeId),
			getIndicator, updateIndicator, f
		)
	}

	def getAvailableRouteScheduleTypes(vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value): Map[ScheduleType.Value, String] = {
		val cacheName = "route-schedule-types/%d-%s-%s.xml" format (vehicleType.id, routeId, direction.toString)
		getCachedOrFetch(
			cacheName,
			() => client.getAvailableScheduleTypes(vehicleType, routeId, direction),
			parsing.parseAvailableScheduleTypes(_)
		)
	}

	def requestAvailableRouteScheduleTypes(
		vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value,
		getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator
	)(f: Map[ScheduleType.Value, String] => Unit) {
		val MAX_AGE = 4 * 24 * 60 * 60 * 1000 /* ms = 4 days */
		requestData(
			"route-schedule-types/%d-%s-%s.xml" format (vehicleType.id, routeId, direction.toString),
			MAX_AGE,
			client.getAvailableScheduleTypes(vehicleType, routeId, direction),
			parsing.parseAvailableScheduleTypes,
			getIndicator, updateIndicator, f
		)
	}

	def getStopSchedule
		(stopId: Int, vehicleType: VehicleType.Value, routeId: String, direction: Direction.Value, scheduleType: ScheduleType.Value)
	= {
		val cacheName = "stop-schedule/%d-%s-%s-%s-%s.xml" format (stopId, vehicleType.toString, routeId, direction.toString, scheduleType.toString)
		getCachedOrFetch(
			cacheName,
			() => client.getStopSchedule(stopId, vehicleType, routeId, direction, scheduleType),
			xml => parsing.parseStopSchedule(xml)
		)
	}

	def readFromCache(relPath: String): Option[String] = readFromCacheWithTimestamp(relPath) map (_._1)

	def readFromCacheWithTimestamp(relPath: String): Option[(String, Long)] = {
		try {
			val path = new File(context.getCacheDir, relPath)
			closing(new FileInputStream(path)) { s =>
				closing(new InputStreamReader(s)) { r =>
					Some(r.readAll(), path.lastModified())
				}
			}
		} catch {
			case _: FileNotFoundException => None
		}
	}

	def writeToCache(relPath: String, data: String) {
		val path = new File(context.getCacheDir, relPath)

		if (! path.getParentFile.exists())
			path.getParentFile.mkdirs()

		closing(new FileOutputStream(path)) { s =>
			closing(new OutputStreamWriter(s)) { w =>
				w.write(data)
			}
		}
	}

	def getCachedOrFetch[T](cacheName: String, fetch: () => String, parse: String => T): T = {
		val cached = readFromCache(cacheName)
		val data = cached getOrElse fetch()

		val parsed = parse(data)

		// Cache just retrieved info only if it was successfully parsed.
		if (cached.isEmpty)
			writeToCache(cacheName, data)

		parsed
	}

	/**
	 * @note Must be called from UI thread only.
	 */
	def requestData[T](
		cacheName: String, updatePeriod: Int,
		fetch: => String, parse: String => T,
		getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator,
		f: T => Unit
	) {
		def fetchData(indicator: ProcessIndicator) {
			val task = new AsyncTaskBridge[Unit, Option[T]] {
				override def onPreExecute() {
					indicator.startFetch()
				}

				protected def doInBackgroundBridge(): Option[T] = {
					val optData = try {
						Log.v(TAG, "Fetch data")
						val rawData = fetch
						Log.v(TAG, "Data is successfully fetched")
						Some(rawData)
					} catch {
						case _: UnknownHostException | _: ConnectException => {
							Log.v(TAG, "Network failure during data fetching")
							None
						}
					}

					optData map { rawData =>
						val newData = parse(rawData)
						writeToCache(cacheName, rawData)
						newData
					}
				}

				override def onPostExecute(result: Option[T]) {
					indicator.stopFetch()
					result match {
						case Some(data) => indicator.onSuccess(); f(data)
						case None => indicator.onError()
					}
				}
			}

			task.execute()
		}

		readFromCacheWithTimestamp(cacheName) match {
			case Some((cachedData, modificationTime)) => {
				val data = parse(cachedData)
				f(data)

				if (modificationTime + updatePeriod < (new util.Date).getTime) {
					// Cache is out of date.
					fetchData(updateIndicator)
				}
			}

			case None => fetchData(getIndicator)
		}
	}
}