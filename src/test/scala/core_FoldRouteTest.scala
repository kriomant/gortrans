package net.kriomant.gortrans.core_tests

import org.scalatest.FunSuite
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.core.RouteStopDirections.{Forward, Backward, Both}

class FoldRouteTest extends FunSuite {
	test("exception is thrown if route is empty") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "B"), Seq())
		}
		assert(error.getMessage === "Less than 2 stops in route")
	}

	test("exception is thrown if route contains just one stop") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "B"), Seq("A"))
		}
		assert(error.getMessage === "Less than 2 stops in route")
	}

	test("exception is thrown if first route stop name differs from 'begin' in route info") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "B"), Seq("B", "B"))
		}
		assert(error.getMessage === "First route stop differs from 'begin' in route info")
	}

	test("exception is thrown if last route stop name differs from 'begin' in route info") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "B"), Seq("A", "C"))
		}
		assert(error.getMessage === "Last route stop differs from 'begin' in route info")
	}

	test("exception is thrown if there are no two consequtive 'end' stops in route") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "B"), Seq("A", "B", "C", "A"))
		}
		assert(error.getMessage === "Two consequtive 'end' stops aren't found in route")
	}
	
	test("route with identical forward and backward parts") {
		assert(
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "C"), Seq("A", "B", "C", "C", "B", "A"))
			=== Seq(("A", Both), ("B", Both), ("C", Both))
		)
	}
	
	test("route with stop skipped on backward route part") {
		assert(
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "C"), Seq("A", "B", "C", "C", "A"))
			=== Seq(("A", Both), ("B", Forward), ("C", Both))
		)
	}

	test("route with stop skipped on forward route part") {
		assert(
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "C"), Seq("A", "C", "C", "B", "A"))
				=== Seq(("A", Both), ("B", Backward), ("C", Both))
		)
	}

	test("route with different stops on forward and backward route parts") {
		assert(
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "C"), Seq("A", "B", "C", "C", "D", "A"))
				=== Seq(("A", Both), ("B", Forward), ("D", Backward), ("C", Both))
		)
	}

	test("route with several stops skipped on backward route part") {
		assert(
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "D"), Seq("A", "B", "C", "D", "D", "A"))
				=== Seq(("A", Both), ("B", Forward), ("C", Forward), ("D", Both))
		)
	}

	test("route with different order of stops") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Route(VehicleType.Bus, "id", "name", "A", "D"), Seq("A", "B", "C", "D", "D", "B", "C", "A"))
		}
		assert(error.getMessage === "Different order of route stops")
	}
}
