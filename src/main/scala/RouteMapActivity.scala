package net.kriomant.gortrans

import android.util.Log
import android.content.res.Resources
import android.graphics._
import drawable.Drawable
import java.lang.Math
import android.widget.CompoundButton.OnCheckedChangeListener
import android.os.{Handler, Bundle}
import com.google.android.maps._
import net.kriomant.gortrans.parsing.{VehicleInfo, RoutePoint}
import android.widget.{ToggleButton, Toast, CompoundButton}
import net.kriomant.gortrans.Client.RouteInfoRequest
import android.content.{Context, Intent}
import android.location.{Location, LocationListener, LocationManager}
import android.view.View
import android.view.View.OnClickListener
import com.actionbarsherlock.app.SherlockMapActivity
import com.actionbarsherlock.view.{MenuItem, Window}
import net.kriomant.gortrans.core.{Route, DirectionsEx, Direction, VehicleType, foldRoute}

object RouteMapActivity {
	private[this] val CLASS_NAME = classOf[RouteMapActivity].getName
	final val TAG = CLASS_NAME

	private final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	private final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	private final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"

	def createIntent(caller: Context, routeId: String, routeName: String, vehicleType: VehicleType.Value): Intent = {
		val intent = new Intent(caller, classOf[RouteMapActivity])
		intent.putExtra(EXTRA_ROUTE_ID, routeId)
		intent.putExtra(EXTRA_ROUTE_NAME, routeName)
		intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent
	}
}

class RouteMapActivity extends SherlockMapActivity
	with TypedActivity
	with TrackLocation
	with MapShortcutTarget
	with VehiclesWatcher {

	import RouteMapActivity._

	private[this] var mapView: MapView = null
  private[this] var trackVehiclesToggle: ToggleButton = null
  val handler = new Handler

	private[this] var dataManager: DataManager = null

  var routeId: String = null
  var routeName: String = null
  var vehicleType: VehicleType.Value = null
  
  var routeOverlay: Overlay = null
  var stopOverlays: Seq[Overlay] = null
	var stopNameOverlays: Seq[Overlay] = null
  var vehiclesOverlay: ItemizedOverlay[OverlayItem] = null
	var locationOverlay: Overlay = null

  var updatingVehiclesLocationIsOn: Boolean = false

  var routeStops: Seq[RoutePoint] = null

	def isRouteDisplayed = false
	override def isLocationDisplayed = false

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		dataManager = getApplication.asInstanceOf[CustomApplication].dataManager

    // Enable to show indeterminate progress indicator in activity header.
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
		setSupportProgressBarIndeterminateVisibility(false)

		setContentView(R.layout.route_map)
		mapView = findView(TR.route_map_view)
		mapView.setBuiltInZoomControls(true)
		mapView.setSatellite(false)

    trackVehiclesToggle = findView(TR.track_vehicles)
    trackVehiclesToggle.setOnCheckedChangeListener(new OnCheckedChangeListener {
      def onCheckedChanged(button: CompoundButton, checked: Boolean) {
        if (checked) {
          updatingVehiclesLocationIsOn = true
          startUpdatingVehiclesLocation()
        } else {
          stopUpdatingVehiclesLocation()
          updatingVehiclesLocationIsOn = false
        }
      }
    })
    trackVehiclesToggle.setChecked(updatingVehiclesLocationIsOn)

		val gpsToggle = findView(TR.toggle_gps)
		gpsToggle.setOnCheckedChangeListener(new OnCheckedChangeListener {
			def onCheckedChanged(button: CompoundButton, checked: Boolean) {
				setGpsEnabled(checked)
			}
		})

		val showMyLocationButton = findView(TR.show_my_location)
		showMyLocationButton.setOnClickListener(new OnClickListener {
			def onClick(view: View) {
				val location = currentLocation
				val ctrl = mapView.getController
				ctrl.animateTo(new GeoPoint((location.getLatitude * 1e6).toInt, (location.getLongitude * 1e6).toInt))
			}
		})
		onNewIntent(getIntent)
	}

	override def onNewIntent(intent: Intent) {
		// Get route reference.
		routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

		Log.d(TAG, "New intent received, route ID: %s" format routeId)

		// Set title.
		val routeNameFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_n,
			VehicleType.TrolleyBus -> R.string.trolleybus_n,
			VehicleType.TramWay -> R.string.tramway_n,
			VehicleType.MiniBus -> R.string.minibus_n
		).mapValues(getString)

		val actionBar = getSupportActionBar
		actionBar.setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))
		actionBar.setDisplayHomeAsUpEnabled(true)

		// Load route details.
		val routePoints = dataManager.getRoutePoints(vehicleType, routeId, routeName)
		routeStops = routePoints filter(_.stop.isDefined)
		val foldedRoute = foldRoute[RoutePoint](routeStops, _.stop.get.name)

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
		routeOverlay = new RouteOverlay(getResources, routeGeoPoints)
		stopOverlays = routeStops map { p =>
			new RouteStopOverlay(
				getResources,
				new GeoPoint((p.latitude * 1e6).toInt, (p.longitude * 1e6).toInt)
			)
		}
		// Display stop name next to one of folded stops.
		stopNameOverlays = foldedRoute map { p =>
			// Find stop which is to the east of another.
			val stop = (p.forward, p.backward) match {
				case (Some(f), Some(b)) => if (f.longitude > b.longitude) f else b
				case (Some(f), None) => f
				case (None, Some(b)) => b
			}
			new RouteStopNameOverlay(
				p.name,
				new GeoPoint((stop.latitude * 1e6).toInt, (stop.longitude * 1e6).toInt)
			)
		}
    updateOverlays()
	}

  def updateOverlays() {
    Log.d("RouteMapActivity", "updateOverlays")
    val overlays = mapView.getOverlays
    overlays.clear()
    overlays.add(routeOverlay)
    for (o <- stopOverlays)
      overlays.add(o)
	  for (o <- stopNameOverlays)
		  overlays.add(o)
    if (vehiclesOverlay != null)
      overlays.add(vehiclesOverlay)
	  if (locationOverlay != null)
		  overlays.add(locationOverlay)
    mapView.postInvalidate()
  }

  override def onResume() {
    super.onResume()

    if (updatingVehiclesLocationIsOn)
      startUpdatingVehiclesLocation()
  }

  override def onPause() {
    if (updatingVehiclesLocationIsOn)
      stopUpdatingVehiclesLocation()

    super.onPause()
  }

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case android.R.id.home => {
			val intent = RouteInfoActivity.createIntent(this, routeId, routeName, vehicleType)
			startActivity(intent)
			true
		}
		case _ => super.onOptionsItemSelected(item)
	}

	def onLocationUpdated(location: Location) {
		Log.d("RouteMapActivity", "Location updated: %s" format location)
		if (location != null) {
			val point = new GeoPoint((location.getLatitude * 1e6).toInt, (location.getLongitude * 1e6).toInt)
			val drawable = getResources.getDrawable(R.drawable.location_marker)
			locationOverlay = new MarkerOverlay(drawable, point, new PointF(0.5f, 0.5f))
		} else {
			locationOverlay = null
		}
		updateOverlays()
		
		findView(TR.show_my_location).setVisibility(if (location != null) View.VISIBLE else View.INVISIBLE)
	}

	def getShortcutNameAndIcon: (String, Int) = {
		val vehicleShortName = getString(vehicleType match {
			case VehicleType.Bus => R.string.bus_short
			case VehicleType.TrolleyBus => R.string.trolleybus_short
			case VehicleType.TramWay => R.string.tramway_short
			case VehicleType.MiniBus => R.string.minibus_short
		})
		val name = getString(R.string.route_map_shortcut_format, vehicleShortName, routeName)
		(name, R.drawable.route_map)
	}


	def getVehiclesToTrack = (vehicleType, routeId, routeName)

	def onVehiclesLocationUpdateStarted() {
		setSupportProgressBarIndeterminateVisibility(true)
	}

	def onVehiclesLocationUpdateCancelled() {
		setSupportProgressBarIndeterminateVisibility(false)

		vehiclesOverlay = null
		updateOverlays()
	}

	def onVehiclesLocationUpdated(vehicles: Seq[VehicleInfo]) {
		setSupportProgressBarIndeterminateVisibility(false)

		vehiclesOverlay = new VehiclesOverlay(getResources, vehicles)
		updateOverlays()
	}
}

