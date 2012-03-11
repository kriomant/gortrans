package net.kriomant.gortrans

import android.util.Log
import android.content.Intent
import android.content.res.Resources
import android.graphics._
import java.lang.Math
import android.widget.CompoundButton.OnCheckedChangeListener
import android.os.{Handler, Bundle}
import com.google.android.maps._
import net.kriomant.gortrans.parsing.{VehicleInfo, RoutePoint}
import android.widget.{ToggleButton, Toast, CompoundButton}
import android.view.Window
import net.kriomant.gortrans.Client.RouteInfoRequest
import net.kriomant.gortrans.core.{DirectionsEx, Direction, VehicleType}

object RouteMapActivity {
	private[this] val CLASS_NAME = classOf[RouteMapActivity].getName
	final val TAG = CLASS_NAME

	final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
}

class RouteMapActivity extends MapActivity with TypedActivity {
	import RouteMapActivity._

  private[this] final val VEHICLES_LOCATION_UPDATE_PERIOD = 20000 /* ms */
  
	private[this] var mapView: MapView = null
  private[this] var trackVehiclesToggle: ToggleButton = null
  private[this] val handler = new Handler

	private[this] var dataManager: DataManager = null
	private[this] val client = new Client

  var routeId: String = null
  var routeName: String = null
  var vehicleType: VehicleType.Value = null
  
  var routeOverlay: Overlay = null
  var stopOverlays: Seq[Overlay] = null
  var vehiclesOverlay: ItemizedOverlay[OverlayItem] = null

  var updatingVehiclesLocationIsOn: Boolean = false

  var routeStops: Seq[RoutePoint] = null

  private[this] final val updateVehiclesLocationRunnable = new Runnable {
    def run() {
      updateVehiclesLocation()
      handler.postDelayed(this, VEHICLES_LOCATION_UPDATE_PERIOD)
    }
  }

	def isRouteDisplayed = false
	override def isLocationDisplayed = false

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		dataManager = getApplication.asInstanceOf[CustomApplication].dataManager

    // Enable to show indeterminate progress indicator in activity header.
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)

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
			VehicleType.Bus -> R.string.bus_route,
			VehicleType.TrolleyBus -> R.string.trolleybus_route,
			VehicleType.TramWay -> R.string.tramway_route,
			VehicleType.MiniBus -> R.string.minibus_route
		).mapValues(getString)
		setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))

		// Load route details.
		val routePoints = dataManager.getRoutePoints(vehicleType, routeId, routeName)
		routeStops = routePoints filter(_.stop.isDefined)

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
			new RouteStopOverlay(getResources, new GeoPoint((p.latitude * 1e6).toInt, (p.longitude * 1e6).toInt))
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
    if (vehiclesOverlay != null)
      overlays.add(vehiclesOverlay)
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

  def startUpdatingVehiclesLocation() {
    handler.post(updateVehiclesLocationRunnable)

    Toast.makeText(this, R.string.vehicles_tracking_turned_on, Toast.LENGTH_SHORT).show()
  }

  def stopUpdatingVehiclesLocation() {
    handler.removeCallbacks(updateVehiclesLocationRunnable)
    vehiclesOverlay = null
    updateOverlays()

    Toast.makeText(this, R.string.vehicles_tracking_turned_off, Toast.LENGTH_SHORT).show()
  }

  def updateVehiclesLocation() {
    val task = new TrackVehiclesTask
    task.execute(Unit)
  }

  class TrackVehiclesTask extends AsyncTaskBridge[Unit, Unit, Seq[VehicleInfo]] {
    override def onPreExecute() {
      setProgressBarIndeterminateVisibility(true)
    }

    override def doInBackgroundBridge(param: Unit): Seq[VehicleInfo] = {
      val request = new RouteInfoRequest(vehicleType, routeId, routeName, DirectionsEx.Both)
      val json = client.getVehiclesLocation(Seq(request))
      parsing.parseVehiclesLocation(json)
    }

    override def onPostExecute(result: Seq[VehicleInfo]) {
      setProgressBarIndeterminateVisibility(false)

      vehiclesOverlay = new VehiclesOverlay(getResources, result)
      updateOverlays()
    }

    override def onCancelled() {
      setProgressBarIndeterminateVisibility(false)

      vehiclesOverlay = null
      updateOverlays()
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
