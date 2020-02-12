package net.kriomant.gortrans

import net.kriomant.gortrans.geometry.{Point, projectToLine}
import org.scalatest.FunSuite

class ProjectToLineTest extends FunSuite {
  test("start point") {
    assert(projectToLine(Point(1, 1), Point(1, 1), Point(1, 2)) === Point(1, 1))
  }

  test("end point") {
    assert(projectToLine(Point(1, 2), Point(1, 1), Point(1, 2)) === Point(1, 2))
  }

  test("middle point") {
    assert(projectToLine(Point(1, 2), Point(1, 1), Point(1, 3)) === Point(1, 2))
  }

  test("point above") {
    assert(projectToLine(Point(2, 2), Point(1, 1), Point(3, 1)) === Point(2, 1))
  }

  test("inclined line") {
    assert(projectToLine(Point(1, 3), Point(1, 1), Point(3, 3)) === Point(2, 2))
  }
}

