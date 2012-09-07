package net.kriomant.gortrans

import collection.mutable
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

	def foldRouteInternal(stops: Seq[String]): Seq[(String, Option[Int], Option[Int])] = {
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

		if (stops.length < 3)
			throw new RouteFoldingException("Less than 3 stops in route")

		// First and last route stops are the same stop on forward and backward parts.
		if (stops.head != stops.last)
			throw new RouteFoldingException("The first route stop is not the same as last two ones")

		// Find two consecutive stops with the same name - it is terminal stop.
		// If terminal stop is not found, it may be one-way circular route,
		// just split it in the middle.
		val pos = (2 to stops.length - 2).find(i => stops(i - 1) == stops(i)).getOrElse(stops.length/2)

		// Build folded route at last.
		val foldedRoute = new mutable.ArrayBuffer[(String, Option[Int], Option[Int])]
		var fpos = 0 // Position of first unfolded stop in forward route.
		var bpos = stops.length - 1 // Position of last unfolded stop in backward route (backward part of route is folded from the end)
		while (fpos < pos) {
			// Take stop name from forward route.
			val name = stops(fpos)
			// Find the same stop in backward route.
			val bstoppos = stops.view(pos, stops.length).lastIndexOf(name, bpos-pos) match {
				case -1 => -1
				case  n => n + pos
			}

			if (bstoppos != -1) {
				// Add all backward stops between bpos and bstoppos as backward-only.
				foldedRoute ++= ((bstoppos + 1) to bpos).reverse.map(s => (stops(s), None, Some(s)))
				foldedRoute += ((name, Some(fpos), Some(bstoppos)))
				bpos = bstoppos - 1
			} else {
				foldedRoute += ((name, Some(fpos), None))
			}

			fpos += 1
		}

		foldedRoute ++= (pos to bpos).reverse.map(s => (stops(s), None, Some(s)))

		foldedRoute
	}

	def foldRoute[Stop](stops: Seq[Stop], getName: Stop => String): Seq[FoldedRouteStop[Stop]] = {
		if (stops.length < 3)
			throw new RouteFoldingException("Less than 3 stops in route")

		// Route returned by nskgortrans contains three starting stop names: first name,
		// last name (it is the same stop on forward part) and one-before-last name (it is stop
		// on backward part).
		val stops_ = if (getName(stops.last) == getName(stops(stops.length - 2))) stops.dropRight(1) else stops

		// Last name (which is the same stop as first one) is non needed by internal
		// algorithm, strip it.
		foldRouteInternal(stops_.map(getName)).map {
			case (name, findex, bindex) =>
				FoldedRouteStop(name, findex.map(stops.apply), bindex.map(stops.apply))
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
		assert(route.head.x == route.last.x && route.head.y == route.last.y)

		var length = 0.0
		val positions = route.sliding(2).map {
			case Seq(start, end) =>
				val cur = length
				length += math.sqrt(
					(end.x - start.x) * (end.x - start.x) +
						(end.y - start.y) * (end.y - start.y)
				)
				cur
		}.toArray

		(length, positions)
	}

	/**
	 * Snap vehicle to route.
	 * @returns `Some((segment_index, segment_part))` if vehicle is successfully snapped, `None` otherwise.
	 */
	def snapVehicleToRouteInternal(vehicle: VehicleInfo, route: Seq[Pt]): Option[(Int, Double)] = {
		// Dumb brute-force algorithm: enumerate all route segments for each vehicle.
		val segments = route.sliding(2).map {
			case Seq(from, to) => (from, to)
		}
		val location = Pt(vehicle.longitude.toDouble, vehicle.latitude.toDouble)

		// Find segment closest to vehicle position.
		val segmentsWithDistance: Iterator[(Double, Int, Double)] = segments.zipWithIndex map {
			case (segment@(start, end), segmentIndex) =>
				val closestPointPos = closestSegmentPointPortion(location, start, end)
				val closestPoint = start + (end - start) * closestPointPos
				val distance = location distanceTo closestPoint
				(distance, segmentIndex, closestPointPos)
		}
		val (distance, segmentIndex, pointPos) = segmentsWithDistance.minBy(_._1)

		// Calculate distance in meters from vehicle location to closest segment.
		// ATTENTION: Distances below are specific to Novosibirsk:
		// One degree of latitude contains ~111 km, one degree of longitude - ~67 km.
		// We use approximation 100 km per degree for both directions.
		val MAX_DISTANCE_FROM_ROUTE = 20 /* meters */
		val MAX_DISTANCE_IN_DEGREES = MAX_DISTANCE_FROM_ROUTE / 100000.0

		(distance <= MAX_DISTANCE_IN_DEGREES) ? (segmentIndex, pointPos)
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
