package net.kriomant.gortrans

import org.scalatest.FunSuite
import net.kriomant.gortrans.parsing.{VehicleInfo, combineDateAndTime, parseVehiclesLocation}
import java.util
import java.text.SimpleDateFormat
import net.kriomant.gortrans.core.{Direction, VehicleType}

object ut {
	def date(str: String, zone: String = "+00"): util.Date = date(str, util.TimeZone.getTimeZone(zone))

	def date(str: String, zone: util.TimeZone): util.Date = {
		val dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm")
		dateFormat.setTimeZone(zone)
		dateFormat.parse(str)
	}
}

class CombineDateAndTimeTest extends FunSuite {
	import ut.date

	test("combines date and time") {
		assert(
			combineDateAndTime(date("2012-03-04 01:34"), date("1980-05-06 13:46"), util.TimeZone.getTimeZone("GMT"))
			=== date("2012-03-04 13:46")
		)
	}

	test("another timezone") {
		val datePart = date("2012-01-01 19:00", "-06") // 2012-01-02 01:00 GMT
		val timePart = date("2000-01-01 11:46", "-06") // 2000-01-01 17:46 GMT
		assert(
			combineDateAndTime(datePart, timePart, util.TimeZone.getTimeZone("-06"))
			=== date("2012-01-01 11:46", "-06") // 2012-01-01 17:46 GMT
		)
	}
}

class ParseVehiclesLocationTest extends FunSuite {
	import ut.date

	test("short timestamp format") {
		val response = """
			{"markers":[
				{"title":"13","id_typetr":"2","marsh":"13","graph":"1","direction":"A",
				"lat":"55.019932","lng":"82.923119","time_nav":"14:54:00","azimuth":"286",
				"rasp":"14:34+Stop 2|","speed":"15"
			}]}
		"""

		assert(
			parseVehiclesLocation(response, date("2012-03-04 01:02", parsing.NSK_TIME_ZONE))
			=== Seq(
				VehicleInfo(
					VehicleType.TrolleyBus, "13", "13", 1, Some(Direction.Forward), 55.019932f, 82.923119f,
					date("2012-03-04 14:54:00", parsing.NSK_TIME_ZONE), 286, 15, Seq(("14:34", "Stop 2"))
				)
			)
		)
	}
}

