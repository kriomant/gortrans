package net.kriomant.gortrans

import net.kriomant.gortrans.core._
import net.kriomant.gortrans.parsing.{RoutePoint, RouteStop}
import org.scalatest.FunSuite

class SplitRouteTest extends FunSuite {
  test("fails when terminal points are not equal") {
    intercept[IllegalArgumentException] {
      splitRoute(Seq(
        RoutePoint(Some(RouteStop("A")), 1.0, 2.0),
        RoutePoint(None, 3.0, 4.0),
        RoutePoint(Some(RouteStop("A")), 1.0, 3.0)
      ), "A", "Z")
    }
  }

  test("fails when terminal points are not stops") {
    intercept[IllegalArgumentException] {
      splitRoute(Seq(
        RoutePoint(None, 1.0, 2.0),
        RoutePoint(Some(RouteStop("A")), 3.0, 4.0),
        RoutePoint(None, 1.0, 2.0)
      ), "A", "Z")
    }
  }

  test("consequtive equal points") {
    assert(
      splitRoute(Seq(
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0),
        RoutePoint(None, 0.0, 0.0),
        RoutePoint(Some(RouteStop("Y")), 1.0, 0.0),
        RoutePoint(Some(RouteStop("Y")), 1.0, 0.0),
        RoutePoint(None, 0.0, 0.0),
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0)
      ), "A", "Z")

        === (Seq(
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0),
        RoutePoint(None, 0.0, 0.0)
      ), Seq(
        RoutePoint(Some(RouteStop("Y")), 1.0, 0.0),
        RoutePoint(None, 0.0, 0.0)
      ))
    )
  }

  test("consecutive same-named stops") {
    assert(
      splitRoute(Seq(
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0),
        RoutePoint(None, 0.0, 0.0),
        RoutePoint(Some(RouteStop("Y")), 1.0, 0.0),
        RoutePoint(None, 3.0, 0.0), // Points between stops are ignored.
        RoutePoint(Some(RouteStop("Y")), 4.0, 0.0), // Coordinates of second stop may differ.
        RoutePoint(None, 0.0, 0.0),
        RoutePoint(Some(RouteStop("Z")), 2.0, 0.0), // Check that end stop name won't affect result.
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0)
      ), "A", "Z")

        === (Seq(
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0),
        RoutePoint(None, 0.0, 0.0),
        RoutePoint(Some(RouteStop("Y")), 1.0, 0.0),
        RoutePoint(None, 3.0, 0.0)
      ), Seq(
        RoutePoint(Some(RouteStop("Y")), 4.0, 0.0),
        RoutePoint(None, 0.0, 0.0),
        RoutePoint(Some(RouteStop("Z")), 2.0, 0.0)
      ))
    )
  }

  test("stop with given name") {
    assert(
      splitRoute(Seq(
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0),
        RoutePoint(Some(RouteStop("B")), 1.0, 0.0),
        RoutePoint(Some(RouteStop("Y")), 1.0, 0.0),
        RoutePoint(None, 3.0, 0.0),
        RoutePoint(None, 4.0, 0.0),
        RoutePoint(Some(RouteStop("Z")), 2.0, 0.0),
        RoutePoint(None, 5.0, 0.0),
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0)
      ), "A", "Z")

        === (Seq(
        RoutePoint(Some(RouteStop("A")), 0.0, 0.0),
        RoutePoint(Some(RouteStop("B")), 1.0, 0.0),
        RoutePoint(Some(RouteStop("Y")), 1.0, 0.0),
        RoutePoint(None, 3.0, 0.0),
        RoutePoint(None, 4.0, 0.0)
      ), Seq(
        RoutePoint(Some(RouteStop("Z")), 2.0, 0.0),
        RoutePoint(None, 5.0, 0.0)
      ))
    )
  }
}
