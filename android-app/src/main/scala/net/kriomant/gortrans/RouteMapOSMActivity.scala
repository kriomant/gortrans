package net.kriomant.gortrans

import android.app.{AlertDialog, Dialog}
import android.content.res.Resources
import android.content.{Context, DialogInterface, Intent}
import android.graphics.{Point, _}
import android.graphics.drawable.{Drawable, NinePatchDrawable}
import android.location.Location
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View.{MeasureSpec, OnClickListener}
import android.view.{LayoutInflater, MotionEvent, View, ViewGroup}
import android.widget._
import com.actionbarsherlock.app.SherlockActivity
import com.actionbarsherlock.view.{MenuItem, Window}
import net.kriomant.gortrans.core.{Direction, VehicleType}
import net.kriomant.gortrans.geometry.{Point => Pt}
import net.kriomant.gortrans.parsing.VehicleInfo
import org.osmdroid.DefaultResourceProxyImpl
import org.osmdroid.api.IMapView
import org.osmdroid.util.{BoundingBoxE6, GeoPoint}
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.OverlayItem.HotspotPlace
import org.osmdroid.views.overlay.{ItemizedOverlay, Overlay, OverlayItem}

import scala.collection.JavaConverters.asJavaCollectionConverter
import scala.collection.mutable

object RouteMapOSMActivity {
  private[this] val CLASS_NAME = classOf[RouteMapOSMActivity].getName
  final val TAG = CLASS_NAME

  private val DIALOG_NEW_MAP_NOTICE = 1
}

