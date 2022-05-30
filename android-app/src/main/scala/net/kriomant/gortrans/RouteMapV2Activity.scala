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
import net.kriomant.gortrans.core.{Route, VehicleType, Direction}
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
{
	import RouteMapV2Activity._

	// Padding between route markers and map edge in pixels.
	final val ROUTE_PADDING = 10

	var map: GoogleMap = null
	var previousStopMarkersState: StopMarkersState.Value = StopMarkersState.Hidden

	var routeMarkers = mutable.Buffer[Polyline]()
	var stopMarkers = mutable.Buffer[Marker]()
	var smallStopMarkers = mutable.Buffer[Marker]()
	val vehicleMarkers = mutable.Map.empty[Marker, (VehicleInfo, Option[Double])]

	/** Vehicle marker for which info windows was shown last time. */
	var infoWindowMarker: Option[Marker] = None

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
			val infoWindowView = getLayoutInflater.inflate(R.layout.map_v2_vehicle_info_window, null, false)
			val titleView = infoWindowView.findViewById(R.id.marker_info_title).asInstanceOf[TextView]
			val scheduleView = infoWindowView.findViewById(R.id.marker_info_schedule).asInstanceOf[TextView]
			val scheduleNrView = infoWindowView.findViewById(R.id.marker_info_schedule_nr).asInstanceOf[TextView]
			val speedView = infoWindowView.findViewById(R.id.marker_info_speed).asInstanceOf[TextView]

			val stopWindowView = getLayoutInflater.inflate(R.layout.map_v2_stop_info_window, null, false)
			val stopNameView = stopWindowView.findViewById(R.id.stop_info_title).asInstanceOf[TextView]

			map.setInfoWindowAdapter(new InfoWindowAdapter {
				def getInfoWindow(marker: Marker): View = null
				def getInfoContents(marker: Marker): View = {
					vehicleMarkers.get(marker) match {
						case Some((info, angle)) =>
							val oldName = RouteListBaseActivity.routeRenames.get(info.vehicleType, info.routeName)
							titleView.setText(RouteListBaseActivity.getRouteTitle(RouteMapV2Activity.this, info.vehicleType, info.routeName))
							scheduleView.setText(formatVehicleSchedule(info))
							scheduleNrView.setText(getString(R.string.vehicle_schedule_number, info.scheduleNr.asInstanceOf[AnyRef]))
							speedView.setText(getString(R.string.vehicle_speed_format, info.speed.asInstanceOf[AnyRef]))

							infoWindowMarker = Some(marker)

							infoWindowView

						case None => // Stop marker
							stopNameView.setText(marker.getTitle)
							stopWindowView
					}
				}
			})
		}

		var previousCameraPosition = map.getCameraPosition
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

				if (previousCameraPosition.zoom != camera.zoom) {
					routeMarkers.foreach(_.setWidth(getRouteStrokeWidth(camera.zoom)))
				}

				if (previousCameraPosition.bearing != camera.bearing) {
					vehicleMarkers.foreach { case (marker, (vehicleInfo, angle)) =>
						marker.setIcon(getVehicleIcon(vehicleInfo, angle, camera.bearing))
					}
				}
			}
		})
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


	def getMapCameraPosition: RouteMapLike.MapCameraPosition = {
		val camera = map.getCameraPosition

		RouteMapLike.MapCameraPosition(
			latitude = camera.target.latitude,
			longitude = camera.target.longitude,
			bearing = camera.bearing,
			tilt = camera.tilt,
			zoom = camera.zoom
		)
	}

	def restoreMapCameraPosition(pos: RouteMapLike.MapCameraPosition) {
		val builder = new CameraPosition.Builder

		val position = builder
			.target(new LatLng(pos.latitude, pos.longitude))
			.bearing(pos.bearing)
			.tilt(pos.tilt)
			.zoom(pos.zoom)
			.build()

		map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
	}

	def createProcessIndicator() = new FragmentActionBarProcessIndicator(this)


	override def announceRoutes(routes: Seq[(Route, /*color*/ Int)]) {
		routes foreach { case (routeInfo, color) =>
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
				vehicleBitmaps((routeInfo.vehicleType, routeInfo.name, Some(angleSector))) = renderVehicle(color, Direction.Forward, Some(angle.toFloat))
			}
			vehicleBitmaps((routeInfo.vehicleType, routeInfo.name, None)) = renderVehicle(color, Direction.Forward, None)
		}
	}

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
		routeMarkers += map.addPolyline(new PolylineOptions()
			.addAll(routeParams.backwardRoutePoints.map(p => new LatLng(p.y, p.x)).asJava)
			.width(getRouteStrokeWidth(map.getCameraPosition.zoom))
			.geodesic(true)
		)
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
		infoWindowMarker = None
		vehicleMarkers.keys.foreach(_.remove())
		vehicleMarkers.clear()
	}

	def getVehicleIcon(info: VehicleInfo, angle: Option[Double], cameraBearing: Float): BitmapDescriptor = {
		val sectorAngle = 360.0 / DIRECTION_SECTORS_NUMBER
		val angleSector = angle map { a =>
			((a + cameraBearing + 360 + sectorAngle/2) % 360 / sectorAngle).toInt
		}
		info.direction match {
			case Some(dir) => BitmapDescriptorFactory.fromBitmap(vehicleBitmaps(info.vehicleType, info.routeName, angleSector))
			case None => BitmapDescriptorFactory.fromResource(R.drawable.vehicle_stopped_marker)
		}
	}

	def setVehicles(vehicles: Seq[(VehicleInfo, Point, Option[Double], Int)]) {
		val cameraPosition = map.getCameraPosition

		val showInfoFor = infoWindowMarker.filter(_.isInfoWindowShown).map { m =>
			val info = vehicleMarkers(m)._1
			(info.vehicleType, info.routeId, info.scheduleNr)
		}

		// Reuse already existing vehicle markers. Spare markers are not removed, but just hidden.
		vehicleMarkers.keys.zipAll(vehicles, null, null) foreach {
			case (marker, null) =>
				marker.setVisible(false)

			case (existingMarker, (info, point, angle, baseColor)) =>
				val bitmapDescriptor = getVehicleIcon(info, angle, cameraPosition.bearing)
				val marker = if (existingMarker == null) {
					val options = new MarkerOptions()
						.icon(bitmapDescriptor)
						.position(new LatLng(point.y, point.x))
						.anchor(0.5f, 1)
					map.addMarker(options)
				} else {
					val bitmapDescriptor = getVehicleIcon(info, angle, cameraPosition.bearing)
					existingMarker.setIcon(bitmapDescriptor)
					existingMarker.setPosition(new LatLng(point.y, point.x))
					existingMarker.setVisible(true)
					existingMarker
				}

				vehicleMarkers(marker) = (info, angle)

				if (showInfoFor.nonEmpty && showInfoFor == Some(info.vehicleType, info.routeId, info.scheduleNr)) {
					infoWindowMarker = Some(marker)
					marker.showInfoWindow()
				}
		}
	}

	def setLocationMarker(location: Location) {}
}