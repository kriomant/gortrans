package net.kriomant.gortrans

import org.scalatest.FunSuite
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.core.DirectionsEx.{Forward, Backward, Both}

class FoldRouteTest extends FunSuite {
	test("exception is thrown if route is empty") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Seq[String](), identity[String])
		}
		assert(error.getMessage === "Less than 3 stops in route")
	}

	test("exception is thrown if route contains just one stop") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Seq("A"), identity[String])
		}
		assert(error.getMessage === "Less than 3 stops in route")
	}

	test("exception is thrown if first route is not the same as last one") {
		val error = intercept[RouteFoldingException] {
			foldRoute(Seq("B", "C", "C", "A"), identity[String])
		}
		assert(error.getMessage === "The first route stop is not the same as last two ones")
	}

	test("route without two identical consequtive stops is folded in the middle") {
		assert(
			foldRoute(Seq("A", "B", "C", "A", "A"), identity[String])
				=== Seq(
				FoldedRouteStop("A", Some("A"), Some("A")),
				FoldedRouteStop("B", Some("B"), None),
				FoldedRouteStop("C", None, Some("C"))
			)
		)
	}

	test("route with identical forward and backward parts") {
		assert(
			foldRoute(Seq("A", "B", "C", "C", "B", "A", "A"), identity[String])
				=== Seq(
				FoldedRouteStop("A", Some("A"), Some("A")),
				FoldedRouteStop("B", Some("B"), Some("B")),
				FoldedRouteStop("C", Some("C"), Some("C"))
			)
		)
	}

	test("route with stop skipped on backward route part") {
		assert(
			foldRoute(Seq("A", "B", "C", "C", "A", "A"), identity[String])
				=== Seq(
				FoldedRouteStop("A", Some("A"), Some("A")),
				FoldedRouteStop("B", Some("B"), None),
				FoldedRouteStop("C", Some("C"), Some("C"))
			)
		)
	}

	test("route with stop skipped on forward route part") {
		assert(
			foldRoute(Seq("A", "C", "C", "B", "A", "A"), identity[String])
				=== Seq(
				FoldedRouteStop("A", Some("A"), Some("A")),
				FoldedRouteStop("B", None, Some("B")),
				FoldedRouteStop("C", Some("C"), Some("C"))
			)
		)
	}

	test("route with different stops on forward and backward route parts") {
		assert(
			foldRoute(Seq("A", "B", "C", "C", "D", "A", "A"), identity[String])
				=== Seq(
				FoldedRouteStop("A", Some("A"), Some("A")),
				FoldedRouteStop("B", Some("B"), None),
				FoldedRouteStop("D", None, Some("D")),
				FoldedRouteStop("C", Some("C"), Some("C"))
			)
		)
	}

	test("route with several stops skipped on backward route part") {
		assert(
			foldRoute(Seq("A", "B", "C", "D", "D", "A", "A"), identity[String])
				=== Seq(
				FoldedRouteStop("A", Some("A"), Some("A")),
				FoldedRouteStop("B", Some("B"), None),
				FoldedRouteStop("C", Some("C"), None),
				FoldedRouteStop("D", Some("D"), Some("D"))
			)
		)
	}

	test("route with different order of stops") {
		assert(
			foldRoute(Seq("A", "B", "C", "D", "D", "B", "C", "A", "A"), identity[String])
			=== Seq(
				FoldedRouteStop("A", Some("A"), Some("A")),
				FoldedRouteStop("C", None, Some("C")),
				FoldedRouteStop("B", Some("B"), Some("B")),
				FoldedRouteStop("C", Some("C"), None),
				FoldedRouteStop("D", Some("D"), Some("D"))
			)
		)
	}

	test("duplicate stops") {
		assert(
			foldRoute(Seq("A", "B", "C", "B", "D", "D", "B", "C", "B", "A", "A"), identity[String])
				=== Seq(
				FoldedRouteStop("A", Some("A"), Some("A")),
				FoldedRouteStop("B", Some("B"), Some("B")),
				FoldedRouteStop("C", Some("C"), Some("C")),
				FoldedRouteStop("B", Some("B"), Some("B")),
				FoldedRouteStop("D", Some("D"), Some("D"))
			)
		)
	}
}
