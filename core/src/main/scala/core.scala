package net.kriomant.gortrans

import collection.mutable
import Predef._

import net.kriomant.gortrans.geometry.{Point => Pt, closestSegmentPoint, closestSegmentPointPortion}
import net.kriomant.gortrans.parsing.{VehicleInfo, RoutePoint, RouteStop}
import net.kriomant.gortrans.utils.traversableOnceUtils
import net.kriomant.gortrans.utils.booleanUtils

object core {

	object VehicleType extends Enumeration {
		val Bus = Value(0)
		val TrolleyBus = Value(1)
		val TramWay = Value(2)
		val MiniBus = Value(7)
	}

	case class Route(vehicleType: VehicleType.Value, id: String, name: String, begin: String, end: String)

	type RoutesInfo = Map[VehicleType.Value, Seq[Route]]

	object Direction extends Enumeration {
		val Forward, Backward = Value

		def inverse(dir: Direction.Value) = dir match {
			case Forward => Backward
			case Backward => Forward
		}
	}

	object DirectionsEx extends Enumeration {
		val Forward, Backward, Both = Value
	}

	object ScheduleType extends Enumeration {
		val Holidays = Value(5)
		val Workdays = Value(11)
		val Daily = Value(23)
	}

	class RouteFoldingException(msg: String) extends Exception(msg)

	case class FoldedRouteStop[Stop](name: String, forward: Option[Stop], backward: Option[Stop]) {
		require(forward.isDefined || backward.isDefined)

		val directions = (forward, backward) match {
			case (Some(_), Some(_)) => DirectionsEx.Both
			case (Some(_), None) => DirectionsEx.Forward
			case (None, Some(_)) => DirectionsEx.Backward
			case (None, None) => throw new AssertionError
		}
	}

	def foldRouteInternal(forward: Seq[String], backward: Seq[String]): Seq[(String, Option[Int], Option[Int])] = {
		// Build folded route at last.
		val foldedRoute = new mutable.ArrayBuffer[(String, Option[Int], Option[Int])]
		var fpos = 0 // Position of first unfolded stop in forward route.
		var bpos = backward.length - 1 // Position of last unfolded stop in backward route (backward part of route is folded from the end)
		while (fpos < forward.length) {
			// Take stop name from forward route.
			val name = forward(fpos)
			// Find the same stop in backward route.
			val bstoppos = backward.lastIndexOf(name, bpos) match {
				case -1 => foldedRoute += ((name, Some(fpos), None))
				case bstoppos => {
					// Add all backward stops between bpos and bstoppos as backward-only.
					foldedRoute ++= ((bstoppos + 1) to bpos).reverse.map(s => (backward(s), None, Some(s)))
					foldedRoute += ((name, Some(fpos), Some(bstoppos)))
					bpos = bstoppos - 1
				}
			}

			fpos += 1
		}

		// Forward stops are exhausted, but there may be unused backward stops, add them.
		foldedRoute ++= (0 to bpos).reverse.map(s => (backward(s), None, Some(s)))

		foldedRoute
	}

	def foldRoute[Stop](forward: Seq[Stop], backward: Seq[Stop], getName: Stop => String): Seq[FoldedRouteStop[Stop]] = {
		foldRouteInternal(forward.map(getName), backward.map(getName)).map {
			case (name, findex, bindex) =>
				FoldedRouteStop(name, findex.map(forward.apply), bindex.map(backward.apply))
		}
	}

	def splitRoute(points: Seq[RoutePoint], begin: String, end: String): (Seq[RoutePoint], Seq[RoutePoint]) = {
		// First point is always a stop and last point is just duplicate of the first one.
		require(points.nonEmpty && points.head.stop.isDefined && points.head == points.last)

		// Find two consecutive stops with the same position (excluding first and last points).
		(2 to points.length - 2).find(i => points(i).stop.isDefined && points(i-1) == points(i)) match {
			case Some(pos) => (points.slice(0, pos-1), points.slice(pos, points.length-1))
			case None => {
				// If equal points are not found, try to split path based on stop names only.

				// Select stop names together with corresponding point indices.
				val stops = points.zipWithIndex.slice(1, points.length-1).collect{
					case (RoutePoint(Some(RouteStop(name)), _, _), index) => (name, index)
				}

				val pos = {
					// Try to find consecutive stops with the same name.
					(1 to stops.length-1).find(i => stops(i-1)._1 == stops(i)._1).map(stops(_)._2)

				} orElse {
						// If such stops aren't found, try to find stop with name given as route end stop.
						stops.find(_._1 == end).map(_._2)

				} getOrElse {

					// Last resort - split stops approximately in the middle.
					if (stops.nonEmpty) stops(stops.length/2)._2 else 1
				}

				(points.slice(0, pos), points.slice(pos, points.length-1))
			}
		}
	}

