package net.kriomant.gortrans

import com.actionbarsherlock.app.SherlockFragmentActivity
import android.os.Bundle
import com.google.android.gms.maps.{CameraUpdateFactory, SupportMapFragment, GoogleMap}
import com.google.android.gms.maps.model._
import collection.JavaConverters.asJavaIterableConverter
import android.location.Location
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import net.kriomant.gortrans.geometry.Point
import net.kriomant.gortrans.parsing.VehicleInfo
import com.google.android.gms.maps.GoogleMap.OnCameraChangeListener
import android.graphics.{Canvas, Bitmap}
import net.kriomant.gortrans.core.Direction
import android.graphics.drawable.Drawable

object RouteMapV2Activity {
	final val TAG = getClass.getName

	object StopMarkersState extends Enumeration {
		val Hidden, Small, Large = Value
	}
}

class RouteMapV2Activity extends SherlockFragmentActivity
	with RouteMapLike
	with TypedActivity
{
	import RouteMapV2Activity._

	// Padding between route markers and map edge in pixels.
	final val ROUTE_PADDING = 10

	var map: GoogleMap = null
	var previousStopMarkersState: StopMarkersState.Value = StopMarkersState.Hidden

	var routeMarkers = Traversable[Polyline]()
	var stopMarkers = Traversable[Marker]()
	var smallStopMarkers = Traversable[Marker]()
	var vehicleMarkers = Traversable[Marker]()

	var vehicleUnknown: Drawable = null

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.route_map_v2)

		vehicleUnknown = getResources.getDrawable(R.drawable.vehicle_stopped_marker)

		val mapFragment = getSupportFragmentManager.findFragmentById(R.id.route_map_v2_view).asInstanceOf[SupportMapFragment]
		map = mapFragment.getMap
		// `map` may be null if Google Play services are not available or not updated.

		map.setOnCameraChangeListener(new OnCameraChangeListener {
			def onCameraChange(camera: CameraPosition) {
				val stopMarkersState =
					if (camera.zoom < RouteMapLike.ZOOM_WHOLE_ROUTE)
						StopMarkersState.Hidden
					else if (camera.zoom < RouteMapLike.ZOOM_WHOLE_ROUTE+2)
						StopMarkersState.Small
					else
						StopMarkersState.Large

				if (stopMarkersState != previousStopMarkersState) {
					stopMarkersState match {
						case StopMarkersState.Hidden =>
							stopMarkers      foreach { _.setVisible(false) }
							smallStopMarkers foreach { _.setVisible(false) }
						case StopMarkersState.Small =>
							stopMarkers      foreach { _.setVisible(false) }
							smallStopMarkers foreach { _.setVisible(true) }
						case StopMarkersState.Large =>
							stopMarkers      foreach { _.setVisible(true) }
							smallStopMarkers foreach { _.setVisible(false) }
					}
					previousStopMarkersState = stopMarkersState
				}

				// TODO: optimize.
				routeMarkers.foreach(_.setWidth(getRouteStrokeWidth(camera.zoom)))
			}
		})

		if (! hasOldState) {
			val mapView = mapFragment.getView
			mapView.getViewTreeObserver.addOnGlobalLayoutListener(new OnGlobalLayoutListener {
				def onGlobalLayout() {
					mapView.getViewTreeObserver.removeGlobalOnLayoutListener(this)

					showWholeRoutes()
				}
			})
		}
	}

	def createProcessIndicator() = new FragmentActionBarProcessIndicator(this)

	def createConstantOverlays() {
		// Display stop name next to one of folded stops.
		/*val stopNames = routes.values map(_.stopNames) reduceLeftOption  (_ | _) getOrElse Set()
		val stopOverlayManager = routeStopNameOverlayManager
		val stopNameOverlays: Iterator[Overlay] = stopNames.iterator map { case (pos, name) =>
			new stopOverlayManager.RouteStopNameOverlay(name, routePointToGeoPoint(pos))
		}*/

		val stops = routes.values map (_.stops) reduceLeftOption (_ | _) getOrElse Set()
		def createStopMarker(point: Point, name: String, iconRes: Int): Marker = {
			val marker = map.addMarker(new MarkerOptions()
				.icon(BitmapDescriptorFactory.fromResource(iconRes))
				.anchor(0.5f, 0.5f)
				.position(new LatLng(point.y, point.x))
				.title(name)
				.visible(false)
			)
			// `MarkerOptions.visible` has no effect (http://code.google.com/p/gmaps-api-issues/issues/detail?id=4677),
			// so hide marker manually after creation. Call to 'visible' is left so it will work (and possible)
			// avoid flicker when bug is fixed.
			marker.setVisible(false)
			marker
		}
		stopMarkers.foreach(_.remove())
		stopMarkers = stops.toSeq.map { case (point, name) => createStopMarker(point, name, R.drawable.route_stop_marker) }
		smallStopMarkers.foreach(_.remove())
		smallStopMarkers = stops.toSeq.map { case (point, name) => createStopMarker(point, name, R.drawable.route_stop_marker_small) }

		routeMarkers.foreach(_.remove())
		routeMarkers = routes.values map { route =>
			map.addPolyline(new PolylineOptions()
				.addAll(route.forwardRoutePoints.map(p => new LatLng(p.y, p.x)).asJava)
				.width(getRouteStrokeWidth(map.getCameraPosition.zoom))
				.geodesic(true)
			)
		}

		/*constantOverlays.clear()
		constantOverlays ++= routeOverlays
		constantOverlays ++= stopOverlays
		constantOverlays ++= stopNameOverlays*/
	}

	def navigateTo(left: Double, top: Double, right: Double, bottom: Double) {
		val motion = CameraUpdateFactory.newLatLngBounds(
			new LatLngBounds(new LatLng(bottom, left), new LatLng(top, right)),
			ROUTE_PADDING
		)
		map.animateCamera(motion)
	}

	def navigateTo(latitude: Double, longitude: Double) {
		val motion = CameraUpdateFactory.newLatLng(new LatLng(latitude, longitude))
		map.animateCamera(motion)
	}

	def setTitle(title: String) {}

	def startBackgroundProcessIndication() {}

	def stopBackgroundProcessIndication() {}

	def clearVehicleMarkers() {
		vehicleMarkers.foreach(_.remove())
		vehicleMarkers = Seq()
	}

	def setVehicles(vehicles: Seq[(VehicleInfo, Point, Option[Double], Int)]) {
	}

	def updateOverlays() {
		val markerOptions = vehiclesData map { case (info, point, angle, baseColor) =>
			val bitmap = android_utils.measure(TAG, "Render vehicle marker") {
				val drawable = info.direction match {
					case Some(dir) =>
						val color = dir match {
							case Direction.Forward => baseColor
							case Direction.Backward => baseColor
						}
						new VehicleMarker(getResources, angle.map(_.toFloat), color)

					case None => vehicleUnknown
				}
				drawable.setBounds(0, 0, drawable.getIntrinsicWidth, drawable.getIntrinsicHeight)
				val bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth, drawable.getIntrinsicHeight, Bitmap.Config.ARGB_8888)
				val canvas = new Canvas(bitmap)
				drawable.draw(canvas)
				bitmap
			}

			new MarkerOptions()
				.icon(BitmapDescriptorFactory.fromBitmap(bitmap))
				.position(new LatLng(point.y, point.x))
				.anchor(0.5f, 1)
		}

		android_utils.measure(TAG, "Remove %d and add %d vehicle marker" format (vehicleMarkers.size, markerOptions.size)) {
			vehicleMarkers.foreach(_.remove())
			vehicleMarkers = markerOptions map { options => map.addMarker(options) } toList
		}
	}

	def setLocationMarker(location: Location) {}
}