class RouteMapOSMActivity extends SherlockActivity
  with RouteMapLike
  with TypedActivity
  with ShortcutTarget {

  import RouteMapLike._
  import RouteMapOSMActivity._

  private[this] var mapView: MapView = _
  private[this] var newMapNotice: View = _

  // Overlays are splitted into constant ones which are
  // updated on new intent or updated route data only and
  // volatile which are frequently updated, like vehicles.
  // At first constant:
  var routeStopNameOverlayManager: RouteStopNameOverlayManagerOSM = _
  var constantOverlays: mutable.Buffer[Overlay] = mutable.Buffer()
  // Now volatile ones:
  var vehiclesOverlay: VehiclesOverlayOSM = _
  ///var locationOverlay: Overlay = _
  var realVehicleLocationOverlays: Seq[Overlay] = Seq()

  var balloonController: MapBalloonControllerOSM = _

  def isRouteDisplayed = false

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

    // Enable to show indeterminate progress indicator in activity header.
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
    setSupportProgressBarIndeterminateVisibility(false)

    setContentView(R.layout.route_map_osm)
    mapView = findViewById(R.id.route_map_osm_view).asInstanceOf[MapView]
    mapView.setBuiltInZoomControls(true)
    mapView.setMultiTouchControls(true)

    // I have problem with "safe canvas": saveLayerAlpha doesn't change
    // save count, so corresponding call to restore fails with "Underflow in restore"
    //mapView.setUseSafeCanvas(false)

    balloonController = new MapBalloonControllerOSM(this, mapView)
    vehiclesOverlay = new VehiclesOverlayOSM(this, getResources, balloonController)
    routeStopNameOverlayManager = new RouteStopNameOverlayManagerOSM(getResources)

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


  override def onPostCreate(savedInstanceState: Bundle) {
    super.onPostCreate(savedInstanceState)

    showWholeRoutes()
  }

  override def onNewIntent(intent: Intent) {
    super.onNewIntent(intent)

    val actionBar = getSupportActionBar
    actionBar.setDisplayHomeAsUpEnabled(true)
  }

  def removeAllRouteOverlays() {
    ///constantOverlays.clear()
  }

  def createRouteOverlays(routeInfo: core.Route, routeParams: RouteInfo) {
    // Get stop names which are already shown on map.
    val knownStopNames = routes.values map (_.stopNames.map(_._2)) reduceLeftOption (_ | _) getOrElse Set()
    // Create overlays for new stop names only.
    val newStopNames = routeParams.stopNames filterNot { case (pos, name) => knownStopNames contains name }

    val stopOverlayManager = routeStopNameOverlayManager
    val stopNameOverlays: Iterator[Overlay] = newStopNames.iterator map { case (pos, name) =>
      new stopOverlayManager.RouteStopNameOverlay(this, name, routePointToGeoPoint(pos))
    }

    val knownStops = routes.values map (_.stops) reduceLeftOption (_ | _) getOrElse Set()
    val newStops = routeParams.stops &~ knownStops
    val stopOverlays: Iterator[Overlay] = newStops.iterator map { case (point, name) =>
      new RouteStopOverlayOSM(this, getResources, routePointToGeoPoint(point))
    }

    // Add route markers.
    val forwardRouteOverlay: Overlay = new RouteOverlayOSM(
      this,
      getResources, getResources.getColor(R.color.forward_route),
      routeParams.forwardRoutePoints map routePointToGeoPoint
    )
    val backwardRouteOverlay: Overlay = new RouteOverlayOSM(
      this,
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
    Log.d("RouteMapOSMActivity", "updateOverlays")

    val overlays = mapView.getOverlays
    overlays.clear()

    overlays.addAll(constantOverlays.asJavaCollection)

    vehiclesOverlay.setVehicles(vehiclesData)
    overlays.add(vehiclesOverlay)
    for (o <- realVehicleLocationOverlays)
      overlays.add(o)
    /*if (locationOverlay != null)
      overlays.add(locationOverlay)*/

    overlays.add(balloonController.closeBalloonOverlay)

    mapView.postInvalidate()
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
    case android.R.id.home =>
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
      zoom = zoom - ZOOM_OFFSET
    )
  }

  def restoreMapCameraPosition(pos: RouteMapLike.MapCameraPosition) {
    val ctrl = mapView.getController
    ctrl.setZoom(math.round(pos.zoom + ZOOM_OFFSET))
    ctrl.setCenter(new GeoPoint(
      (pos.latitude * 1e6).toInt,
      (pos.longitude * 1e6).toInt
    ))
  }

  def createProcessIndicator() = new ActionBarProcessIndicator(this)

  def navigateTo(left: Double, top: Double, right: Double, bottom: Double) {
    val ctrl = mapView.getController
    // zoom before centering: http://code.google.com/p/osmdroid/issues/detail?id=204
    //ctrl.setZoom(13)
    //ctrl.zoomToSpan(((bottom - top) * 1e6).toInt, ((right - left) * 1e6).toInt)
    //ctrl.animateTo(new GeoPoint(((bottom + top)/2 * 1e6).toInt, ((left + right)/2 * 1e6).toInt))
    mapView.zoomToBoundingBox(new BoundingBoxE6(top, right, bottom, left))
  }


  def navigateTo(latitude: Double, longitude: Double) {
    val ctrl = mapView.getController
    // animateTo doesn't work: http://code.google.com/p/osmdroid/issues/detail?id=278
    //ctrl.animateTo(new GeoPoint((latitude * 1e6).toInt, (longitude * 1e6).toInt))
    ctrl.setCenter(new GeoPoint((latitude * 1e6).toInt, (longitude * 1e6).toInt))
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
      new RealVehicleLocationOverlayOSM(this, pos, Pt(info.longitude, info.latitude))
    }
    updateOverlays()
  }

  def setLocationMarker(location: Location) {
    /*
        if (location != null) {
          val point = new GeoPoint((location.getLatitude * 1e6).toInt, (location.getLongitude * 1e6).toInt)
          val drawable = getResources.getDrawable(R.drawable.location_marker)
          locationOverlay = new MarkerOverlayOSM(this, drawable, point, new PointF(0.5f, 0.5f))
        } else {
          locationOverlay = null
        }
        updateOverlays()
      */
  }

  override def onCreateDialog(id: Int): Dialog = {
    id match {
      case DIALOG_NEW_MAP_NOTICE =>
        new AlertDialog.Builder(this)
          .setTitle(R.string.osm_map_dialog_title)
          .setMessage(R.string.osm_map_dialog_message)
          .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener {
            def onClick(dialog: DialogInterface, which: Int) {
              dismissDialog(DIALOG_NEW_MAP_NOTICE)
            }
          })
          .create()
      case _ => super.onCreateDialog(id)
    }
  }

  def routePointToGeoPoint(p: Pt): GeoPoint = new GeoPoint((p.y * 1e6).toInt, (p.x * 1e6).toInt)
}

class MarkerOverlayOSM(context: Context, drawable: Drawable, location: GeoPoint, anchorPosition: PointF) extends Overlay(context) {
  val offset = new Point(
    -(drawable.getIntrinsicWidth * anchorPosition.x).toInt,
    -(drawable.getIntrinsicHeight * anchorPosition.y).toInt
  )

