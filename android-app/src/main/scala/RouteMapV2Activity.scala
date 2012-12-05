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

class RouteMapV2Activity extends SherlockFragmentActivity
	with RouteMapLike
	with TypedActivity
{
	// Padding between route markers and map edge in pixels.
	final val ROUTE_PADDING = 10

	var map: GoogleMap = null
	var previousZoom: Float = 0

	var routeMarkers = Traversable[Polyline]()
	var stopMarkers = Traversable[Marker]()

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.route_map_v2)

		val mapFragment = getSupportFragmentManager.findFragmentById(R.id.route_map_v2_view).asInstanceOf[SupportMapFragment]
		map = mapFragment.getMap
		// `map` may be null if Google Play services are not available or not updated.

		map.setOnCameraChangeListener(new OnCameraChangeListener {
			def onCameraChange(camera: CameraPosition) {

				if ((previousZoom >= RouteMapLike.ZOOM_WHOLE_ROUTE+1) != (camera.zoom >= RouteMapLike.ZOOM_WHOLE_ROUTE+1)) {
					stopMarkers.foreach(_.setVisible(camera.zoom >= RouteMapLike.ZOOM_WHOLE_ROUTE+1))
				}

				// TODO: optimize.
				routeMarkers.foreach(_.setWidth(getRouteStrokeWidth(camera.zoom)))

				previousZoom = camera.zoom
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

		previousZoom = map.getCameraPosition.zoom

		val stops = routes.values map (_.stops) reduceLeftOption (_ | _) getOrElse Set()
		/*val stopOverlays: Iterator[Overlay] = stops.iterator map { case (point, name) =>
			new RouteStopOverlay(getResources, routePointToGeoPoint(point))
		}*/
		stopMarkers.foreach(_.remove())
		stopMarkers = stops.toSeq.map { case (point, name) =>
			map.addMarker(new MarkerOptions()
				.icon(BitmapDescriptorFactory.fromResource(R.drawable.route_stop_marker))
				.anchor(0.5f, 0.5f)
				.position(new LatLng(point.y, point.x))
				.title(name)
				.visible(map.getCameraPosition.zoom >= RouteMapLike.ZOOM_WHOLE_ROUTE+1)
			)
		}

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

	def updateOverlays() {}

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

	def clearVehicleMarkers() {}

	def setVehicles(vehicles: Seq[(VehicleInfo, Point, Option[Double], Int)]) {}

	def setLocationMarker(location: Location) {}
}