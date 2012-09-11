package net.kriomant.gortrans

import java.io._
import net.kriomant.gortrans.utils.{closing, readerUtils}
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.parsing.{RouteStop, RoutePoint, RoutesInfo}
import net.kriomant.gortrans.Client.{RouteInfoRequest}
import android.content.Context
import android.util.Log
import java.util
import java.net.{SocketException, UnknownHostException, ConnectException}
import scala.collection.mutable

object DataManager {
	final val TAG = "DataManager"

	trait ProcessIndicator {
		def startFetch()
		def stopFetch()
		def onSuccess()
		def onError()
	}

	trait Source[Data, Cursor] {
		val name: String
		val maxAge: Long

		def fetch(client: Client): Data
		def update(db: Database, old: Boolean, fresh: Data)
	}

	object RoutesListSource extends Source[RoutesInfo, Database.RoutesTable.Cursor] {
		val name = "routes"
		val maxAge = 4 * 24 * 60 * 60 * 1000l /* ms = 4 days */

		def fetch(client: Client): RoutesInfo = {
			parsing.parseRoutesJson(client.getRoutesList())
		}

		def update(db: Database, old: Boolean, fresh: RoutesInfo) {
			// Collect known routes.
			val known = mutable.Set[(VehicleType.Value, String)]()
			if (old) {
				closing(db.fetchRoutes()) { cursor =>
					while (cursor.moveToNext())
						known.add((cursor.vehicleType, cursor.externalId))
				}
			}

			val routes = fresh.values.flatten[Route].toSeq
			routes.foreach { route =>
				db.addOrUpdateRoute(route.vehicleType, route.id, route.name, route.begin, route.end)
			}

			val obsolete = known -- routes.map(r => (r.vehicleType, r.id)).toSet
			obsolete.foreach { case (vehicleType, id) => db.deleteRoute(vehicleType, id) }
		}
	}

	case class RoutePointsListSource(vehicleType: VehicleType.Value, routeId: String, routeName: String)
		extends Source[Seq[RoutePoint], Unit]
	{
		val name = "route-%d-%s" format (vehicleType.id, routeId)
		val maxAge = 2 * 24 * 60 * 60 * 1000l /* ms = 2 days */

		def fetch(client: Client): Seq[RoutePoint] = {
			val response = client.getRoutesInfo(Seq(RouteInfoRequest(vehicleType, routeId, routeName, DirectionsEx.Both)))
			parsing.parseRoutesPoints(response)(routeId)
		}

		def update(db: Database, old: Boolean, routePoints: Seq[RoutePoint]) {
			val dbRouteId = db.findRoute(vehicleType, routeId)

			db.clearStopsAndPoints(dbRouteId)

			if (routePoints.nonEmpty) {
				// First point is always a stop and last point is just duplicate of the first one.
				// But it may mave different 'length' field value so stops cannot be compared directly.
				assume(
					routePoints.head.stop.isDefined &&
					routePoints.head.stop.get.name == routePoints.last.stop.get.name &&
					routePoints.head.latitude == routePoints.last.latitude &&
					routePoints.head.longitude == routePoints.last.longitude
				)
				val points = routePoints.dropRight(1)

				for ((point, index) <- points.zipWithIndex) {
					db.addRoutePoint(dbRouteId, index, point)
				}

				// Get stop names and corresponding point indices.
				val stops = points.zipWithIndex.collect {
					case (RoutePoint(Some(RouteStop(name, _)), _, _), index) => (name, index)
				}
				val folded = core.foldRoute[(String, Int)](stops, _._1)

				for ((stop, index) <- folded.zipWithIndex) {
					db.addRouteStop(dbRouteId, stop.name, index, stop.forward.map(_._2), stop.backward.map(_._2))
				}
			}
		}
	}