  override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
    if (!shadow) {
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

class RouteOverlayOSM(context: Context, resources: Resources, color: Int, geoPoints: Seq[GeoPoint]) extends Overlay(context) {
  // Place here to avoid path allocation on each drawing.
  val path = new Path
  path.incReserve(geoPoints.length + 1)

  val paint = new Paint
  paint.setStyle(Paint.Style.STROKE)

  override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
    if (!shadow) {
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

class RouteStopOverlayOSM(context: Context, resources: Resources, geoPoint: GeoPoint) extends Overlay(context) {
  val img: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.route_stop_marker)
  val imgSmall: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.route_stop_marker_small)

  override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
    if (!shadow && view.getZoomLevel >= RouteMapLike.ZOOM_WHOLE_ROUTE) {
      val point = new Point
      view.getProjection.toPixels(geoPoint, point)

      val icon = if (
        view.getZoomLevel == RouteMapLike.ZOOM_WHOLE_ROUTE ||
          view.getZoomLevel == RouteMapLike.ZOOM_WHOLE_ROUTE + 1
      )
        imgSmall
      else
        img

      canvas.drawBitmap(icon, point.x - icon.getWidth / 2, point.y - icon.getHeight / 2, null)
    }
  }
}

class RouteStopNameOverlayManagerOSM(resources: Resources) {
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

  class RouteStopNameOverlay(context: Context, name: String, geoPoint: GeoPoint) extends Overlay(context) {
    final val X_OFFSET = 15
    final val Y_OFFSET = 5

