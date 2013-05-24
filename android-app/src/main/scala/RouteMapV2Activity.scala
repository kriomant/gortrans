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
import com.google.android.gms.maps.GoogleMap.{InfoWindowAdapter, OnCameraChangeListener}
import android.graphics.{Color, LightingColorFilter, Canvas, Bitmap}
import net.kriomant.gortrans.core.{VehicleType, Direction}
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.{Toast, TextView}
import com.actionbarsherlock.view.Window
import net.kriomant.gortrans.RouteMapLike.RouteInfo
import scala.collection.mutable
import com.google.android.gms.common.{ConnectionResult, GooglePlayServicesUtil}
import android.preference.PreferenceManager
import android.util.Log

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

	var routeMarkers = mutable.Buffer[Polyline]()
	var stopMarkers = mutable.Buffer[Marker]()
	var smallStopMarkers = mutable.Buffer[Marker]()
	var vehicleMarkers = Traversable[Marker]()

	var vehicleBitmaps = mutable.Map.empty[(VehicleType.Value, String, Direction.Value), Bitmap]

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		// Enable to show indeterminate progress indicator in activity header.
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
		setSupportProgressBarIndeterminateVisibility(false)

		setContentView(R.layout.route_map_v2)

		val googlePlayServicesStatus = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this)
		Log.d(TAG, "Google Play Services status: %d" format googlePlayServicesStatus)

		if (googlePlayServicesStatus == ConnectionResult.SUCCESS) {
			if (! GooglePlayServicesUtil.isUserRecoverableError(googlePlayServicesStatus)) {
				Log.e(TAG, "Non-recoverable Google Play Services error, disable new map")

				// Show error message.
				Toast.makeText(this, R.string.new_map_not_supported, Toast.LENGTH_LONG).show()

				// Turn off new maps.
				val prefs = PreferenceManager.getDefaultSharedPreferences(this)
				prefs.edit().putBoolean(SettingsActivity.KEY_USE_NEW_MAP, false).commit()

				// Redirect to old maps.
				val intent = getIntent
				intent.setClass(this, classOf[RouteMapActivity])
				startActivity(intent)

				finish()
			}

			// If error is recoverable, do nothing. Corresponding message with action button
			// will be shown instead of map by Google Play Services library.

			return
		}

		val mapFragment = getSupportFragmentManager.findFragmentById(R.id.route_map_v2_view).asInstanceOf[SupportMapFragment]
		map = mapFragment.getMap
		// `map` may be null if Google Play services are not available or not updated.

		// Default marker info window shows snippet text all in one line.
		// Use own layout in order to show multi-line schedule.
		locally {
			val infoWindowView = getLayoutInflater.inflate(R.layout.map_v2_info_window, null, false)
			val titleView = infoWindowView.findViewById(R.id.marker_info_title).asInstanceOf[TextView]
			val scheduleView = infoWindowView.findViewById(R.id.marker_info_schedule).asInstanceOf[TextView]
			map.setInfoWindowAdapter(new InfoWindowAdapter {
				def getInfoWindow(marker: Marker): View = null
				def getInfoContents(marker: Marker): View = {
					titleView.setText(marker.getTitle)
					scheduleView.setText(marker.getSnippet)
					infoWindowView
				}
			})
		}

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

	override def isInitialized = {
		getSupportFragmentManager.findFragmentById(R.id.route_map_v2_view).asInstanceOf[SupportMapFragment].getMap != null
	}

	def createProcessIndicator() = new FragmentActionBarProcessIndicator(this)

	def createRouteOverlays(routeInfo: core.Route, routeParams: RouteInfo) {
		// Display stop name next to one of folded stops.
		/*val stopNames = routes.values map(_.stopNames) reduceLeftOption  (_ | _) getOrElse Set()
		val stopOverlayManager = routeStopNameOverlayManager
		val stopNameOverlays: Iterator[Overlay] = stopNames.iterator map { case (pos, name) =>
			new stopOverlayManager.RouteStopNameOverlay(name, routePointToGeoPoint(pos))
		}*/

		val knownStops = routes.values map (_.stops) reduceLeftOption (_ | _) getOrElse Set()
		val newStops = routeParams.stops &~ knownStops
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
		stopMarkers ++= newStops.toSeq.map { case (point, name) => createStopMarker(point, name, R.drawable.route_stop_marker) }
		smallStopMarkers ++= newStops.toSeq.map { case (point, name) => createStopMarker(point, name, R.drawable.route_stop_marker_small) }

		routeMarkers += map.addPolyline(new PolylineOptions()
			.addAll(routeParams.forwardRoutePoints.map(p => new LatLng(p.y, p.x)).asJava)
			.width(getRouteStrokeWidth(map.getCameraPosition.zoom))
			.geodesic(true)
		)

		// Create vehicle marker bitmap for route.
		def renderVehicle(color: Int, direction: Direction.Value): Bitmap = {
			val drawable = direction match {
				case Direction.Forward => new VehicleMarker(getResources, None, color)
				case Direction.Backward => {
					val dr = getResources.getDrawable(R.drawable.vehicle_marker_back)
					dr.setColorFilter(new LightingColorFilter(Color.BLACK, color))
					dr
				}
			}
			drawable.setBounds(0, 0, drawable.getIntrinsicWidth, drawable.getIntrinsicHeight)
			val bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth, drawable.getIntrinsicHeight, Bitmap.Config.ARGB_8888)
			val canvas = new Canvas(bitmap)
			drawable.draw(canvas)
			bitmap
		}

		vehicleBitmaps((routeInfo.vehicleType, routeInfo.name, Direction.Forward)) = renderVehicle(routeParams.color, Direction.Forward)
		vehicleBitmaps((routeInfo.vehicleType, routeInfo.name, Direction.Backward)) = renderVehicle(routeParams.color, Direction.Backward)
	}

	def removeAllRouteOverlays() {
		stopMarkers.foreach(_.remove())
		stopMarkers.clear()

		smallStopMarkers.foreach(_.remove())
		smallStopMarkers.clear()

		routeMarkers.foreach(_.remove())
		routeMarkers.clear()
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

	def setTitle(title: String) {
		getSupportActionBar.setTitle(title)
	}

	def startBackgroundProcessIndication() {
		setSupportProgressBarIndeterminateVisibility(true)
	}

	def stopBackgroundProcessIndication() {
		setSupportProgressBarIndeterminateVisibility(true)
	}

	def clearVehicleMarkers() {
		vehicleMarkers.foreach(_.remove())
		vehicleMarkers = Seq()
	}

	def setVehicles(vehicles: Seq[(VehicleInfo, Point, Option[Double], Int)]) {
		val markerOptions = vehiclesData map { case (info, point, angle, baseColor) =>
			val bitmapDescriptor = info.direction match {
				case Some(dir) => BitmapDescriptorFactory.fromBitmap(vehicleBitmaps(info.vehicleType, info.routeName, dir))
				case None => BitmapDescriptorFactory.fromResource(R.drawable.vehicle_stopped_marker)
			}

			new MarkerOptions()
				.icon(bitmapDescriptor)
				.position(new LatLng(point.y, point.x))
				.anchor(0.5f, 1)
				.title(getString(RouteMapLike.routeNameResourceByVehicleType(info.vehicleType), info.routeName))
				.snippet(formatVehicleSchedule(info))
		}

		android_utils.measure(TAG, "Remove %d and add %d vehicle marker" format (vehicleMarkers.size, markerOptions.size)) {
			vehicleMarkers.foreach(_.remove())
			vehicleMarkers = markerOptions map { options => map.addMarker(options) } toList
		}
	}

	def setLocationMarker(location: Location) {}
}