package net.kriomant.gortrans
package checker

import org.slf4j.LoggerFactory
import net.kriomant.gortrans.parsing.{RouteStop, RoutePoint}

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

			logger.debug("Check stop names")
			val routeStops = points.collect{ case RoutePoint(Some(RouteStop(name, _)), _, _) => name }
			for (stopName <- routeStops.toSet[String]) {
				if (! stops.contains(stopName))
					logger.error("Stop name '{}' is not known", stopName)
			}

			logger.debug("Check route is foldable")
			try {
				core.foldRoute[String](routeStops, (x => x))
			} catch {
				case e: core.RouteFoldingException => logger.error("Can't fold route: {}", e.getMessage)
			}
		}
	}
}
