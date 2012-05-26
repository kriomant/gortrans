package net.kriomant.gortrans

object geometry {

	case class Point(x: Double, y: Double) {
		def *(other: Point) = x * other.x + y * other.y
		def *(scale: Double) = Point(scale * x, scale * y)
		def -(other: Point) = Point(x-other.x, y-other.y)
		def +(other: Point) = Point(x+other.x, y+other.y)

		def distanceTo(other: Point) = math.sqrt((x-other.x)*(x-other.x) + (y-other.y)*(y-other.y))
	}

	def projectToLine(point: Point, start: Point, end: Point): Point = {
		require(start.x != end.x || start.y != end.y)

		// Line equation: u = start + (end - start) * t, t=[0,1]
		// Let 'p' = projection of 'point' to line, p = start + (end - start) * pt
		// Then (p-point) is orthogonal to (start-end) => (p-point) * (start-end) = 0 =>
		// (start + (end-start) * pt - point) * (end-start) = 0 =>
		//
		// (start-point)*(end-start) + (end-start)^2 * pt = 0 =>
		// pt = (start-point)*(end-start) / -(end-start)^2

		val pt = (start-point)*(end-start) / -((end-start)*(end-start))
		start + (end-start) * pt
	}

	def closestSegmentPoint(point: Point, start: Point, end: Point): Point = {
		// Find whether projection of point to line containing segment belongs to segment.
		// Get two vectors: from segment start to point and from segment start to segment end.
		val (spx, spy) = (point.x - start.x, point.y - start.y)
		val (sex, sey) = (end.x - start.x, end.y - start.y)
		// Check angle between vectors: if it is more than 90 degrees, then projection lies
		// before segment start.
		if (spx * sex + spy * sey <= 0) {
			start
		} else {
			val (pex, pey) = (end.x - point.x, end.y - point.y)
			if (pex * sex + pey * sey <= 0)
				end
			else
				projectToLine(point, start, end)
		}
	}

}
