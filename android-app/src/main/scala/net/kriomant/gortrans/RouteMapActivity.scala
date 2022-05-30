package net.kriomant.gortrans

import android.util.Log
import android.content.res.Resources
import java.lang.Math
import android.os.Bundle
import com.google.android.maps._
import net.kriomant.gortrans.parsing.{VehicleSchedule, VehicleInfo, RoutePoint}
import android.widget._
import android.content.{DialogInterface, Context, Intent}
import android.location.{Location}
import android.view.{LayoutInflater, ViewGroup, View}
import android.view.View.{MeasureSpec, OnClickListener}
import com.actionbarsherlock.app.SherlockMapActivity
import com.actionbarsherlock.view.{MenuItem, Window}
import android.graphics._
import drawable.{NinePatchDrawable, Drawable}
import net.kriomant.gortrans.core.{FoldedRouteStop, Direction, VehicleType}
import net.kriomant.gortrans.geometry.{Point => Pt}
import scala.collection.mutable
import android.graphics.Point
import scala.Some
import scala.collection.JavaConverters.asJavaCollectionConverter
import android.preference.PreferenceManager
import android.app.AlertDialog

object RouteMapActivity {
	private[this] val CLASS_NAME = classOf[RouteMapActivity].getName
	final val TAG = CLASS_NAME

	private val DIALOG_NEW_MAP_NOTICE = 1
}

