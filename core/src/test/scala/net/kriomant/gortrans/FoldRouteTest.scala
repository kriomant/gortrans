package net.kriomant.gortrans

import net.kriomant.gortrans.core._
import org.scalatest.FunSuite

class FoldRouteTest extends FunSuite {
  test("route with starting stop on forward part only") {
    assert(
      foldRoute(Seq("B", "C"), Seq("C", "A"), identity[String])
        === Seq(
        FoldedRouteStop("B", Some("B"), None),
        FoldedRouteStop("A", None, Some("A")),
        FoldedRouteStop("C", Some("C"), Some("C"))
      )
    )
  }

  test("route with identical forward and backward parts") {
    assert(
      foldRoute(Seq("A", "B", "C"), Seq("C", "B", "A"), identity[String])
        === Seq(
        FoldedRouteStop("A", Some("A"), Some("A")),
        FoldedRouteStop("B", Some("B"), Some("B")),
        FoldedRouteStop("C", Some("C"), Some("C"))
      )
    )
  }

  test("route with stop skipped on backward route part") {
    assert(
      foldRoute(Seq("A", "B", "C"), Seq("C", "A"), identity[String])
        === Seq(
        FoldedRouteStop("A", Some("A"), Some("A")),
        FoldedRouteStop("B", Some("B"), None),
        FoldedRouteStop("C", Some("C"), Some("C"))
      )
    )
  }

  test("route with stop skipped on forward route part") {
    assert(
      foldRoute(Seq("A", "C"), Seq("C", "B", "A"), identity[String])
        === Seq(
        FoldedRouteStop("A", Some("A"), Some("A")),
        FoldedRouteStop("B", None, Some("B")),
        FoldedRouteStop("C", Some("C"), Some("C"))
      )
    )
  }

  test("route with different stops on forward and backward route parts") {
    assert(
      foldRoute(Seq("A", "B", "C"), Seq("C", "D", "A"), identity[String])
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
      foldRoute(Seq("A", "B", "C", "D"), Seq("D", "A"), identity[String])
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
      foldRoute(Seq("A", "B", "C", "D"), Seq("D", "B", "C", "A"), identity[String])
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
      foldRoute(Seq("A", "B", "C", "B", "D"), Seq("D", "B", "C", "B", "A"), identity[String])
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
