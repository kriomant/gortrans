package net.kriomant.gortrans

import android.util.Log
import android.content.res.Resources
import java.lang.Math
import android.widget.CompoundButton.OnCheckedChangeListener
import android.os.Bundle
import com.google.android.maps._
import net.kriomant.gortrans.parsing.{VehicleSchedule, VehicleInfo, RoutePoint}
import android.widget._
import android.content.{Context, Intent}
import android.location.{Location}
import android.view.{LayoutInflater, ViewGroup, View}
import android.view.View.{MeasureSpec, OnClickListener}
import com.actionbarsherlock.app.SherlockMapActivity
import com.actionbarsherlock.view.{MenuItem, Window}
import android.graphics._
import drawable.{NinePatchDrawable, Drawable}
import net.kriomant.gortrans.core.{FoldedRouteStop, Direction, VehicleType}
import net.kriomant.gortrans.geometry.{Point => Pt}
import net.kriomant.gortrans.CursorIterator.cursorUtils
import utils.closing
import scala.collection.mutable
import android.graphics.Point
import scala.Some
import net.kriomant.gortrans.core.FoldedRouteStop
import scala.Left
import scala.Right
import scala.collection.JavaConverters.asJavaCollectionConverter
import net.kriomant.gortrans.VehiclesWatcher.Listener
import utils.functionAsRunnable

object RouteMapActivity {
	private[this] val CLASS_NAME = classOf[RouteMapActivity].getName
	final val TAG = CLASS_NAME

	private final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	private final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	private final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
	private final val EXTRA_GROUP_ID = CLASS_NAME + ".VEHICLE_TYPE"

	def createIntent(caller: Context, routeId: String, routeName: String, vehicleType: VehicleType.Value): Intent = {
		val intent = new Intent(caller, classOf[RouteMapActivity])
		intent.putExtra(EXTRA_ROUTE_ID, routeId)
		intent.putExtra(EXTRA_ROUTE_NAME, routeName)
		intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent
	}

	def createShowGroupIntent(context: Context, groupId: Long): Intent = {
		val intent = new Intent(context, classOf[RouteMapActivity])
		intent.putExtra(EXTRA_GROUP_ID, groupId)
		intent
	}

	// MapView's zoom level at which whole or significant part of route
	// is visible.
	val ZOOM_WHOLE_ROUTE = 14
	val ZOOM_SHOW_STOP_NAMES = 17

	case class RouteInfo(
		forwardRoutePoints: Seq[Pt],
		backwardRoutePoints: Seq[Pt],

		bounds: RectF,
		stops: Set[(Pt, String)], // (position, name)
		stopNames: Set[(Pt, String)],

		forwardRouteOverlay: Overlay,
		backwardRouteOverlay: Overlay
	)

	def routePointToGeoPoint(p: Pt): GeoPoint =
		new GeoPoint((p.y * 1e6).toInt, (p.x * 1e6).toInt)

	val routeNameResourceByVehicleType = Map(
		VehicleType.Bus -> R.string.bus_n,
		VehicleType.TrolleyBus -> R.string.trolleybus_n,
		VehicleType.TramWay -> R.string.tramway_n,
		VehicleType.MiniBus -> R.string.minibus_n
	)
}