	/**Splits route into forward and backward parts and returns index of first route point belonging
	 * to backward part.
	 */
	def splitRoutePosition(foldedRoute: Seq[FoldedRouteStop[RoutePoint]], routePoints: Seq[parsing.RoutePoint]): Int = {
		// Split route into forward and backward parts for proper snapping of vehicle locations.
		// Find last route stop.
		val borderStop = (foldedRoute.last.forward orElse foldedRoute.last.backward).get
		routePoints.indexOf(borderStop) + 1
	}

	def splitRoute(foldedRoute: Seq[FoldedRouteStop[RoutePoint]], routePoints: Seq[parsing.RoutePoint]): (scala.Seq[RoutePoint], scala.Seq[RoutePoint]) = {
		val borderStopIndex = splitRoutePosition(foldedRoute, routePoints)
		// Last point is included into both forward route (as last point) and into
		// backward route (as first point).
		val forwardRoutePoints = routePoints.slice(0, borderStopIndex + 1)
		val backwardRoutePoints = routePoints.slice(borderStopIndex, routePoints.length)
		(forwardRoutePoints, backwardRoutePoints)
	}

	sealed class CircularStraightenRoute(val positions: Seq[Double], val totalLength: Double) {
		@inline def stopPosition(i: Int) = positions(i)

		@inline def distanceToPrevious(i: Int) = i match {
			case 0 => totalLength - positions.last
			case _ => positions(i)
		}
	}

	/**Returns distance from route start to each route point.
	 */
	def straightenRoute(route: Seq[Pt]): (Double, Seq[Double]) = {
		var length = 0.0
		val positions = (route :+ route.head).sliding(2).map { case Seq(start, end) =>
			val cur = length
			length += start distanceTo end
			cur
		}.toArray

		(length, positions.ensuring(_.length == route.length))
	}

	// ATTENTION: Distances below are specific to Novosibirsk:
	// One degree of latitude contains ~111 km, one degree of longitude - ~67 km.
	// We use approximation 100 km per degree for both directions.
	val MAX_DISTANCE_FROM_ROUTE = 30 /* meters */
	val MAX_DISTANCE_IN_DEGREES = MAX_DISTANCE_FROM_ROUTE / 100000.0

	/**
	 * Snap vehicle to route.
	 * @return `Some((segment_index, segment_part))` if vehicle is successfully snapped, `None` otherwise.
	 */
	def snapVehicleToRouteInternal(vehicle: VehicleInfo, route: Seq[Pt]): Option[(Int, Double)] = {
		// Dumb brute-force algorithm: enumerate all route segments for each vehicle.
		val location = Pt(vehicle.longitude.toDouble, vehicle.latitude.toDouble)

		def segmentNotTooFar(location: Pt, start: Pt, end: Pt): Boolean = {
			// Get segment bounding box.
			var (left, right) = if (start.x < end.x) (start.x, end.x) else (end.x, start.x)
			var (top, bottom) = if (start.y < end.y) (start.y, end.y) else (end.y, start.y)
			// Expand by maximum distance.
			left -= MAX_DISTANCE_IN_DEGREES
			right += MAX_DISTANCE_IN_DEGREES
			top -= MAX_DISTANCE_IN_DEGREES
			bottom += MAX_DISTANCE_IN_DEGREES
			location.x >= left && location.x <= right && location.y >= top && location.y <= bottom
		}

		// Find segment closest to vehicle position.
		var minDistance = MAX_DISTANCE_IN_DEGREES
		var closestSegmentIndex = -1
		var closestSegmentPointPos: Double = 0.0

		for (segmentIndex <- 0 until route.length-1) {
			val start = route(segmentIndex)
			val end = route(segmentIndex+1)
			if (segmentNotTooFar(location, start, end)) {
				val closestPointPos = closestSegmentPointPortion(location, start, end)
				val closestPoint = start + (end-start) * closestPointPos
				val distance = location distanceTo closestPoint
				if (distance < minDistance) {
					closestSegmentIndex = segmentIndex
					minDistance = distance
					closestSegmentPointPos = closestPointPos
				}
			}
		}

		(closestSegmentIndex != -1) ? ((closestSegmentIndex, closestSegmentPointPos))
	}

