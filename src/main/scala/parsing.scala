package net.kriomant.gortrans

import org.json._

import net.kriomant.gortrans.core._

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
}

