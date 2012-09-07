package net.kriomant.gortrans

import android.util.Log
import android.content.res.Resources
import java.lang.Math
import android.widget.CompoundButton.OnCheckedChangeListener
import android.os.{Handler, Bundle}
import com.google.android.maps._
import net.kriomant.gortrans.parsing.{VehicleInfo, RoutePoint}
import android.widget.{ToggleButton, CompoundButton}
import android.content.{Context, Intent}
import android.location.{Location}
import android.view.View
import android.view.View.OnClickListener
import com.actionbarsherlock.app.SherlockMapActivity
import com.actionbarsherlock.view.{MenuItem, Window}
import net.kriomant.gortrans.geometry.{Point => Pt, closestSegmentPoint}
import net.kriomant.gortrans.utils.traversableOnceUtils
import android.graphics._
import android.graphics.drawable.{BitmapDrawable, Drawable}
import net.kriomant.gortrans.core.{FoldedRouteStop, Direction, VehicleType, foldRoute}

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
  
  var forwardRouteOverlay: Overlay = null
	var backwardRouteOverlay: Overlay = null
  var stopOverlays: Seq[Overlay] = null
	var stopNameOverlays: Seq[Overlay] = null
  var vehiclesOverlay: ItemizedOverlay[OverlayItem] = null
	var locationOverlay: Overlay = null
	var realVehicleLocationOverlays: Seq[Overlay] = Seq()

  var updatingVehiclesLocationIsOn: Boolean = false

	var routePoints: Seq[RoutePoint] = null
	var forwardRoutePoints: Seq[RoutePoint] = null
	var backwardRoutePoints: Seq[RoutePoint] = null
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
				if (location != null) {
					val ctrl = mapView.getController
					ctrl.animateTo(new GeoPoint((location.getLatitude * 1e6).toInt, (location.getLongitude * 1e6).toInt))
				}
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

		loadData()
	}

	def loadData() {
		// Load route details.
		dataManager.requestRoutePoints(
			vehicleType, routeId, routeName,
			new ForegroundProcessIndicator(this, loadData),
			new MapActionBarProcessIndicator(this)
		) {
			val db = getApplication.asInstanceOf[CustomApplication].database
			routePoints = db.fetchLegacyRoutePoints(vehicleType, routeId)
			routeStops = routePoints filter(_.stop.isDefined)
			val foldedRoute = foldRoute[RoutePoint](routeStops, _.stop.get.name)

			val (f, b) = core.splitRoute(foldedRoute, routePoints)
			forwardRoutePoints = f
			backwardRoutePoints = b

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
			forwardRouteOverlay = new RouteOverlay(
				getResources, getResources.getColor(R.color.forward_route),
				forwardRoutePoints map routePointToGeoPoint
			)
			backwardRouteOverlay = new RouteOverlay(
				getResources, getResources.getColor(R.color.backward_route),
				backwardRoutePoints map routePointToGeoPoint
			)

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
					case (None, None) => throw new AssertionError
				}
				new RouteStopNameOverlay(
					p.name,
					new GeoPoint((stop.latitude * 1e6).toInt, (stop.longitude * 1e6).toInt)
				)
			}
			updateOverlays()
		}
	}

  def updateOverlays() {
    Log.d("RouteMapActivity", "updateOverlays")
    val overlays = mapView.getOverlays
    overlays.clear()
    overlays.add(forwardRouteOverlay)
	  overlays.add(backwardRouteOverlay)
    for (o <- stopOverlays)
      overlays.add(o)
	  for (o <- stopNameOverlays)
		  overlays.add(o)
    if (vehiclesOverlay != null)
      overlays.add(vehiclesOverlay)
	  for (o <- realVehicleLocationOverlays)
		  overlays.add(o)
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
		realVehicleLocationOverlays = Seq()
		updateOverlays()
	}

	def onVehiclesLocationUpdated(vehicles: Seq[VehicleInfo]) {
		val vehiclesPointsAndAngles = vehicles map { v =>
			val (pt, segment) = v.direction match {
				case Some(Direction.Forward) => core.snapVehicleToRoute(v, forwardRoutePoints.map(p => Pt(p.longitude, p.latitude)))
				case Some(Direction.Backward) => core.snapVehicleToRoute(v, backwardRoutePoints.map(p => Pt(p.longitude, p.latitude)))
				case None => (Pt(v.longitude, v.latitude), None)
			}

			val angle = segment map { s =>
				math.atan2(s._2.y - s._1.y, s._2.x - s._1.x) * 180 / math.Pi
			}
			(v, pt, angle)
		}

		vehiclesOverlay = new VehiclesOverlay(getResources, vehiclesPointsAndAngles)
		realVehicleLocationOverlays = vehiclesPointsAndAngles map { case (info, pos, angle) =>
			new RealVehicleLocationOverlay(pos, Pt(info.longitude, info.latitude))
		}
		updateOverlays()

		setSupportProgressBarIndeterminateVisibility(false)
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

class RouteOverlay(resources: Resources, color: Int, geoPoints: Seq[GeoPoint]) extends Overlay {
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

			view.getProjection.toPixels(geoPoints.head, point)
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

			paint.setColor(color)

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

// VehiclesOverlay shows vehicle locations snapped to route. This overlay
// is used to show real vehicle location.
class RealVehicleLocationOverlay(snapped: Pt, real: Pt) extends Overlay {
	override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
		if (! shadow) {
			val realPoint = new Point
			view.getProjection.toPixels(new GeoPoint((real.y*1e6).toInt, (real.x*1e6).toInt), realPoint)

			val snappedPoint = new Point
			view.getProjection.toPixels(new GeoPoint((snapped.y*1e6).toInt, (snapped.x*1e6).toInt), snappedPoint)

			val pen = new Paint()
			canvas.drawLine(realPoint.x, realPoint.y, snappedPoint.x, snappedPoint.y, pen)
		}
	}
}

class VehiclesOverlay(resources: Resources, infos: Seq[(VehicleInfo, Pt, Option[Double])])
	extends ItemizedOverlay[OverlayItem](null)
{
  def boundCenterBottom = ItemizedOverlayBridge.boundCenterBottom_(_)

	val forwardArrow = BitmapFactory.decodeResource(resources, R.drawable.forward_route_arrow)
	val backwardArrow = BitmapFactory.decodeResource(resources, R.drawable.backward_route_arrow)

	val vehicleForward = BitmapFactory.decodeResource(resources, R.drawable.vehicle_forward_marker)
	val vehicleBackward = BitmapFactory.decodeResource(resources, R.drawable.vehicle_backward_marker)
	val vehicleUnknown = BitmapFactory.decodeResource(resources, R.drawable.vehicle_stopped_marker)

  populate()

  def size = infos.length

  def createItem(pos: Int) = {
    Log.d("RouteMapActivity", "Create vehicle overlay #%d" format pos)
    val (info, point, angle) = infos(pos)

	  val bitmap = info.direction match {
		  case Some(dir) =>
			  val (image, arrow) = dir match {
					case Direction.Forward => (vehicleForward, forwardArrow)
					case Direction.Backward => (vehicleBackward, backwardArrow)
				}
			  angle match {
				  case Some(a) =>
					  val modified = image.copy(image.getConfig, true)
					  val canvas = new Canvas(modified)
					  canvas.rotate(-a.toFloat, image.getWidth / 2, image.getWidth / 2)
					  canvas.drawBitmap(arrow, (image.getWidth-arrow.getWidth)/2, (image.getWidth-arrow.getHeight)/2, new Paint)
					  modified

				  case None => image
			  }

		  case None => vehicleUnknown
	  }
    val geoPoint = new GeoPoint((point.y*1e6).toInt, (point.x*1e6).toInt)
    val item = new OverlayItem(geoPoint, null, null)
	  item.setMarker(boundCenterBottom(new BitmapDrawable(resources, bitmap)))
    item
  }
}