    override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
      if (!shadow && view.getZoomLevel >= RouteMapLike.ZOOM_SHOW_STOP_NAMES) {
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
class RealVehicleLocationOverlayOSM(context: Context, snapped: Pt, real: Pt) extends Overlay(context) {
  override def draw(canvas: Canvas, view: MapView, shadow: Boolean) {
    if (!shadow) {
      val realPoint = new Point
      view.getProjection.toPixels(new GeoPoint((real.y * 1e6).toInt, (real.x * 1e6).toInt), realPoint)

      val snappedPoint = new Point
      view.getProjection.toPixels(new GeoPoint((snapped.y * 1e6).toInt, (snapped.x * 1e6).toInt), snappedPoint)

      val pen = new Paint()
      canvas.drawLine(realPoint.x, realPoint.y, snappedPoint.x, snappedPoint.y, pen)
    }
  }
}

class MapBalloonControllerOSM(context: Context, mapView: MapView) {

  val closeBalloonOverlay: Overlay = new Overlay(context) {
    override def onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean = {
      if (balloon != null)
        hideBalloon()
      false
    }

    def draw(p1: Canvas, p2: MapView, p3: Boolean) {}
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
      at, MapView.LayoutParams.BOTTOM_CENTER, 0, 0
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

    /*balloon.post { () =>
      if (balloon != null) {
        ensureBalloonIsFullyVisible()
      }
    }*/
  }

  def isViewShown(view: View): Boolean = view eq balloon

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
    val geoCenter = mapView.getMapCenter
    val pt = new Point
    mapView.getProjection.toPixels(geoCenter, pt)

    // Correct center coordinates to make balloon fully visible.
    if (balloon.getLeft < mapView.getLeft) {
      pt.x -= mapView.getLeft - balloon.getLeft
    }
    if (balloon.getTop < mapView.getTop) {
      pt.y -= mapView.getTop - balloon.getTop
    }

    // Make bottom and right edges visible, but left and top edges have
    // priority - don't hide them.
    if (balloon.getRight > mapView.getRight) {
      pt.x += Math.min(balloon.getLeft - mapView.getLeft, balloon.getRight - mapView.getRight)
    }
    if (balloon.getBottom > mapView.getBottom) {
      pt.y += Math.min(balloon.getTop - mapView.getTop, balloon.getBottom - mapView.getBottom)
    }

    // Animate map scroll.
    mapView.getController.animateTo(mapView.getProjection.fromPixels(pt.x, pt.y))
  }

  private[this] var balloon: View = _
}

class VehiclesOverlayOSM(
                          context: RouteMapOSMActivity, resources: Resources, balloonController: MapBalloonControllerOSM
                        ) extends ItemizedOverlay[OverlayItem](
  resources.getDrawable(R.drawable.vehicle_stopped_marker),
  new DefaultResourceProxyImpl(context)
) {
  def boundCenterBottom(marker: Drawable): Drawable = boundToHotspot(marker, HotspotPlace.BOTTOM_CENTER)

  val vehicleUnknown: Drawable = resources.getDrawable(R.drawable.vehicle_stopped_marker)

  // Information to identify vehicle for which balloon is shown.
  var balloonVehicle: (VehicleType.Value, String, Int) = _
  val balloon: View = balloonController.inflateView(R.layout.map_vehicle_popup)

  var infos: Seq[(VehicleInfo, Pt, Option[Double], Int)] = Seq()

  /**
   * @note This method manages balloon view, so it must be called from UI thread.
   */
  def setVehicles(vehicles: Seq[(VehicleInfo, Pt, Option[Double], Int)]) {
    infos = vehicles
    populate()

    if (balloonVehicle != null && balloonController.isViewShown(balloon)) {
      // Find vehicle to show balloon for.
      infos.indexWhere { case (info, _, _, _) => (info.vehicleType, info.routeId, info.scheduleNr) == balloonVehicle } match {
        case -1 => balloonController.hideView(balloon)
        case pos => showBalloon(infos(pos)._1, getItem(pos))
      }
    }
  }

  def clear() {
    setVehicles(Seq())
  }

  def size: Int = infos.length

  def createItem(pos: Int): OverlayItem = {
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
    val geoPoint = new GeoPoint((point.y * 1e6).toInt, (point.x * 1e6).toInt)
    val item = new OverlayItem(null, null, geoPoint)
    item.setMarker(boundCenterBottom(marker))
    item
  }

  populate()

  private def findItem(event: MotionEvent, mapView: MapView): Int = {
    val pj = mapView.getProjection
    val eventX = event.getX.toInt
    val eventY = event.getY.toInt
    val point = new Point
    val itemPoint = new Point

    /* These objects are created to avoid construct new ones every cycle. */
    pj.fromMapPixels(eventX, eventY, point)

    for (i <- 0 until size) {
      val item = getItem(i)
      val marker = item.getMarker(0)
      pj.toPixels(item.getPoint, itemPoint)

      if (hitTest(item, marker, point.x - itemPoint.x, point.y - itemPoint.y)) {
        return i
      }
    }

    -1
  }

  override def onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean = {
    val idx = findItem(e, mapView)
    if (idx != -1)
      onTap(idx)
    idx != -1
  }

  /*override*/ def onTap(index: Int): Boolean = {
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

  def onSnapToItem(p1: Int, p2: Int, p3: Point, p4: IMapView): Boolean = false

  // Markers on map are too big and poorly scaled due to bug http://code.google.com/p/osmdroid/issues/detail?id=331
  // Bug is fixed, but after 3.0.8, so I override boundToHotspot to work around.
  // The only change is removal of "* mScale", see http://code.google.com/p/osmdroid/source/detail?r=1099
  protected override def boundToHotspot(marker: Drawable, hotspot: HotspotPlace): Drawable = synchronized {
    val markerWidth = marker.getIntrinsicWidth
    val markerHeight = marker.getIntrinsicHeight

    val rect = new Rect
    rect.set(0, 0, 0 + markerWidth, 0 + markerHeight)

    hotspot match {
      case HotspotPlace.CENTER => rect.offset(-markerWidth / 2, -markerHeight / 2)
      case null | HotspotPlace.BOTTOM_CENTER => rect.offset(-markerWidth / 2, -markerHeight)
      case HotspotPlace.TOP_CENTER => rect.offset(-markerWidth / 2, 0)
      case HotspotPlace.RIGHT_CENTER => rect.offset(-markerWidth, -markerHeight / 2)
      case HotspotPlace.LEFT_CENTER => rect.offset(0, -markerHeight / 2)
      case HotspotPlace.UPPER_RIGHT_CORNER => rect.offset(-markerWidth, 0)
      case HotspotPlace.LOWER_RIGHT_CORNER => rect.offset(-markerWidth, -markerHeight)
      case HotspotPlace.UPPER_LEFT_CORNER => rect.offset(0, 0)
      case HotspotPlace.LOWER_LEFT_CORNER => rect.offset(0, -markerHeight)
      case HotspotPlace.NONE =>
    }
    marker.setBounds(rect)

    marker
  }
}