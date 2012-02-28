package net.kriomant.gortrans

import collection.mutable
import net.kriomant.gortrans.core.RouteFoldingException

object core {
	object VehicleType extends Enumeration {
		val Bus = Value(0)
		val TrolleyBus = Value(1)
		val TramWay = Value(2)
		val MiniBus = Value(7)
	}

	case class Route(vehicleType: VehicleType.Value, id: String, name: String, begin: String, end: String)

	type RoutesInfo = Map[VehicleType.Value, Seq[Route]]

	object RouteStopDirections extends Enumeration {
		val Forward, Backward, Both = Value
	}
	case class FoldedRouteStop(name: String, directions: RouteStopDirections.Value)

	class RouteFoldingException(msg: String) extends Exception(msg)

	def foldRoute(routeInfo: Route, stopNames: Seq[String]): Seq[(String,  RouteStopDirections.Value)] = {
		// nskgortrans.ru returns route stops for both back and forth
		// route parts as single list. E.g. for route between A and D
		// (with corresponding stops in between) route stops list is
		// [A, B, C, D, D, C, B, A]
		// This is required because forward and backward route parts may
		// differ, for example vehicle may not stop on B stop when it goes
		// backward.
		// Thus it is needed to "fold" route stops list to make it shorter.
		// Each route stop in folded route is marked with directions
		// where it is available.

		if (stopNames.length < 2)
			throw new RouteFoldingException("Less than 2 stops in route")

		// Split route into forward and backward parts basing
		// on end route stop name from route info.
		if (stopNames.head != routeInfo.begin)
			throw new RouteFoldingException("First route stop differs from 'begin' in route info")
		if (stopNames.last != routeInfo.begin)
			throw new RouteFoldingException("Last route stop differs from 'begin' in route info")

		val pos = stopNames.indexOfSlice(Seq(routeInfo.end, routeInfo.end))
		if (pos == -1)
			throw new RouteFoldingException("Two consequtive 'end' stops aren't found in route")

		val (forward, back) = stopNames.splitAt(pos + 1)
		val backward = back.reverse

		// Index stops position.
		val stopIndex = stopNames.toSet[String].map{ name => (name, (forward.indexOf(name), backward.indexOf(name)))}.toMap

		// Build folded route at last.
		val foldedRoute = new mutable.ArrayBuffer[(String, RouteStopDirections.Value)]
		var fpos = 0 // Position of first unfolded stop in forward route.
		var bpos = 0 // The same for backward route.
		while (fpos < forward.length) {
			// Take stop name from forward route.
			val name = forward(fpos)
			// Find the same stop in backward route.
			val bstoppos = stopIndex(name)._2

			if (bstoppos != -1) {
				if (bstoppos < bpos)
					throw new RouteFoldingException("Different order of route stops")

				// Add all backward stops between bpos and bstoppos as backward-only.
				foldedRoute ++= backward.slice(bpos, bstoppos).map((_, RouteStopDirections.Backward))
				foldedRoute += Tuple2(name, RouteStopDirections.Both)
				bpos = bstoppos+1
			} else {
				foldedRoute += Tuple2(name, RouteStopDirections.Forward)
			}

			fpos += 1
		}
		
		foldedRoute
	}

}