class RouteMapActivity extends SherlockMapActivity
	with TypedActivity
	with TrackLocation
	with MapShortcutTarget {

	import RouteMapActivity._

	private[this] var mapView: MapView = null
  private[this] var trackVehiclesToggle: ToggleButton = null

	private[this] var dataManager: DataManager = null

	var routesInfo: Set[core.Route] = null

	var vehiclesWatcher: VehiclesWatcher = null

	var vehiclesData: Seq[(VehicleInfo, Pt, Option[Double])] = Seq()

	// Overlays are splitted into constant ones which are
	// updated on new intent or updated route data only and
	// volatile which are frequently updated, like vehicles.
	// At first constant:
	var routeStopNameOverlayManager: RouteStopNameOverlayManager = null
	var constantOverlays: mutable.Buffer[Overlay] = mutable.Buffer()
	// Now volatile ones:
  var vehiclesOverlay: VehiclesOverlay = null
	var locationOverlay: Overlay = null
	var realVehicleLocationOverlays: Seq[Overlay] = Seq()

	var balloonController: MapBalloonController = null

  var updatingVehiclesLocationIsOn: Boolean = true

	val routes: mutable.Map[(VehicleType.Value, String), RouteInfo] = mutable.Map()

	// Hack to avoid repositioning map when activity is recreated due to
	// configuration change. TODO: normal solution.
	var hasOldState: Boolean = false

	def isRouteDisplayed = false
	override def isLocationDisplayed = false

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		hasOldState = (bundle != null)

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
          if (vehiclesWatcher != null) vehiclesWatcher.startUpdatingVehiclesLocation()
        } else {
	        updatingVehiclesLocationIsOn = false
          if (vehiclesWatcher != null) vehiclesWatcher.stopUpdatingVehiclesLocation()
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

		balloonController = new MapBalloonController(this, mapView)
		vehiclesOverlay = new VehiclesOverlay(this, getResources, balloonController)
		routeStopNameOverlayManager = new RouteStopNameOverlayManager(getResources)

		onNewIntent(getIntent)
	}

	override def onNewIntent(intent: Intent) {
		setIntent(intent)

		val actionBar = getSupportActionBar
		actionBar.setDisplayHomeAsUpEnabled(true)

		if (vehiclesWatcher != null)
			vehiclesWatcher.stopUpdatingVehiclesLocation()
		vehiclesWatcher = null

		routes.clear()
		createConstantOverlays()
		updateOverlays()

		val db = getApplication.asInstanceOf[CustomApplication].database

		val groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1)
		if (groupId != -1) {
			val (groupName, dbRouteIds) = db.transaction {
				(db.getGroupName(groupId), db.getGroupItems(groupId))
			}
			routesInfo = dbRouteIds map { rid =>
				closing(db.fetchRoute(rid)) { c =>
					core.Route(c.vehicleType, c.externalId, c.name, c.firstStopName, c.lastStopName)
				}
			}

			actionBar.setTitle(groupName)

			loadRoutesData()

		} else {
			// Get route reference.
			val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
			val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
			val vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

			Log.d(TAG, "New intent received, route ID: %s" format routeId)

			routesInfo = Set(core.Route(vehicleType, routeId, routeName, "", ""))

			actionBar.setTitle(getString(routeNameResourceByVehicleType(vehicleType), routeName))

			loadRouteInfo(vehicleType, routeId, routeName)
		}

		vehiclesWatcher = new VehiclesWatcher(this, routesInfo.map(r => (r.vehicleType, r.id, r.name)), new Listener {
			def onVehiclesLocationUpdated(vehicles: Either[String, Seq[VehicleInfo]]) {
				RouteMapActivity.this.onVehiclesLocationUpdated()
			}

			def onVehiclesLocationUpdateCancelled() {
				RouteMapActivity.this.onVehiclesLocationUpdateCancelled()
			}

			def onVehiclesLocationUpdateStarted() {
				RouteMapActivity.this.onVehiclesLocationUpdateStarted()
			}
		})

		vehiclesWatcher.subscribe (updateVehiclesLocation _)
	}

	def loadRouteInfo(vehicleType: VehicleType.Value, routeId: String, routeName: String) {
		val db = getApplication.asInstanceOf[CustomApplication].database

		dataManager.requestRoutesList(
			new ForegroundProcessIndicator(this, () => loadRouteInfo(vehicleType, routeId, routeName)),
			new MapActionBarProcessIndicator(this)
		) {
			routesInfo = closing(db.fetchRoute(vehicleType, routeId)) { cursor =>
				val routeBegin = cursor.firstStopName
				val routeEnd = cursor.lastStopName
				Set(core.Route(vehicleType, routeId, routeName, routeBegin, routeEnd))
			}

			loadRoutesData()
		}
	}

	def loadRoutesData() {
		routesInfo foreach (loadData _)
	}

	def loadData(routeInfo: core.Route) {
		// Load route details.
		dataManager.requestRoutePoints(
			routeInfo.vehicleType, routeInfo.id, routeInfo.name, routeInfo.begin, routeInfo.end,
			new ForegroundProcessIndicator(this, () => loadData(routeInfo)),
			new MapActionBarProcessIndicator(this)
		) {
			val db = getApplication.asInstanceOf[CustomApplication].database

			// Load route data from database.
			val points = closing(db.fetchRoutePoints(routeInfo.vehicleType, routeInfo.id)) { cursor =>
				cursor.map(c => Pt(c.longitude, c.latitude)).toIndexedSeq
			}
			if (points.nonEmpty) {
				val stops = closing(db.fetchRouteStops(routeInfo.vehicleType, routeInfo.id)) {
					_.map(c => FoldedRouteStop[Int](c.name, c.forwardPointIndex, c.backwardPointIndex)).toIndexedSeq
				}

				// Split points into forward and backward parts. They will be used
				// for snapping vehicle position to route.
				val firstBackwardStop = stops.reverseIterator.find(_.backward.isDefined).get
				val firstBackwardPointIndex = firstBackwardStop.backward.get
				val (fwdp, bwdp) = points.splitAt(firstBackwardStop.backward.get)
				val forwardRoutePoints = points.slice(0, firstBackwardPointIndex+1)
				val backwardRoutePoints = points.slice(firstBackwardPointIndex, points.length) :+ points.head
				assert(forwardRoutePoints.last == backwardRoutePoints.head)
				assert(backwardRoutePoints.last == forwardRoutePoints.head)

				// Calculate rectangle (and it's center) containing whole route.
				val top = points.map(_.y).min
				val left = points.map(_.x).min
				val bottom = points.map(_.y).max
				val right = points.map(_.x).max
				val longitude = (left + right) / 2
				val latitude = (top + bottom) / 2
				val bounds = new RectF(left.toFloat, top.toFloat, right.toFloat, bottom.toFloat)

				if (! hasOldState) {
					// Navigate to show full route.
					val ctrl = mapView.getController
					ctrl.animateTo(new GeoPoint((latitude * 1e6).toInt, (longitude * 1e6).toInt))
					ctrl.zoomToSpan(((bottom - top) * 1e6).toInt, ((right - left) * 1e6).toInt)
				}

				// Add route markers.
				val forwardRouteOverlay = new RouteOverlay(
					getResources, getResources.getColor(R.color.forward_route),
					forwardRoutePoints map routePointToGeoPoint
				)
				val backwardRouteOverlay = new RouteOverlay(
					getResources, getResources.getColor(R.color.backward_route),
					backwardRoutePoints map routePointToGeoPoint
				)

				// Unfold route.
				val routeStops =
					stops.collect { case FoldedRouteStop(name, Some(pos), _) => (points(pos), name) } ++
					stops.reverse.collect { case FoldedRouteStop(name, _, Some(pos)) => (points(pos), name) }

				val stopNames = stops map { p =>
				// Find stop which is to the east of another.
					val stop = (p.forward, p.backward) match {
						case (Some(f), Some(b)) => if (points(f).x > points(b).x) f else b
						case (Some(f), None) => f
						case (None, Some(b)) => b
						case (None, None) => throw new AssertionError
					}
					(points(stop), p.name)
				}

				routes((routeInfo.vehicleType, routeInfo.id)) = RouteInfo(
					forwardRoutePoints, backwardRoutePoints,
					bounds, routeStops.toSet, stopNames.toSet,
					forwardRouteOverlay, backwardRouteOverlay
				)

				createConstantOverlays()
				updateOverlays()
			}
		}
	}

	/** Create overlays which are changed on new intent only.
	  *
	  * @note This method doesn't call updateOverlays.
		*/
	def createConstantOverlays() {
		// Display stop name next to one of folded stops.
		val stopNames = routes.values map(_.stopNames) reduceLeftOption  (_ | _) getOrElse Set()
		val stopOverlayManager = routeStopNameOverlayManager
		val stopNameOverlays: Iterator[Overlay] = stopNames.iterator map { case (pos, name) =>
			new stopOverlayManager.RouteStopNameOverlay(name, routePointToGeoPoint(pos))
		}

		val stops = routes.values map (_.stops) reduceLeftOption (_ | _) getOrElse Set()
		val stopOverlays: Iterator[Overlay] = stops.iterator map { case (point, name) =>
			new RouteStopOverlay(getResources, routePointToGeoPoint(point))
		}

		val routeOverlays: Iterator[Overlay] = routes.values.iterator flatMap { r =>
			Iterator(r.forwardRouteOverlay, r.backwardRouteOverlay)
		}

		constantOverlays.clear()
		constantOverlays ++= routeOverlays
		constantOverlays ++= stopOverlays
		constantOverlays ++= stopNameOverlays
	}

  def updateOverlays() {
    Log.d("RouteMapActivity", "updateOverlays")

    val overlays = mapView.getOverlays
    overlays.clear()

	  overlays.addAll(constantOverlays.asJavaCollection)

	  vehiclesOverlay.setVehicles(vehiclesData)
    overlays.add(vehiclesOverlay)
	  for (o <- realVehicleLocationOverlays)
		  overlays.add(o)
	  if (locationOverlay != null)
		  overlays.add(locationOverlay)

	  overlays.add(balloonController.closeBalloonOverlay)

	  mapView.postInvalidate()
  }

  override def onResume() {
    super.onResume()

    if (updatingVehiclesLocationIsOn && vehiclesWatcher != null)
      vehiclesWatcher.startUpdatingVehiclesLocation()
  }

  override def onPause() {
    if (updatingVehiclesLocationIsOn && vehiclesWatcher != null)
      vehiclesWatcher.stopUpdatingVehiclesLocation()

    super.onPause()
  }

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
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
			startActivity(parentIntent)
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
		val intent = getIntent
		val name = intent.getLongExtra(EXTRA_GROUP_ID, -1) match {
			case -1 =>
				val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
				val vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

				val vehicleShortName = getString(vehicleType match {
					case VehicleType.Bus => R.string.bus_short
					case VehicleType.TrolleyBus => R.string.trolleybus_short
					case VehicleType.TramWay => R.string.tramway_short
					case VehicleType.MiniBus => R.string.minibus_short
				})
				getString(R.string.route_map_shortcut_format, vehicleShortName, routeName)

			case groupId =>
				val db = getApplication.asInstanceOf[CustomApplication].database
				db.getGroupName(groupId)
		}
		(name, R.drawable.route_map)
	}


	def onVehiclesLocationUpdateStarted() {
		setSupportProgressBarIndeterminateVisibility(true)
	}

	def onVehiclesLocationUpdateCancelled() {
		setSupportProgressBarIndeterminateVisibility(false)

		vehiclesOverlay.clear()
		realVehicleLocationOverlays = Seq()
		updateOverlays()
	}

	def onVehiclesLocationUpdated() {
		setSupportProgressBarIndeterminateVisibility(false)
	}

	def updateVehiclesLocation(result: Either[String, Seq[VehicleInfo]]) {
		result match {
			case Right(vehicles) => {
				val vehiclesPointsAndAngles = android_utils.measure(TAG, "snapping %d vehicles" format vehicles.length) {
					vehicles map { v =>
						routes.get((v.vehicleType, v.routeId)) match {
							case None => (v, Pt(v.latitude, v.longitude), Some(v.azimuth/180.0*math.Pi))
							case Some(route) =>
								val (pt, segment) = v.direction match {
									case Some(Direction.Forward) => core.snapVehicleToRoute(v, route.forwardRoutePoints)
									case Some(Direction.Backward) => core.snapVehicleToRoute(v, route.backwardRoutePoints)
									case None => (Pt(v.longitude, v.latitude), None)
								}

								val angle = segment map { s =>
									math.atan2(s._2.y - s._1.y, s._2.x - s._1.x) * 180 / math.Pi
								}
								(v, pt, angle)
						}
					}
				}

				vehiclesData = vehiclesPointsAndAngles
				realVehicleLocationOverlays = vehiclesPointsAndAngles map { case (info, pos, angle) =>
					new RealVehicleLocationOverlay(pos, Pt(info.longitude, info.latitude))
				}

				runOnUiThread { () =>
					updateOverlays()
				}
			}

			case Left(message) => {
				vehiclesData = Seq()
				realVehicleLocationOverlays = Seq()
				runOnUiThread { () =>
					Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
					updateOverlays()
				}
			}
		}
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
		if (! shadow && view.getZoomLevel >= RouteMapActivity.ZOOM_WHOLE_ROUTE) {
			val point = new Point
			view.getProjection.toPixels(geoPoint, point)

			if (
				view.getZoomLevel == RouteMapActivity.ZOOM_WHOLE_ROUTE ||
				view.getZoomLevel == RouteMapActivity.ZOOM_WHOLE_ROUTE+1
			)
				canvas.drawBitmap(img,
					new Rect(0, 0, img.getWidth, img.getHeight),
					new Rect(point.x - img.getWidth/4, point.y - img.getHeight/4, point.x + img.getWidth/4, point.y + img.getHeight/4),
					null
				)
			else
				canvas.drawBitmap(img, point.x - img.getWidth/2, point.y - img.getHeight/2, null)
		}
	}
}

