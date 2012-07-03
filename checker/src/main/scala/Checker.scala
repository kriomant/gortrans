package net.kriomant.gortrans
package checker

import org.slf4j.LoggerFactory
import net.kriomant.gortrans.parsing.{RouteStop, RoutePoint}
import net.kriomant.gortrans.core.VehicleType

object Checker {
	val logger = LoggerFactory.getLogger(getClass)

	def main(args: Array[String]) {
		logger.debug("Initialize client")
		object ClientLogger extends Logger {
			val clientLogger = LoggerFactory.getLogger("net.kriomant.gortrans.core.Client")
			def debug(msg: String) { clientLogger.debug(msg) }
			def verbose(msg: String) { clientLogger.trace(msg) }
		}
		val client = new Client(ClientLogger)

		logger.info("Load route list")
		val rawRoutesByType = client.getRoutesList()
		logger.trace("Raw route list:\n{}", rawRoutesByType)
		val routesByType = parsing.parseRoutesJson(rawRoutesByType)
		val routes = routesByType.values.flatten

		logger.info("Load stop list")
		val rawStops = client.getStopsList()
		logger.trace("Raw stop list:\n{}", rawStops)
		val stops = parsing.parseStopsList(rawStops)

		logger.info("Load route points")
		val routePointsRequests = routes.map { route =>
			Client.RouteInfoRequest(route.vehicleType, route.id, route.name, core.DirectionsEx.Both)
		}
		// nskgortrans doesn't allow to query points for all routes at once,
		// it seems there is some limit on number of queried routes. So split
		// routes into smaller portions.
		val routesPoints = routePointsRequests.grouped(30) map { group =>
			val rawRoutesPoints = client.getRoutesInfo(group)
			logger.trace("Raw route points:\n{}", rawRoutesPoints)
			val points = parsing.parseRoutesPoints(rawRoutesPoints)
			points
		} reduceLeft (_ ++ _)

		logger.info("Check routes")
		for (route <- routes) {
			logger.info("Check {} {} route", route.vehicleType, route.name)

			val points = routesPoints(route.id)
			if (points.isEmpty) {
				logger.warn("Route is empty")
			} else {
				logger.debug("Check stop names")
				val routeStops = points.collect{ case RoutePoint(Some(RouteStop(name, _)), _, _) => name }

				var wasStopErrors = false
				for (stopName <- routeStops.toSet[String]) {
					if (missingStops contains (route.vehicleType, route.name, stopName)) {
						logger.warn("Stop named '{}' is ignored", stopName)

					} else {
						val fixedStopName = core.stopNameFixes.get((route.vehicleType, route.name, stopName)) match {
							case Some(n) => {
								logger.warn("Stop name fix applied: {} -> {}", stopName, n)
								n
							}
							case None => stopName
						}

						if (! stops.contains(fixedStopName)) {
							logger.error("Stop name '{}' is not known", fixedStopName)
							wasStopErrors = true
						}
					}
				}

				if (wasStopErrors) {
					val rawSchedules = client.getAvailableScheduleTypes(route.vehicleType, route.id, core.Direction.Forward)
					val schedules = parsing.parseAvailableScheduleTypes(rawSchedules)

					if (schedules.nonEmpty) {
						val rawForwardStops = client.getRouteStops(route.vehicleType, route.id, core.Direction.Forward, schedules.head._1.id)
						val forwardStops = parsing.parseRouteStops(rawForwardStops)

						val rawBackwardStops = client.getRouteStops(route.vehicleType, route.id, core.Direction.Backward, schedules.head._1.id)
						val backwardStops = parsing.parseRouteStops(rawBackwardStops)

						logger.info(
							"\nForward route stops: {}\n\nBackward route stops: {}\n\nStops from route points:{}\n",
							Array[AnyRef](
								forwardStops.map(_.name).mkString(" —— "),
								backwardStops.map(_.name).mkString(" —— "),
								routeStops.mkString(" —— ")
							)
						)
					} else {
						logger.warn("There are no schedule types")
					}
				}

				logger.debug("Check route is foldable")
				try {
					core.foldRoute[String](routeStops, (x => x))
				} catch {
					case e: core.RouteFoldingException => {
						logger.error("Can't fold route: {}\nRoute: {}", e.getMessage, routeStops.mkString(" —— "))
					}
				}
			}
		}
	}

	// Set of stop names (from route points) which are known to be missing in stop list.
	val missingStops: Set[(VehicleType.Value, String, String)] = Set(
		(VehicleType.Bus, "103", "Таймырская ул."),
		(VehicleType.Bus, "103", "Пищекомбинат (Красный Восток)"),
		(VehicleType.Bus, "103", "Строительная ул. (Верх-Тула)"),
		(VehicleType.Bus, "233", "Сельхоз техникум Новосибирский (Раздольное)"),
		(VehicleType.Bus, "1004", "Клубная ул."),
		(VehicleType.Bus, "1004", "Гвардейская"),
		(VehicleType.Bus, "1042", "Управление механизации (ул.Тайгинская)"),
		(VehicleType.Bus, "1060", "Гвардейская"),
		(VehicleType.Bus, "1096", "Клуб Калейдоскоп"),
		(VehicleType.Bus, "1131", "Добролюбова ул."),
		(VehicleType.Bus, "1204", "Гвардейская"),
		(VehicleType.Bus, "1221", "Гвардейская"),
		(VehicleType.Bus, "1243", "Гвардейская"),
		(VehicleType.TrolleyBus, "8", "Гвардейская"),
		(VehicleType.MiniBus, "1045", "Мебельная фабрика"),
		(VehicleType.MiniBus, "1104", "Школа (Кочубея ул.)"),
		(VehicleType.MiniBus, "1104", "ТК Лента"),
		(VehicleType.MiniBus, "1130", "Добролюбова ул."),
		(VehicleType.MiniBus, "1257", "Театр \"Драмы\"")
	)
}
