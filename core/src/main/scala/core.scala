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
		// Two last stops are no compared, because they are terminal stop on forward
		// route part and same stop on backward route part.
		val pos = (2 to stops.length - 2).find(i => stops(i - 1) == stops(i)).
			getOrElse(throw new RouteFoldingException("End route stop is not found"))

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
		routePoints.findIndexOf(_ == borderStop) + 1
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
	def straightenRoute(route: Seq[RoutePoint]): (Double, Seq[Double]) = {
		assert(route.head.latitude == route.last.latitude && route.head.longitude == route.last.longitude)

		var length = 0.0
		val positions = route.sliding(2).map {
			case Seq(start, end) =>
				val cur = length
				length += math.sqrt(
					(end.latitude - start.latitude) * (end.latitude - start.latitude) +
						(end.longitude - start.longitude) * (end.longitude - start.longitude)
				)
				cur
		}.toArray

		(length, positions)
	}

	/**
	 * Snap vehicle to route.
	 * @returns `Some((segment_index, segment_part))` if vehicle is successfully snapped, `None` otherwise.
	 */
	def snapVehicleToRouteInternal(vehicle: VehicleInfo, route: Seq[RoutePoint]): Option[(Int, Double)] = {
		// Dumb brute-force algorithm: enumerate all route segments for each vehicle.
		val segments = route.sliding(2).map {
			case Seq(from, to) =>
				(Pt(from.longitude, from.latitude), Pt(to.longitude, to.latitude))
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

		(distance <= MAX_DISTANCE_IN_DEGREES) ?(segmentIndex, pointPos)
	}

	def snapVehicleToRoute(vehicle: VehicleInfo, route: Seq[RoutePoint]): (Pt, Option[(Pt, Pt)]) = {
		snapVehicleToRouteInternal(vehicle, route) match {
			case Some((segmentIndex, pointPos)) => {
				val start = Pt(route(segmentIndex).longitude, route(segmentIndex).latitude)
				val end = Pt(route(segmentIndex + 1).longitude, route(segmentIndex + 1).latitude)
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
	// Use this map to find correct stop name (as in stop list) by route name and stop name
	// from route points.
	val stopNameFixes: Map[(VehicleType.Value, String, String), String] = Map(
		(VehicleType.Bus, "16", "Магазин №18") -> "Магазин № 18",
		(VehicleType.Bus, "21", "Храм Михаила Архангела") -> "Храм Архангела Михаила",
		(VehicleType.Bus, "21", "Питомник") -> "Микрорайон \"Весенний\"",
		(VehicleType.Bus, "23", "Механизаторов ул.") -> "Механизаторов",
		(VehicleType.Bus, "36", "Храм Михаила Архангела") -> "Храм Архангела Михаила",
		(VehicleType.Bus, "38", "Стрелочная ул") -> "Стрелочная ул.",
		(VehicleType.Bus, "38", "Питомник") -> "Микрорайон \"Весенний\"",
		(VehicleType.Bus, "21", "Питомник") -> "Микрорайон \"Весенний\"",
		(VehicleType.Bus, "45", "Поликлиника (Софийская)") -> "Поликлиника (Демакова)",
		(VehicleType.Bus, "45", "Поликлиника (Софийская)") -> "Поликлиника (Гидромонтажная)",
		(VehicleType.Bus, "45", "Поликлиника (Софийская)") -> "Поликлиника (Гидромонтажная)",
		(VehicleType.Bus, "48", "Поликлиника (Софийская)") -> "Поликлиника (Гидромонтажная)",
		(VehicleType.Bus, "68", "Питомник") -> "Микрорайон \"Весенний\"",
		(VehicleType.Bus, "103", "сады Украина") -> "Сады \"Украина\"",
		(VehicleType.Bus, "103", "Торговый центр (Верх-Тула)") -> "Торговый центр (пос. Верх-Тула)",
		(VehicleType.Bus, "103", "пос. Верх-Тула") -> "с. Верх-Тула",
		(VehicleType.Bus, "103", "Таймырская ул.") -> null,
		(VehicleType.Bus, "103", "Пищекомбинат (Красный Восток)") -> null,
		(VehicleType.Bus, "103", "Строительная ул. (Верх-Тула)") -> null,
		(VehicleType.Bus, "116", "Центр (с. Барышево)") -> "Центрт(пос.Барышево)",
		(VehicleType.Bus, "120", "c.Кудряши") -> "с. Кудряши",
		(VehicleType.Bus, "233", "Сады") -> "Сады (Петухова ул.)",
		(VehicleType.Bus, "233", "Вещевой рынок (Гусинобродское шоссе)") -> "Вещевой рынок \"Гусинобродский\"",
		(VehicleType.Bus, "233", "Сельхоз техникум Новосибирский (Раздольное)") -> null,
		(VehicleType.Bus, "1004", "Клубная ул.") -> null,
		(VehicleType.Bus, "1004", "Гвардейская") -> null,
		(VehicleType.Bus, "1027", "Управление механизации (ул.Тайгинская)") -> "Управление механизации",
		(VehicleType.Bus, "1038", "Родники ж/м (ул. Земнухова)") -> "Земнухова ул.",
		(VehicleType.Bus, "1042", "Управление механизации (ул.Тайгинская)") -> null,
		(VehicleType.Bus, "1060", "Индустриальная ул.") -> "Индустриальная",
		(VehicleType.Bus, "1060", "Троллейный ж/м (Пархоменко ул.)") -> "Троллейный ж/м",
		(VehicleType.Bus, "1060", "Гвардейская") -> null,
		(VehicleType.Bus, "1064", "Вертковская ул.") -> "Вертковская ул. (ул. Станиславского)",
		(VehicleType.Bus, "1096", "Клуб Калейдоскоп") -> null,
		(VehicleType.Bus, "1103", "Поликлиника") -> "Поликлиника (Авиастр)",
		(VehicleType.Bus, "1109", "Сады") -> "Сады (Петухова ул.)",
		(VehicleType.Bus, "1119", "Магазин №18") -> "Магазин № 18",
		(VehicleType.Bus, "1119", "Магазин №24") -> "Магазин № 24",
		(VehicleType.Bus, "1131", "Семьи Шамшиных") -> "Семьи Шамшиных ул.",
		(VehicleType.Bus, "1131", "Добролюбова ул.") -> null,
		(VehicleType.Bus, "1135", "Клуб Калейдоскоп") -> "Клюб Калейдоскоп",
		(VehicleType.Bus, "1135", "Троллейный ж/м (Пархоменко ул.)") -> "Троллейный ж/м",
		(VehicleType.Bus, "1137", "Немировича-Данченко") -> "Немировича-Данченко ул.",
		(VehicleType.Bus, "1150", "Троллейный ж/м (Пархоменко ул.)") -> "Троллейный ж/м",
		(VehicleType.Bus, "1204", "Индустриальная ул.") -> "Индустриальная",
		(VehicleType.Bus, "1204", "Гвардейская") -> null,
		(VehicleType.Bus, "1208", "Поликлиника") -> "Поликлиника (Авиастр)",
		(VehicleType.Bus, "1208", "Автовокзал (Каменская магистраль)") -> "Автовокзал",
		(VehicleType.Bus, "1221", "Храм Михаила Архангела") -> "Храм Архангела Михаила",
		(VehicleType.Bus, "1221", "Питомник") -> "Микрорайон \"Весенний\"",
		(VehicleType.Bus, "1221", "Сады") -> "Сады (Петухова ул.)",
		(VehicleType.Bus, "1221", "Гвардейская") -> null,
		(VehicleType.Bus, "1243", "Троллейный ж/м (Пархоменко ул.)") -> "Троллейный ж/м",
		(VehicleType.Bus, "1243", "Строительная ул.") -> "Строительная",
		(VehicleType.Bus, "1243", "Гвардейская") -> null,
		(VehicleType.Bus, "1324", "Индустриальная ул.") -> "Индустриальная",
		(VehicleType.Bus, "1444", "Ипподромская ул.") -> "Ипподромская ул. (ул. Писарева)",
		(VehicleType.TrolleyBus, "8", "Гвардейская") -> null,
		(VehicleType.TrolleyBus, "13", "Ипподромская ул.") -> "Ипподромская ул. (ул. Писарева)",
		(VehicleType.TrolleyBus, "22", "Питомник") -> "Микрорайон \"Весенний\"",
		(VehicleType.TrolleyBus, "26", "Сады") -> "Сады (Петухова ул.)",
		(VehicleType.MiniBus, "323", "сады Украина") -> "Сады \"Украина\"",
		(VehicleType.MiniBus, "323", "Торговый центр (Верх-Тула)") -> "Торговый центр (пос. Верх-Тула)",
		(VehicleType.MiniBus, "327", "Горького ул.") -> "Горького(Бердск)",
		(VehicleType.MiniBus, "327", "Вокзал (Бердск)") -> "Вокзал г Бердска",
		(VehicleType.MiniBus, "327", "пос. Кирова (Бердское шоссе)") -> "пос. Кирова",
		(VehicleType.MiniBus, "341", "Поликлиника (Софийская)") -> "Поликлиника (Гидромонтажная)",
		(VehicleType.MiniBus, "347", "Больница (Мочищенское шоссе)") -> "Больница (Софийская ул.)",
		(VehicleType.MiniBus, "347", "Институт (Мочищенское шоссе)") -> "Институт",
		(VehicleType.MiniBus, "399", "Поселковый совет (Каменка)") -> "Поселковый Совет(Каменка)",
		(VehicleType.MiniBus, "399", "Магазин(Каменка)") -> "Магазин (Каменка)",
		(VehicleType.MiniBus, "399", "с.Каменка") -> "с. Каменка",
		(VehicleType.MiniBus, "399", "Поселок (Каменское шоссе)") -> "Поселок",
		(VehicleType.MiniBus, "399", "Поселковый совет (Каменка)") -> "Поселковый совет(Каменка)",
		(VehicleType.MiniBus, "1028", "Диагностический центр (Гор. больница)") -> "Диагностический центр (Городская больница)",
		(VehicleType.MiniBus, "1031", "Диагностический центр (Гор. больница)") -> "Диагностический центр (Городская больница)",
		(VehicleType.MiniBus, "1031", "Троллейный ж/м (Пархоменко ул.)") -> "Троллейный ж/м",
		(VehicleType.MiniBus, "1045", "Мебельная фабрика") -> null,
		(VehicleType.MiniBus, "1045", "Поликлиника (Софийская)") -> "Поликлиника (Гидромонтажная)",
		(VehicleType.MiniBus, "1048", "Крылова ул.") -> "Крылова",
		(VehicleType.MiniBus, "1068", "Поликлиника") -> "Поликлиника (Авиастр)",
		(VehicleType.MiniBus, "1073", "Управление механизации (ул.Тайгинская)") -> "Управление механизации",
		(VehicleType.MiniBus, "1104", "Поликлиника") -> "Поликлиника (Авиастр)",
		(VehicleType.MiniBus, "1104", "Школа (Кочубея ул.)") -> null,
		(VehicleType.MiniBus, "1104", "ТК Лента") -> null,
		(VehicleType.MiniBus, "1128", "Автовокзал (Каменская магистраль)") -> "Автовокзал",
		(VehicleType.MiniBus, "1130", "Поликлиника") -> "Поликлиника (Авиастр)",
		(VehicleType.MiniBus, "1130", "Поселок (Каменское шоссе)") -> "Поселок",
		(VehicleType.MiniBus, "1130", "Сады (пр. Дзержинского)") -> "Сады",
		(VehicleType.MiniBus, "1130", "Вещевой рынок (Гусинобродское шоссе)") -> "Вещевой рынок \"Гусинобродский\"",
		(VehicleType.MiniBus, "1130", "Добролюбова ул.") -> null,
		(VehicleType.MiniBus, "1148", "Поликлиника (Софийская)") -> "Больница (Софийская ул.)",
		(VehicleType.MiniBus, "1223", "Механизаторов ул.") -> "Механизаторов",
		(VehicleType.MiniBus, "1228", "Питомник") -> "Микрорайон \"Весенний\"",
		(VehicleType.MiniBus, "1251", "Семьи Шамшиных") -> "Семьи Шамшиных ул.",
		(VehicleType.MiniBus, "1255", "Механизаторов ул.") -> "Механизаторов",
		(VehicleType.MiniBus, "1255", "Гвардейская") -> "Вертковская ул. (ул. Сибиряков Гвардейцев)",
		(VehicleType.MiniBus, "1257", "Театр \"Драмы\"") -> null,
		(VehicleType.MiniBus, "1321", "Храм Михаила Архангела") -> "Храм Архангела Михаила",
		(VehicleType.MiniBus, "1321", "Питомник") -> "Микрорайон \"Весенний\"",
		(VehicleType.MiniBus, "1322", "Храм Михаила Архангела") -> "Храм Архангела Михаила",
		(VehicleType.MiniBus, "1322", "Питомник") -> "Микрорайон \"Весенний\""
	)
}