class RouteStopNameOverlayManager(resources: Resources) {
	private val frame = resources.getDrawable(R.drawable.stop_name_frame).asInstanceOf[NinePatchDrawable]

	private val framePadding = new Rect
	frame.getPadding(framePadding)

	// Shared graphic resources to avoid allocations during drawing.
	private val pen = new Paint()
	pen.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD))
	pen.setTextSize(resources.getDimension(R.dimen.stop_name_font_size))
	private val fontMetrics = pen.getFontMetricsInt

	private val point = new Point
	private val bounds = new Rect

	class RouteStopNameOverlay(name: String, geoPoint: GeoPoint) extends Overlay {
		final val X_OFFSET = 15
		final val Y_OFFSET = 5

		override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
			if (! shadow && view.getZoomLevel >= RouteMapActivity.ZOOM_SHOW_STOP_NAMES) {
				view.getProjection.toPixels(geoPoint, point)

				val textWidth = pen.measureText(name).toInt

				// Bottom-left corner of frame points to stop.
				bounds.set(
					point.x,
					point.y - framePadding.top - framePadding.bottom - (fontMetrics.bottom - fontMetrics.top),
					point.x + framePadding.left + framePadding.right + textWidth,
					point.y
				)
				frame.setBounds(bounds)

				frame.draw(canvas)
				canvas.drawText(
					name,
					point.x + framePadding.left,
					point.y - framePadding.bottom - fontMetrics.bottom,
					pen
				)
			}
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

class MapBalloonController(context: Context, mapView: MapView) {
	import utils.functionAsRunnable

	val closeBalloonOverlay = new Overlay {
		override def onTap(p1: GeoPoint, p2: MapView): Boolean = {
			if (balloon != null)
				hideBalloon()
			false
		}
	}

	def inflateView(layoutResourceId: Int): View = {
		val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater]
		inflater.inflate(layoutResourceId, mapView, false)
	}

	def showBalloon(view: View, at: GeoPoint) {
		require(view != null)

		if (balloon.ne(null) && balloon.ne(view))
			mapView.removeView(balloon)

		val layoutParams = new MapView.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
			at, MapView.LayoutParams.BOTTOM_CENTER
		)
		view.setLayoutParams(layoutParams.asInstanceOf[ViewGroup.LayoutParams])

		if (view ne balloon) {
			mapView.addView(view)
			balloon = view
		}

		mapView.measure(
			MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
			MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
		)

		balloon.post { () =>
			if (balloon != null) {
				ensureBalloonIsFullyVisible()
			}
		}
	}

	def isViewShown(view: View): Boolean = (view eq balloon)

	def hideBalloon() {
		if (balloon != null) {
			mapView.removeView(balloon)
			balloon = null
		}
	}

	def hideView(view: View) {
		if (balloon == view) {
			mapView.removeView(balloon)
			balloon = null
		}
	}

	private def ensureBalloonIsFullyVisible() {
		// Get current map center at translate to pixel coordinates.
		val geoCenter= mapView.getMapCenter
		val pt = new Point
		mapView.getProjection.toPixels(geoCenter, pt)

		// Correct center coordinates to make balloon fully visible.
		if (balloon.getLeft < 0) {
			pt.x += balloon.getLeft
		}
		if (balloon.getTop < 0) {
			pt.y += balloon.getTop
		}

		// Make bottom and right edges visible, but left and top edges have
		// priority - don't hide them.
		if (balloon.getRight > mapView.getWidth) {
			pt.x += Math.min(balloon.getLeft, balloon.getRight - mapView.getWidth)
		}
		if (balloon.getBottom > mapView.getHeight) {
			pt.y += Math.min(balloon.getTop, balloon.getBottom - mapView.getHeight)
		}

		// Animate map scroll.
		mapView.getController.animateTo(mapView.getProjection.fromPixels(pt.x, pt.y))
	}

	private[this] var balloon: View = null
}