	def snapVehicleToRoute(vehicle: VehicleInfo, route: Seq[Pt]): (Pt, Option[(Pt, Pt)]) = {
		snapVehicleToRouteInternal(vehicle, route) match {
			case Some((segmentIndex, pointPos)) => {
				val start = route(segmentIndex)
				val end = route(segmentIndex + 1)
				val point = start + (end - start) * pointPos
				(point, Some((start, end)))
			}
			case None => (Pt(vehicle.longitude.toDouble, vehicle.latitude.toDouble), None)
		}
	}

	// Seems like stop names in route points and stop names in stop list
	// are taken from different sources, so stop names may differ. This
	// leads to "Stop not found" errors when user tries to view stop
	// arrivals.
	// This maps are used to find correct stop name (as in stop list) by route name and stop name
	// from route points.
	val commonStopNameFixes: Map[String, String] = Map(
		"Магазин №18" -> "Магазин № 18",
		"сады Украина" -> "Сады \"Украина\"",
		"Храм Михаила Архангела" -> "Храм Архангела Михаила",
		"Механизаторов ул." -> "Механизаторов",
		"Вещевой рынок (Гусинобродское шоссе)" -> "Вещевой рынок \"Гусинобродский\"",
		"Семьи Шамшиных" -> "Семьи Шамшиных ул.",
		"Крылова ул." -> "Крылова",
		"Торговый центр (Верх-Тула)" -> "Торговый центр (пос. Верх-Тула)",
		"Магазин №24" -> "Магазин № 24",
		"Магазин(Каменка)" -> "Магазин (Каменка)",
		"Центр (с. Барышево)" -> "Центрт(пос.Барышево)",
		"пос. Верх-Тула" -> "с. Верх-Тула",
		"Индустриальная ул." -> "Индустриальная",
		"Поселковый совет (Каменка)" -> "Поселковый Совет(Каменка)",
		"Строительная ул." -> "Строительная",
		"Стрелочная ул" -> "Стрелочная ул.",
		"c.Кудряши" -> "с. Кудряши",
		"Поликлиника" -> "Поликлиника (Авиастр)",
		"Троллейный ж/м (Пархоменко ул.)" -> "Троллейный ж/м",
		"Вокзал (Бердск)" -> "Вокзал г Бердска",
		"Автовокзал (Каменская магистраль)" -> "Автовокзал",
		"Клуб Калейдоскоп" -> "Клюб Калейдоскоп",
		"Ипподромская ул." -> "Ипподромская ул. (ул. Писарева)",
		"с.Каменка" -> "с. Каменка",
		"Диагностический центр (Гор. больница)" -> "Диагностический центр (Городская больница)",
		"Управление механизации (ул.Тайгинская)" -> "Управление механизации",
		"Немировича-Данченко" -> "Немировича-Данченко ул.",
		"Питомник" -> "Микрорайон \"Весенний\"",
		"Сады" -> "Сады (Петухова ул.)",
		"Родники ж/м (ул. Земнухова)" -> "Земнухова ул.",
		"пос. Кирова (Бердское шоссе)" -> "пос. Кирова",
		"Поликлиника (Софийская)" -> "Поликлиника (Гидромонтажная)",
		"Поселок (Каменское шоссе)" -> "Поселок"
	)

	val routeSpecificStopNameFixes: Map[(VehicleType.Value, String, String), String] = Map(
		(VehicleType.Bus, "45", "Поликлиника (Софийская)") -> "Поликлиника (Демакова)",
		(VehicleType.Bus, "1064", "Вертковская ул.") -> "Вертковская ул. (ул. Станиславского)",
		(VehicleType.MiniBus, "327", "Горького ул.") -> "Горького(Бердск)",
		(VehicleType.MiniBus, "347", "Больница (Мочищенское шоссе)") -> "Больница (Софийская ул.)",
		(VehicleType.MiniBus, "347", "Институт (Мочищенское шоссе)") -> "Институт",
		(VehicleType.MiniBus, "1130", "Сады (пр. Дзержинского)") -> "Сады",
		(VehicleType.MiniBus, "1148", "Поликлиника (Софийская)") -> "Больница (Софийская ул.)",
		(VehicleType.MiniBus, "1255", "Гвардейская") -> "Вертковская ул. (ул. Сибиряков Гвардейцев)"
	)

	def fixStopName(vehicleType: VehicleType.Value, routeName: String, stopName: String): String = {
		routeSpecificStopNameFixes.getOrElse(
			(vehicleType, routeName, stopName),
			commonStopNameFixes.getOrElse(stopName, stopName)
		)
	}
}
