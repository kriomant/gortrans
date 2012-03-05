package net.kriomant.gortrans.core_tests

import org.scalatest.FunSuite
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.core.DirectionsEx.{Forward, Backward, Both}

class FoldRouteTest extends FunSuite {
	test("exception is thrown if route is empty") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Seq())
		}
		assert(error.getMessage === "Less than 2 stops in route")
	}

	test("exception is thrown if route contains just one stop") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Seq("A"))
		}
		assert(error.getMessage === "Less than 2 stops in route")
	}

	test("exception is thrown if first route is not the same as last one") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Seq("B", "C", "C", "A"))
		}
		assert(error.getMessage === "The first route stop is not the same as the last one")
	}

	test("exception is thrown if there are no two identical consequtive stops in route") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Seq("A", "B", "C", "A"))
		}
		assert(error.getMessage === "End route stop is not found")
	}
	
	test("route with identical forward and backward parts") {
		assert(
			foldRoute(Seq("A", "B", "C", "C", "B", "A"))
			=== Seq(("A", Both), ("B", Both), ("C", Both))
		)
	}
	
	test("route with stop skipped on backward route part") {
		assert(
			foldRoute(Seq("A", "B", "C", "C", "A"))
			=== Seq(("A", Both), ("B", Forward), ("C", Both))
		)
	}

	test("route with stop skipped on forward route part") {
		assert(
			foldRoute(Seq("A", "C", "C", "B", "A"))
				=== Seq(("A", Both), ("B", Backward), ("C", Both))
		)
	}

	test("route with different stops on forward and backward route parts") {
		assert(
			foldRoute(Seq("A", "B", "C", "C", "D", "A"))
				=== Seq(("A", Both), ("B", Forward), ("D", Backward), ("C", Both))
		)
	}

	test("route with several stops skipped on backward route part") {
		assert(
			foldRoute(Seq("A", "B", "C", "D", "D", "A"))
				=== Seq(("A", Both), ("B", Forward), ("C", Forward), ("D", Both))
		)
	}

	test("route with different order of stops") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Seq("A", "B", "C", "D", "D", "B", "C", "A"))
		}
		assert(error.getMessage === "Different order of route stops")
	}
}