object VehicleMarker {
	case class ConstantState(
		back: Drawable, front: Drawable, arrow: Drawable,
		angle: Option[Float], color: Int
	) extends Drawable.ConstantState {
		// VehicleMarker uses color filters to colorize some drawables and
		// color filter is part of constant state and is shared between
		// drawables, so they are needed to be mutated.
		def this(resources: Resources, angle: Option[Float], color: Int) = this(
			resources.getDrawable(R.drawable.vehicle_marker_back).mutate(),
			resources.getDrawable(R.drawable.vehicle_marker_front),
			resources.getDrawable(R.drawable.vehicle_marker_arrow).mutate(),
			angle, color
		)

		back.setColorFilter(new LightingColorFilter(Color.BLACK, color))
		arrow.setColorFilter(new LightingColorFilter(Color.BLACK, color))

		val frontInsets = {
			val diff = back.getIntrinsicWidth - front.getIntrinsicWidth
			val offset = diff / 2
			new Rect(offset, offset, diff - offset, back.getIntrinsicHeight - front.getIntrinsicHeight - offset)
		}

		def newDrawable(): Drawable = new VehicleMarker(new ConstantState(
			back.getConstantState.newDrawable(),
			front.getConstantState.newDrawable(),
			arrow.getConstantState.newDrawable(),
			angle, color
		))

		override def newDrawable(res: Resources): Drawable = new VehicleMarker(new ConstantState(
			back.getConstantState.newDrawable(res),
			front.getConstantState.newDrawable(res),
			arrow.getConstantState.newDrawable(res),
			angle, color
		))

		def getChangingConfigurations: Int =
			back.getChangingConfigurations |
			front.getChangingConfigurations |
			arrow.getChangingConfigurations
	}
}
class VehicleMarker private (state: VehicleMarker.ConstantState) extends Drawable {
	def this(resources: Resources, angle: Option[Float], color: Int) =
		this(new VehicleMarker.ConstantState(resources, angle, color))

