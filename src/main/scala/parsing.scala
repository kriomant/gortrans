package net.kriomant.gortrans

import org.json._

import net.kriomant.gortrans.core._
import net.kriomant.gortrans.utils.booleanUtils

object parsing {

	implicit def jsonArrayTraversable(arr: JSONArray) = new Traversable[JSONObject] {
		def foreach[T](f: JSONObject => T) = {
			for (i <- 0 until arr.length) {
				f(arr.getJSONObject(i))
			}
		}
	}

	def parseRoute(vehicleType: VehicleType.Value, obj: JSONObject) = Route(
		vehicleType,
		id = obj.getString("marsh"),
		name = obj.getString("name"),
		begin = obj.getString("stopb"),
		end = obj.getString("stope")
	)
	
	def parseSection(obj: JSONObject): (VehicleType.Value, Seq[Route]) = {
			val vtype = VehicleType(obj.getInt("type"))
			(vtype, obj.getJSONArray("ways") map {j => parseRoute(vtype, j)} toSeq)
	}

	type RoutesInfo = Map[VehicleType.Value, Seq[Route]]

	def parseRoutes(arr: JSONArray): RoutesInfo = {
		arr map parseSection toMap
	}

	def parseRoutesJson(json: String): RoutesInfo = {
		val tokenizer = new JSONTokener(json)
		val arr = new JSONArray(tokenizer)
		parseRoutes(arr)
	}
	
	case class RouteStop(name: String, length: Int)
	case class RoutePoint(stop: Option[RouteStop], latitude: Double, longitude: Double)

	def parseRoutesPoints(obj: JSONObject): Map[String, Seq[RoutePoint]] = {
		obj.getJSONArray("all") flatMap { o =>
			o.getJSONArray("r") map { m =>
				val routeId = m.getString("marsh")
				val points: Seq[RoutePoint] = m.getJSONArray("u").toSeq map { p =>
					val lat = p.getDouble("lat")
					val lng = p.getDouble("lng")
					val stop = (p has "n") ? RouteStop(p.getString("n"), p.getInt("len"))
					RoutePoint(stop, lat, lng)
				}
				routeId -> points
			}
		} toMap
	}

	def parseRoutesPoints(json: String): Map[String, Seq[RoutePoint]] = {
		parseRoutesPoints(new JSONObject(new JSONTokener(json)))
	}
}