	case class RouteSchedulesSource(vehicleType: VehicleType.Value, routeId: String, stopId: Int, direction: Direction.Value)
		extends Source[Map[ScheduleType.Value, (String, Seq[(Int, Seq[Int])])], Unit]
	{
		val name = "schedule-%d-%s-%d-%s" format (vehicleType.id, routeId, stopId, direction)
		val maxAge = 2 * 24 * 60 * 60 * 1000l /* ms = 2 days */

		def fetch(client: Client): Map[ScheduleType.Value, (String, Seq[(Int, Seq[Int])])] = {
			// I assume schedules types are equal for both directions.
			val scheduleTypes = parsing.parseAvailableScheduleTypes(client.getAvailableScheduleTypes(vehicleType, routeId, Direction.Forward))

			scheduleTypes.map { case (scheduleType, name) =>
				scheduleType -> (name, parsing.parseStopSchedule(client.getStopSchedule(stopId, vehicleType, routeId, direction, scheduleType)))
			}.toMap
		}

		def update(db: Database, old: Boolean, schedules: Map[ScheduleType.Value, (String, Seq[(Int, Seq[Int])])]) {
			val dbRouteId = db.findRoute(vehicleType, routeId)

			db.clearSchedules(dbRouteId, stopId, direction)

			for ((scheduleType, (name, schedule)) <- schedules) {
				val expanded = schedule.flatMap{case (hour, minutes) => minutes.map(m => (hour, m))}
				db.addSchedule(dbRouteId, stopId, direction, scheduleType, name, expanded)
			}
		}
	}
}

class DataManager(context: Context, db: Database) {
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
	def requestRoutesList(getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator)(f: => Unit) {
		requestDatabaseData(RoutesListSource,	 getIndicator, updateIndicator, f)
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

	def requestRoutePoints(
		vehicleType: VehicleType.Value, routeId: String, routeName: String,
		getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator
	)(f: => Unit) {
		requestDatabaseData(RoutePointsListSource(vehicleType, routeId, routeName), getIndicator, updateIndicator, f)
	}

	def requestStopSchedules(
		vehicleType: VehicleType.Value, routeId: String, stopId: Int, direction: Direction.Value,
		getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator
	)(f: => Unit) {
		requestDatabaseData(RouteSchedulesSource(vehicleType, routeId, stopId, direction), getIndicator, updateIndicator, f)
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
						case _: UnknownHostException | _: ConnectException | _: SocketException | _: EOFException => {
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

	/**
	 * @note Must be called from UI thread only.
	 */
	def requestDatabaseData[T, Cursor](
		source: Source[T, Cursor],
		getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator,
		f: => Unit
	) {

		def fetchData(old: Boolean, indicator: ProcessIndicator) {
			val task = new AsyncTaskBridge[Unit, Boolean] {
				override def onPreExecute() {
					indicator.startFetch()
				}

				protected def doInBackgroundBridge(): Boolean = {
					val optData = try {
						Log.v(TAG, "Fetch data")
						val rawData = source.fetch(client)
						Log.v(TAG, "Data is successfully fetched")
						Some(rawData)
					} catch {
						case ex @ (_: UnknownHostException | _: ConnectException | _: SocketException | _: EOFException) => {
							Log.v(TAG, "Network failure during data fetching", ex)
							None
						}
					}

					optData map { rawData =>
						db.transaction {
							source.update(db, old, rawData)
							db.updateLastUpdateTime(source.name, new util.Date)
						}
					}

					optData.isDefined
				}

				override def onPostExecute(success: Boolean) {
					indicator.stopFetch()
					if (success) {
						indicator.onSuccess()
						f
					} else {
						indicator.onError()
					}
				}
			}

			task.execute()
		}

		db.getLastUpdateTime(source.name) match {
			case Some(modificationTime) => {
				f

				if (modificationTime.getTime() + source.maxAge < (new util.Date).getTime) {
					// Cache is out of date.
					fetchData(true, updateIndicator)
				}
			}

			case None => fetchData(false, getIndicator)
		}
	}
}