	def draw(canvas: Canvas) {
		val bounds = getBounds
		canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, _alpha, Canvas.HAS_ALPHA_LAYER_SAVE_FLAG)
		state.back.draw(canvas)
		if (state.front.isVisible)
			state.front.draw(canvas)

		if (state.arrow.isVisible) {
			state.angle map { a =>
				canvas.save(Canvas.MATRIX_SAVE_FLAG)
				val arrowBounds = state.arrow.getBounds
				canvas.rotate(-a, (arrowBounds.left + arrowBounds.right) / 2.0f, (arrowBounds.top + arrowBounds.bottom) / 2.0f)
				state.arrow.draw(canvas)
				canvas.restore()
			}
		}

		canvas.restore()
	}

	var _alpha: Int = 255

	def setAlpha(alpha: Int) {
		_alpha = alpha
	}

	/** Set color filter.
	  *
	  * This method should apply color filter to whole drawable, but it doesn't
	  * do it exactly. This drawable is composite and uses color filter on inner drawables
	  * to colorize them. Ideally I should compose inner and provided color filters,
	  * but Android platform doesn't provide such capability.
	  *
	  * So this method is designed to work with ItemizedOverlay which uses color
	  * filter to draw shadows.
	  */
	def setColorFilter(cf: ColorFilter) {
		if (cf != null) {
			state.back.setColorFilter(cf)
			state.front.setVisible(false, false)
			state.arrow.setVisible(false, false)
		} else {
			state.back.setColorFilter(new LightingColorFilter(Color.BLACK, state.color))
			state.front.setVisible(true, false)
			state.arrow.setVisible(true, false)
		}
	}

	def getOpacity: Int = PixelFormat.TRANSLUCENT

	override def onBoundsChange(bounds: Rect) {
		super.onBoundsChange(bounds)
		state.back.setBounds(bounds)

		val frontRect = new Rect(
			bounds.left + state.frontInsets.left,
			bounds.top + state.frontInsets.top,
			bounds.right - state.frontInsets.right,
			bounds.bottom - state.frontInsets.bottom
		)
		state.front.setBounds(frontRect)
		state.arrow.setBounds(frontRect)
	}

	override def getIntrinsicWidth: Int = state.back.getIntrinsicWidth
	override def getIntrinsicHeight: Int = state.back.getIntrinsicHeight

	override def getConstantState = state

	override def mutate(): Drawable = throw new Exception("not mutable")
}

