package net.kriomant.gortrans

import java.io._
import net.kriomant.gortrans.utils.{closing, readerUtils}
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.parsing.{RouteStop, RoutePoint, RoutesInfo}
import net.kriomant.gortrans.Client.{RouteInfoRequest}
import android.content.Context
import android.util.Log
import java.util
import java.net.{SocketTimeoutException, SocketException, UnknownHostException, ConnectException}
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

	def retryOnceIfEmpty(f: => String): String = {
		var res = f
		if (res.nonEmpty) res else f
	}

	object RoutesListSource extends Source[RoutesInfo, Database.RoutesTable.Cursor] {
		val name = "routes"
		val maxAge = 4 * 24 * 60 * 60 * 1000l /* ms = 4 days */

		def fetch(client: Client): RoutesInfo = {
			val json = retryOnceIfEmpty { client.getRoutesList() }
			parsing.parseRoutesJson(json)
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

	case class RoutePointsListSource(
		vehicleType: VehicleType.Value, routeId: String, routeName: String,
		routeBegin: String, routeEnd: String
	) extends Source[Seq[RoutePoint], Unit]
	{
		val name = "route-%d-%s" format (vehicleType.id, routeId)
		val maxAge = 2 * 24 * 60 * 60 * 1000l /* ms = 2 days */

		def fetch(client: Client): Seq[RoutePoint] = {
			val response = retryOnceIfEmpty {
				client.getRoutesInfo(Seq(RouteInfoRequest(vehicleType, routeId, routeName, DirectionsEx.Both)))
			}
			parsing.parseRoutesPoints(response)(routeId)
		}

		def update(db: Database, old: Boolean, routePoints: Seq[RoutePoint]) {
			val dbRouteId = db.findRoute(vehicleType, routeId)

			db.clearStopsAndPoints(dbRouteId)

			if (routePoints.nonEmpty) {
				// Split route into forward and backward parts.
				val (forward, backward) = splitRoute(routePoints, routeBegin, routeEnd)

				for ((point, index) <- (forward ++ backward).zipWithIndex) {
					db.addRoutePoint(dbRouteId, index, point)
				}

				// Get stop names and corresponding point indices.
				val forwardStops = forward.zipWithIndex.collect {
					case (RoutePoint(Some(RouteStop(name)), _, _), index) => (name, index)
				}
				val backwardStops = backward.zipWithIndex.collect {
					case (RoutePoint(Some(RouteStop(name)), _, _), index) => (name, index + forward.length)
				}
				val folded = core.foldRoute[(String, Int)](forwardStops, backwardStops, _._1)

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

	object ScheduleStopsSource extends Source[Map[String, Int], Unit] {
		val name = "stops"
		val maxAge = 4 * 24 * 60 * 60 * 1000l /* ms = 4 days */

		def fetch(client: Client): Map[String, Int] = {
			parsing.parseStopsList(client.getStopsList(""))
		}

		def update(db: Database, old: Boolean, stops: Map[String, Int]) {
			db.clearStops()

			for ((name, stopId) <- stops) {
				db.addStop(name, stopId)
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
	                                                           
	/**
	 * @note Must be called from UI thread only.
	 */
	def requestRoutesList(getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator)(f: => Unit) {
		requestDatabaseData(RoutesListSource,	 getIndicator, updateIndicator, f)
	}

	def requestStopsList(getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator)(f: => Unit) {
		requestDatabaseData(ScheduleStopsSource, getIndicator, updateIndicator, f)
	}

	def requestRoutePoints(
		vehicleType: VehicleType.Value, routeId: String, routeName: String,
		routeBegin: String, routeEnd: String,
		getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator
	)(f: => Unit) {
		requestDatabaseData(
			RoutePointsListSource(vehicleType, routeId, routeName, routeBegin, routeEnd),
			getIndicator, updateIndicator, f
		)
	}

	def requestStopSchedules(
		vehicleType: VehicleType.Value, routeId: String, stopId: Int, direction: Direction.Value,
		getIndicator: ProcessIndicator, updateIndicator: ProcessIndicator
	)(f: => Unit) {
		requestDatabaseData(RouteSchedulesSource(vehicleType, routeId, stopId, direction), getIndicator, updateIndicator, f)
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
					Thread.currentThread.setName("AsyncTask: request %s" format source.name)
					try {
						val optData = try {
							Log.v(TAG, "Fetch data")
							val rawData = source.fetch(client)
							Log.v(TAG, "Data is successfully fetched")
							Some(rawData)
						} catch {
							case ex @ (
								_: UnknownHostException |
								_: ConnectException |
								_: SocketException |
								_: EOFException |
								_: SocketTimeoutException
							) => {
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

					} finally {
						Thread.currentThread.setName("AsyncTask: idle")
					}
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