package net.kriomant.gortrans

import android.os.Bundle
import net.kriomant.gortrans.core.VehicleType
import android.util.Log
import android.content.Intent
import com.google.android.maps.{GeoPoint, Overlay, MapView, MapActivity}
import android.content.res.Resources
import net.kriomant.gortrans.parsing.RoutePoint
import android.graphics._
import java.lang.Math

object RouteMapActivity {
	private[this] val CLASS_NAME = classOf[RouteMapActivity].getName
	final val TAG = CLASS_NAME

	final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
}

class RouteMapActivity extends MapActivity with TypedActivity {
	import RouteMapActivity._

	private[this] var mapView: MapView = null

	def isRouteDisplayed = false
	override def isLocationDisplayed = false

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		setContentView(R.layout.route_map)
		mapView = findView(TR.route_map_view)
		mapView.setBuiltInZoomControls(true)
		mapView.setSatellite(false)

		onNewIntent(getIntent)
	}

	override def onNewIntent(intent: Intent) {
		// Get route reference.
		val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		val vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

		Log.d(TAG, "New intent received, route ID: %s" format routeId)

		// Set title.
		val routeNameFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_route,
			VehicleType.TrolleyBus -> R.string.trolleybus_route,
			VehicleType.TramWay -> R.string.tramway_route,
			VehicleType.MiniBus -> R.string.minibus_route
		).mapValues(getString)
		setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))

		// Load route details.
		val routePoints = DataManager.getRoutePoints(vehicleType, routeId)(this)
		val routeStops = routePoints filter(_.stop.isDefined)

		// Calculate rectangle (and it's center) containing whole route.
		val top = routeStops.map(_.latitude).min
		val left = routeStops.map(_.longitude).min
		val bottom = routeStops.map(_.latitude).max
		val right = routeStops.map(_.longitude).max
		val longitude = (left + right) / 2
		val latitude = (top + bottom) / 2

		def routePointToGeoPoint(p: RoutePoint): GeoPoint =
			new GeoPoint((p.latitude * 1e6).toInt, (p.longitude * 1e6).toInt)

		// Navigate to show full route.
		val ctrl = mapView.getController
		ctrl.animateTo(new GeoPoint((latitude * 1e6).toInt, (longitude * 1e6).toInt))
		ctrl.zoomToSpan(((bottom - top) * 1e6).toInt, ((right - left) * 1e6).toInt)

		// Add route markers.
		val routeGeoPoints = routePoints map routePointToGeoPoint
		val overlays = mapView.getOverlays
		overlays.clear()
		overlays.add(new RouteOverlay(getResources, routeGeoPoints))
		val markers = routeStops map { p =>
			new RouteStopOverlay(getResources, new GeoPoint((p.latitude * 1e6).toInt, (p.longitude * 1e6).toInt))
		}
		for (m <- markers)
			overlays.add(m)
		mapView.postInvalidate()
	}
}

class RouteOverlay(resources: Resources, geoPoints: Seq[GeoPoint]) extends Overlay {
	final val PHYSICAL_ROUTE_STROKE_WIDTH: Float = 3 // meters
	final val MIN_ROUTE_STROKE_WIDTH: Float = 2 // pixels

	// Place here to avoid path allocation on each drawing.
	val path = new Path
	path.incReserve(geoPoints.length+1)

	val paint = new Paint
	paint.setStyle(Paint.Style.STROKE)

	override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
		if (! shadow) {
			val point = new Point

			path.rewind()

			view.getProjection.toPixels(geoPoints.last, point)
			path.moveTo(point.x, point.y)
			
			for (gp <- geoPoints) {
				view.getProjection.toPixels(gp, point)
				path.lineTo(point.x, point.y)
			}

			// From getZoomLevel documentation: "At zoom level 1 (fully zoomed out), the equator of the
			// earth is 256 pixels long. Each successive zoom level is magnified by a factor of 2."
			// We want to fix physical width of route stroke (in meters), but make it still visible
			// on zoom level 1 by limiting minimum width in pixels.
			val metersPerPixel = (40e6 / (128 * Math.pow(2, view.getZoomLevel))).toFloat
			val strokeWidth = Math.max(PHYSICAL_ROUTE_STROKE_WIDTH / metersPerPixel, MIN_ROUTE_STROKE_WIDTH)
			paint.setStrokeWidth(strokeWidth)

			canvas.drawPath(path, paint)
		}
	}
}
class RouteStopOverlay(resources: Resources, geoPoint: GeoPoint) extends Overlay {
	val img = BitmapFactory.decodeResource(resources, R.drawable.route_stop_marker)

	override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
		if (! shadow) {
			val point = new Point
			view.getProjection.toPixels(geoPoint, point)

			canvas.drawBitmap(img, point.x - img.getWidth/2, point.y - img.getHeight/2, null)
		}
	}
}