class RouteMapActivity extends SherlockMapActivity
	with RouteMapLike
	with MapShortcutTarget {

	import RouteMapLike._
	import RouteMapActivity._

	private[this] var mapView: MapView = null
	private[this] var newMapNotice: View = null

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

	def isRouteDisplayed = false
	override def isLocationDisplayed = false

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

    // Enable to show indeterminate progress indicator in activity header.
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
		setSupportProgressBarIndeterminateVisibility(false)

		setContentView(R.layout.route_map)
		mapView = findViewById(R.id.route_map_view).asInstanceOf[MapView]
		mapView.setBuiltInZoomControls(true)
		mapView.setSatellite(false)

		balloonController = new MapBalloonController(this, mapView)
		vehiclesOverlay = new VehiclesOverlay(this, getResources, balloonController)
		routeStopNameOverlayManager = new RouteStopNameOverlayManager(getResources)

		newMapNotice = findViewById(R.id.new_map_notice)

		val prefs = PreferenceManager.getDefaultSharedPreferences(this)
		if (!prefs.contains(SettingsActivity.KEY_USE_NEW_MAP) && SettingsActivity.isNewMapAvailable(this)) {
			newMapNotice.setVisibility(View.VISIBLE)
			newMapNotice.setOnClickListener(new OnClickListener {
				def onClick(v: View) {
					showDialog(DIALOG_NEW_MAP_NOTICE)
				}
			})
		}
	}

	override def onNewIntent(intent: Intent) {
		super.onNewIntent(intent)

		val actionBar = getSupportActionBar
		actionBar.setDisplayHomeAsUpEnabled(true)
	}

	def removeAllRouteOverlays() {
		constantOverlays.clear()
	}

	def createRouteOverlays(routeInfo: core.Route, routeParams: RouteInfo) {
		// Get stop names which are already shown on map.
		val knownStopNames = (routes.values map(_.stopNames.map(_._2)) reduceLeftOption  (_ | _) getOrElse Set())
		// Create overlays for new stop names only.
		val newStopNames = routeParams.stopNames filterNot {case (pos, name) => knownStopNames contains name}

		val stopOverlayManager = routeStopNameOverlayManager
		val stopNameOverlays: Iterator[Overlay] = newStopNames.iterator map { case (pos, name) =>
			new stopOverlayManager.RouteStopNameOverlay(name, routePointToGeoPoint(pos))
		}

		val knownStops = routes.values map (_.stops) reduceLeftOption (_ | _) getOrElse Set()
		val newStops = routeParams.stops &~ knownStops
		val stopOverlays: Iterator[Overlay] = newStops.iterator map { case (point, name) =>
			new RouteStopOverlay(getResources, routePointToGeoPoint(point))
		}

		// Add route markers.
		val forwardRouteOverlay: Overlay = new RouteOverlay(
			getResources, getResources.getColor(R.color.forward_route),
			routeParams.forwardRoutePoints map routePointToGeoPoint
		)
		val backwardRouteOverlay: Overlay = new RouteOverlay(
			getResources, getResources.getColor(R.color.backward_route),
			routeParams.backwardRoutePoints map routePointToGeoPoint
		)
		val routeOverlays = Iterator(forwardRouteOverlay, backwardRouteOverlay)

		constantOverlays ++= routeOverlays
		constantOverlays ++= stopOverlays
		constantOverlays ++= stopNameOverlays

		updateOverlays()
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
			parentIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(parentIntent)
			true
		}
		case _ => super.onOptionsItemSelected(item)
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

	/** Same zoom level in Google Maps v1 and v2 leads to different actual
	  * scale. Since MapCameraPosition is based on Google Maps v2 CameraPosition,
	  * we must correct zoom here. */
	final val ZOOM_OFFSET = 1

	def getMapCameraPosition: RouteMapLike.MapCameraPosition = {
		val pos = mapView.getMapCenter
		val zoom = mapView.getZoomLevel

		RouteMapLike.MapCameraPosition(
			latitude = pos.getLatitudeE6.toDouble / 1e6,
			longitude = pos.getLongitudeE6.toDouble / 1e6,
			zoom = zoom-ZOOM_OFFSET
		)
	}

	def restoreMapCameraPosition(pos: RouteMapLike.MapCameraPosition) {
		val ctrl = mapView.getController
		ctrl.setZoom(math.round(pos.zoom+ZOOM_OFFSET))
		ctrl.setCenter(new GeoPoint(
			(pos.latitude * 1e6).toInt,
			(pos.longitude * 1e6).toInt
		))
	}

	def createProcessIndicator() = new MapActionBarProcessIndicator(this)

	def navigateTo(left: Double, top: Double, right: Double, bottom: Double) {
		val ctrl = mapView.getController
		ctrl.animateTo(new GeoPoint(((bottom + top)/2 * 1e6).toInt, ((left + right)/2 * 1e6).toInt))
		ctrl.zoomToSpan(((bottom - top) * 1e6).toInt, ((right - left) * 1e6).toInt)
	}


	def navigateTo(latitude: Double, longitude: Double) {
		val ctrl = mapView.getController
		ctrl.animateTo(new GeoPoint((latitude * 1e6).toInt, (longitude * 1e6).toInt))
	}

	def setTitle(title: String) {
		getSupportActionBar.setTitle(title)
	}

	def startBackgroundProcessIndication() {
		setSupportProgressBarIndeterminateVisibility(true)
	}

	def stopBackgroundProcessIndication() {
		setSupportProgressBarIndeterminateVisibility(false)
	}

	def clearVehicleMarkers() {
		vehiclesOverlay.clear()
		realVehicleLocationOverlays = Seq()
		updateOverlays()
	}

	def setVehicles(vehicles: Seq[(VehicleInfo, geometry.Point, Option[Double], Int)]) {
		realVehicleLocationOverlays = vehicles map { case (info, pos, angle, color) =>
			new RealVehicleLocationOverlay(pos, Pt(info.longitude, info.latitude))
		}
		updateOverlays()
	}

	def setLocationMarker(location: Location) {
		if (location != null) {
			val point = new GeoPoint((location.getLatitude * 1e6).toInt, (location.getLongitude * 1e6).toInt)
			val drawable = getResources.getDrawable(R.drawable.location_marker)
			locationOverlay = new MarkerOverlay(drawable, point, new PointF(0.5f, 0.5f))
		} else {
			locationOverlay = null
		}
		updateOverlays()
	}

	override def onCreateDialog(id: Int) = {
		id match {
			case DIALOG_NEW_MAP_NOTICE =>
				new AlertDialog.Builder(this)
					.setTitle(R.string.new_map_dialog_title)
					.setMessage(R.string.new_map_dialog_message)
					.setPositiveButton(R.string.new_map_try_button, new DialogInterface.OnClickListener {
						def onClick(dialog: DialogInterface, which: Int) {
							PreferenceManager.getDefaultSharedPreferences(RouteMapActivity.this)
								.edit()
								.putBoolean(SettingsActivity.KEY_USE_NEW_MAP, true)
								.commit()

							// Redirect to new maps.
							val intent = getIntent
							intent.setClass(RouteMapActivity.this, classOf[RouteMapV2Activity])
							intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
							startActivity(intent)

							finish()
						}
					})
					.setNegativeButton(R.string.new_map_reject_button, new DialogInterface.OnClickListener {
						def onClick(dialog: DialogInterface, which: Int) {
							PreferenceManager.getDefaultSharedPreferences(RouteMapActivity.this)
								.edit()
								.putBoolean(SettingsActivity.KEY_USE_NEW_MAP, false)
								.commit()

							newMapNotice.setVisibility(View.GONE)
							removeDialog(DIALOG_NEW_MAP_NOTICE)
						}
					})
					.create()
			case _ => super.onCreateDialog(id)
		}
	}

	def routePointToGeoPoint(p: Pt): GeoPoint = new GeoPoint((p.y * 1e6).toInt, (p.x * 1e6).toInt)
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
			val strokeWidth = Math.max(RouteMapLike.PHYSICAL_ROUTE_STROKE_WIDTH / metersPerPixel, RouteMapLike.MIN_ROUTE_STROKE_WIDTH)
			paint.setStrokeWidth(strokeWidth)

			paint.setColor(color)

			canvas.drawPath(path, paint)
		}
	}
}