class VehiclesOverlay(
	context: Context, resources: Resources, balloonController: MapBalloonController
) extends ItemizedOverlay[OverlayItem](null)
{
  def boundCenterBottom = ItemizedOverlayBridge.boundCenterBottom_(_)

	val vehicleUnknown = resources.getDrawable(R.drawable.vehicle_stopped_marker)

	// Information to identify vehicle for which balloon is shown.
	var balloonVehicle: (VehicleType.Value, String, Int) = null
	val balloon = balloonController.inflateView(R.layout.map_vehicle_popup)

	var infos: Seq[(VehicleInfo, Pt, Option[Double])] = Seq()

	/**
	 * @note This method manages balloon view, so it must be called from UI thread.
	 */
	def setVehicles(vehicles: Seq[(VehicleInfo, Pt, Option[Double])]) {
		infos = vehicles
		populate()

		if (balloonVehicle != null && balloonController.isViewShown(balloon)) {
			// Find vehicle to show balloon for.
			infos.indexWhere{case (info, _, _) => (info.vehicleType, info.routeId, info.scheduleNr) == balloonVehicle} match {
				case -1 => balloonController.hideView(balloon)
				case pos => showBalloon(infos(pos)._1, getItem(pos))
			}
		}
	}

	def clear() { setVehicles(Seq()) }

  def size = infos.length

  def createItem(pos: Int) = {
    val (info, point, angle) = infos(pos)

	  val marker = info.direction match {
		  case Some(dir) =>
			  val color = dir match {
					case Direction.Forward => resources.getColor(R.color.forward_vehicle)
					case Direction.Backward => resources.getColor(R.color.backward_vehicle)
				}
			  new VehicleMarker(resources, angle.map(_.toFloat), color)

		  case None => vehicleUnknown
	  }
    val geoPoint = new GeoPoint((point.y*1e6).toInt, (point.x*1e6).toInt)
    val item = new OverlayItem(geoPoint, null, null)
	  item.setMarker(boundCenterBottom(marker))
    item
  }

	populate()

	override def onTap(index: Int): Boolean = {
		showBalloon(infos(index)._1, getItem(index))
		true
	}

	private def showBalloon(vehicleInfo: VehicleInfo, item: OverlayItem) {
		val title = context.getString(
			RouteMapActivity.routeNameResourceByVehicleType(vehicleInfo.vehicleType),
			vehicleInfo.routeName
		)
		balloon.findViewById(R.id.vehicle_title).asInstanceOf[TextView].setText(title)

		val vehicleMarker = item.getMarker(0).getConstantState.newDrawable(balloon.getResources)
		balloon.findViewById(R.id.vehicle_icon).asInstanceOf[ImageView].setImageDrawable(vehicleMarker)

		balloon.findViewById(R.id.vehicle_schedule_number).asInstanceOf[TextView].setText(
			resources.getString(R.string.vehicle_schedule_number, vehicleInfo.scheduleNr.asInstanceOf[AnyRef])
		)

		val schedule = vehicleInfo.schedule match {
			case VehicleSchedule.Schedule(schedule) =>
				schedule.map{ case (time, stop) =>
					resources.getString(R.string.vehicle_schedule_row, time, stop)
				}.mkString("\n")
			case VehicleSchedule.Status(status) => status
			case VehicleSchedule.NotProvided => ""
		}
		balloon.findViewById(R.id.vehicle_schedule).asInstanceOf[TextView].setText(schedule)

		balloon.findViewById(R.id.vehicle_speed).asInstanceOf[TextView].setText(
			resources.getString(R.string.vehicle_speed_format, vehicleInfo.speed.asInstanceOf[AnyRef])
		)

		balloonVehicle = (vehicleInfo.vehicleType, vehicleInfo.routeId, vehicleInfo.scheduleNr)
		balloonController.showBalloon(balloon, item.getPoint)
	}
}
