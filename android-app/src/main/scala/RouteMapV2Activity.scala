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
import com.actionbarsherlock.view.{MenuItem, Window}
import net.kriomant.gortrans.RouteMapLike.RouteInfo
import scala.collection.mutable
import com.google.android.gms.common.{ConnectionResult, GooglePlayServicesUtil}
import android.preference.PreferenceManager
import android.util.Log
import android.content.Intent

object RouteMapV2Activity {
	final val TAG = getClass.getName

	object StopMarkersState extends Enumeration {
		val Hidden, Small, Large = Value
	}

	private final val DIRECTION_SECTORS_NUMBER = 16
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
	var vehicleMarkers = Traversable[(Marker, VehicleInfo, Option[Double])]()

	var vehicleBitmaps = mutable.Map.empty[(VehicleType.Value, String, Option[Int]), Bitmap]

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		// Enable to show indeterminate progress indicator in activity header.
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
		setSupportProgressBarIndeterminateVisibility(false)

		setContentView(R.layout.route_map_v2)

		getSupportActionBar.setDisplayShowHomeEnabled(true)
		getSupportActionBar.setDisplayHomeAsUpEnabled(true)

		val googlePlayServicesStatus = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this)
		Log.d(TAG, "Google Play Services status: %d" format googlePlayServicesStatus)

		if (googlePlayServicesStatus != ConnectionResult.SUCCESS) {
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
				intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
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

				vehicleMarkers.foreach { case (marker, vehicleInfo, angle) =>
					marker.setIcon(getVehicleIcon(vehicleInfo, angle, camera.bearing))
				}
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

	override def onOptionsItemSelected(item: MenuItem): Boolean = {
		import RouteMapLike._

		item.getItemId match {
			case android.R.id.home => {
				val intent = getIntent
				val parentIntent = intent.getLongExtra(EXTRA_GROUP_ID, -1) match {
					case -1 =>
						val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
						val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
						val vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))
						RouteInfoActivity.createIntent(this, routeId, routeName, vehicleType)
					case groupId =>
						GroupsActivity.createIntent(this)
				}
				parentIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
				startActivity(parentIntent)
				true
			}
			case _ => super.onOptionsItemSelected(item)
		}
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
		def renderVehicle(color: Int, direction: Direction.Value, angle: Option[Float]): Bitmap = {
			val drawable = new VehicleMarker(getResources, angle, color)
			drawable.setBounds(0, 0, drawable.getIntrinsicWidth, drawable.getIntrinsicHeight)
			val bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth, drawable.getIntrinsicHeight, Bitmap.Config.ARGB_8888)
			val canvas = new Canvas(bitmap)
			drawable.draw(canvas)
			bitmap
		}

		val sectorAngle = 360.0 / DIRECTION_SECTORS_NUMBER
		(0 until DIRECTION_SECTORS_NUMBER) foreach { angleSector =>
			val angle = angleSector * sectorAngle
			vehicleBitmaps((routeInfo.vehicleType, routeInfo.name, Some(angleSector))) = renderVehicle(routeParams.color, Direction.Forward, Some(angle.toFloat))
		}
		vehicleBitmaps((routeInfo.vehicleType, routeInfo.name, None)) = renderVehicle(routeParams.color, Direction.Forward, None)
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
		vehicleMarkers.foreach(_._1.remove())
		vehicleMarkers = Seq()
	}

	def getVehicleIcon(info: VehicleInfo, angle: Option[Double], cameraBearing: Float): BitmapDescriptor = {
		val sectorAngle = 360.0 / DIRECTION_SECTORS_NUMBER
		val angleSector = angle map { a =>
			((a + cameraBearing + 360 + sectorAngle/2) % 360 / sectorAngle).toInt
		}
		Log.d(TAG, s"angle: $angle, bearing: $cameraBearing, sector: $angleSector")
		info.direction match {
			case Some(dir) => BitmapDescriptorFactory.fromBitmap(vehicleBitmaps(info.vehicleType, info.routeName, angleSector))
			case None => BitmapDescriptorFactory.fromResource(R.drawable.vehicle_stopped_marker)
		}
	}

	def setVehicles(vehicles: Seq[(VehicleInfo, Point, Option[Double], Int)]) {
		val cameraPosition = map.getCameraPosition

		vehicleMarkers.foreach(_._1.remove())

		vehicleMarkers = vehiclesData map { case (info, point, angle, baseColor) =>
			val bitmapDescriptor = getVehicleIcon(info, angle, cameraPosition.bearing)

			val options = new MarkerOptions()
				.icon(bitmapDescriptor)
				.position(new LatLng(point.y, point.x))
				.anchor(0.5f, 1)
				.title(getString(RouteMapLike.routeNameResourceByVehicleType(info.vehicleType), info.routeName))
				.snippet(formatVehicleSchedule(info))

			(map.addMarker(options), info, angle)
		}
	}

	def setLocationMarker(location: Location) {}
}