class RouteStopOverlay(resources: Resources, geoPoint: GeoPoint) extends Overlay {
	val img = BitmapFactory.decodeResource(resources, R.drawable.route_stop_marker)
	val imgSmall = BitmapFactory.decodeResource(resources, R.drawable.route_stop_marker_small)

	override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
		if (! shadow && view.getZoomLevel >= RouteMapLike.ZOOM_WHOLE_ROUTE) {
			val point = new Point
			view.getProjection.toPixels(geoPoint, point)

			val icon = if (
				view.getZoomLevel == RouteMapLike.ZOOM_WHOLE_ROUTE ||
				view.getZoomLevel == RouteMapLike.ZOOM_WHOLE_ROUTE+1
			)
				imgSmall
			else
				img

			canvas.drawBitmap(icon, point.x - icon.getWidth/2, point.y - icon.getHeight/2, null)
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
			if (! shadow && view.getZoomLevel >= RouteMapLike.ZOOM_SHOW_STOP_NAMES) {
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
	context: RouteMapActivity, resources: Resources, balloonController: MapBalloonController
) extends ItemizedOverlay[OverlayItem](null)
{
  def boundCenterBottom = ItemizedOverlayBridge.boundCenterBottom_(_)

	val vehicleUnknown = resources.getDrawable(R.drawable.vehicle_stopped_marker)

	// Information to identify vehicle for which balloon is shown.
	var balloonVehicle: (VehicleType.Value, String, Int) = null
	val balloon = balloonController.inflateView(R.layout.map_vehicle_popup)

	var infos: Seq[(VehicleInfo, Pt, Option[Double], Int)] = Seq()

	/**
	 * @note This method manages balloon view, so it must be called from UI thread.
	 */
	def setVehicles(vehicles: Seq[(VehicleInfo, Pt, Option[Double], Int)]) {
		infos = vehicles
		populate()

		if (balloonVehicle != null && balloonController.isViewShown(balloon)) {
			// Find vehicle to show balloon for.
			infos.indexWhere{case (info, _, _, _) => (info.vehicleType, info.routeId, info.scheduleNr) == balloonVehicle} match {
				case -1 => balloonController.hideView(balloon)
				case pos => showBalloon(infos(pos)._1, getItem(pos))
			}
		}
	}

	def clear() { setVehicles(Seq()) }

  def size = infos.length

  def createItem(pos: Int) = {
    val (info, point, angle, baseColor) = infos(pos)

	  val marker = info.direction match {
		  case Some(dir) =>
			  val color = dir match {
					case Direction.Forward => baseColor
					case Direction.Backward => baseColor
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
		val oldName = RouteListBaseActivity.routeRenames.get(vehicleInfo.vehicleType, vehicleInfo.routeName)
		val title = RouteListBaseActivity.getRouteTitle(context, vehicleInfo.vehicleType, vehicleInfo.routeName)
		balloon.findViewById(R.id.vehicle_title).asInstanceOf[TextView].setText(title)

		val vehicleMarker = item.getMarker(0).getConstantState.newDrawable(balloon.getResources)
		balloon.findViewById(R.id.vehicle_icon).asInstanceOf[ImageView].setImageDrawable(vehicleMarker)

		balloon.findViewById(R.id.vehicle_schedule_number).asInstanceOf[TextView].setText(
			resources.getString(R.string.vehicle_schedule_number, vehicleInfo.scheduleNr.asInstanceOf[AnyRef])
		)

		val schedule = context.formatVehicleSchedule(vehicleInfo)
		balloon.findViewById(R.id.vehicle_schedule).asInstanceOf[TextView].setText(schedule)

		balloon.findViewById(R.id.vehicle_speed).asInstanceOf[TextView].setText(
			resources.getString(R.string.vehicle_speed_format, vehicleInfo.speed.asInstanceOf[AnyRef])
		)

		balloonVehicle = (vehicleInfo.vehicleType, vehicleInfo.routeId, vehicleInfo.scheduleNr)
		balloonController.showBalloon(balloon, item.getPoint)
	}
}