class MarkerOverlay(drawable: Drawable, location: GeoPoint, anchorPosition: PointF) extends Overlay {
	val offset = new Point(
		-(drawable.getIntrinsicWidth  * anchorPosition.x).toInt,
		-(drawable.getIntrinsicHeight * anchorPosition.y).toInt
	)

	override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
		if (! shadow) {
			val point = new Point
			view.getProjection.toPixels(location, point)

			drawable.setBounds(
				point.x + offset.x,
				point.y + offset.y,
				point.x + drawable.getIntrinsicWidth + offset.x,
				point.y + drawable.getIntrinsicHeight + offset.y
			)
			drawable.draw(canvas)
		}
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

class RouteStopNameOverlay(name: String, geoPoint: GeoPoint) extends Overlay {
	final val X_OFFSET = 15
	final val Y_OFFSET = 5

	override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
		if (! shadow) {
			val point = new Point
			view.getProjection.toPixels(geoPoint, point)

			val pen = new Paint()
			pen.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD))
			pen.setTextSize(20.0f)
			canvas.drawText(name, point.x + X_OFFSET, point.y + Y_OFFSET, pen)
		}
	}
}

class VehiclesOverlay(resources: Resources, infos: Seq[VehicleInfo]) extends ItemizedOverlay[OverlayItem](null) {
  def boundCenterBottom = ItemizedOverlayBridge.boundCenterBottom_(_)

  val vehicle_forward = boundCenterBottom(resources.getDrawable(R.drawable.vehicle_forward_marker))
  val vehicle_backward = boundCenterBottom(resources.getDrawable(R.drawable.vehicle_backward_marker))
  val vehicle_stopped = boundCenterBottom(resources.getDrawable(R.drawable.vehicle_stopped_marker))

  populate()

  def size = infos.length

  def createItem(pos: Int) = {
    Log.d("RouteMapActivity", "Create vehicle overlay #%d" format pos)
    val info = infos(pos)
    val point = new GeoPoint((info.latitude*1e6).toInt, (info.longitude*1e6).toInt)
    val item = new OverlayItem(point, null, null)
    item.setMarker(info.direction match {
      case Some(Direction.Forward) => vehicle_forward
      case Some(Direction.Backward) => vehicle_backward
      case None => vehicle_stopped
    })
    item
  }
}
