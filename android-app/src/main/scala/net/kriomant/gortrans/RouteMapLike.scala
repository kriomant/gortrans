package net.kriomant.gortrans

import android.content.{Context, Intent}
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.{CompoundButton, ImageButton, Toast, ToggleButton}
import net.kriomant.gortrans.CursorIterator.cursorUtils
import net.kriomant.gortrans.VehiclesWatcher.Listener
import net.kriomant.gortrans.core.{Direction, FoldedRouteStop, VehicleType}
import net.kriomant.gortrans.geometry.{Point => Pt}
import net.kriomant.gortrans.parsing.{VehicleInfo, VehicleSchedule}
import net.kriomant.gortrans.utils.{closing, functionAsRunnable}
import org.json.JSONObject

import scala.collection.mutable

object RouteMapLike {
  private[this] val CLASS_NAME = classOf[RouteMapLike].getName
  final val TAG = CLASS_NAME

  final val EXTRA_ROUTE_ID = "ROUTE_ID"
  final val EXTRA_ROUTE_NAME = "ROUTE_NAME"
  final val EXTRA_VEHICLE_TYPE = "VEHICLE_TYPE"
  final val EXTRA_GROUP_ID = "VEHICLE_TYPE"

  private def getMapActivityClass(context: Context): Class[_] = {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val use_new_map = prefs.getBoolean(SettingsActivity.KEY_USE_NEW_MAP, false)
    if (use_new_map)
      classOf[RouteMapV2Activity]
    else if (SettingsActivity.isOldGoogleMapAvailable(context))
      classOf[RouteMapActivity]
    else
      classOf[RouteMapOSMActivity]
  }

  def createIntent(caller: Context, routeId: String, routeName: String, vehicleType: VehicleType.Value): Intent = {
    val intent = new Intent(caller, getMapActivityClass(caller))
    intent.putExtra(EXTRA_ROUTE_ID, routeId)
    intent.putExtra(EXTRA_ROUTE_NAME, routeName)
    intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
    intent
  }

  def createShowGroupIntent(context: Context, groupId: Long): Intent = {
    val intent = new Intent(context, getMapActivityClass(context))
    intent.putExtra(EXTRA_GROUP_ID, groupId)
    intent
  }

  // MapView's zoom level at which whole or significant part of route
  // is visible.
  val ZOOM_WHOLE_ROUTE = 14
  val ZOOM_SHOW_STOP_NAMES = 17

  final val PHYSICAL_ROUTE_STROKE_WIDTH: Float = 3 // meters
  final val MIN_ROUTE_STROKE_WIDTH: Float = 2 // pixels

  case class RouteInfo(
                        forwardRoutePoints: Seq[Pt],
                        backwardRoutePoints: Seq[Pt],

                        bounds: RectF,
                        stops: Set[(Pt, String)], // (position, name)
                        stopNames: Set[(Pt, String)],

                        color: Int
                      )

  // Camera position.
  // Modelled after Google Maps V2 CameraPosition.
  case class MapCameraPosition(
                                latitude: Double,
                                longitude: Double,
                                zoom: Float,
                                bearing: Float = 0,
                                tilt: Float = 0
                              ) {
    def toJson: JSONObject = {
      val json = new JSONObject
      json.put("version", 1)
      json.put("lat", latitude)
      json.put("lng", longitude)
      json.put("bearing", bearing)
      json.put("tilt", tilt)
      json.put("zoom", zoom)

      json
    }
  }

  object MapCameraPosition {
    def fromJson(json: JSONObject): MapCameraPosition = {
      assert(json.getInt("version") == 1)
      MapCameraPosition(
        latitude = json.getDouble("lat"),
        longitude = json.getDouble("lng"),
        bearing = json.getDouble("bearing").toFloat,
        tilt = json.getDouble("tilt").toFloat,
        zoom = json.getDouble("zoom").toFloat
      )
    }
  }

}

trait RouteMapLike extends BaseActivity with TypedActivity with TrackLocation {

  import RouteMapLike._

  var groupId: Long = -1

  var rainbow: Rainbow = _

  private[this] var dataManager: DataManager = _
  var routesInfo: Set[core.Route] = _
  val routes: mutable.Map[(VehicleType.Value, String), RouteInfo] = mutable.Map()
  var vehiclesData: Seq[(VehicleInfo, Pt, Option[Double], Int)] = Seq()

  var vehiclesWatcher: VehiclesWatcher = _
  private[this] var trackVehiclesToggle: ToggleButton = _
  var updatingVehiclesLocationIsOn: Boolean = true

  // Hack to avoid repositioning map when activity is recreated due to
  // configuration change. TODO: normal solution.
  var hasOldState: Boolean = false

  def isInitialized: Boolean = true

  def createProcessIndicator(): DataManager.ProcessIndicator

  def removeAllRouteOverlays()

  def createRouteOverlays(routeInfo: core.Route, routeParams: RouteInfo)

  def announceRoutes(routes: Seq[(core.Route, /* color */ Int)]) {}

  def getMapCameraPosition: MapCameraPosition

  def restoreMapCameraPosition(position: MapCameraPosition)

  def navigateTo(left: Double, top: Double, right: Double, bottom: Double)

  def navigateTo(latitude: Double, longitude: Double)

  def setTitle(title: String)

  def startBackgroundProcessIndication()

  def stopBackgroundProcessIndication()

  def clearVehicleMarkers()

  def setVehicles(vehicles: Seq[(VehicleInfo, Pt, Option[Double], Int)])

  def setLocationMarker(location: Location)

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    rainbow = Rainbow(getResources.getColor(R.color.forward_vehicle))
    dataManager = getApplication.asInstanceOf[CustomApplication].dataManager

    hasOldState = savedInstanceState != null
  }

  override def onPostCreate(savedInstanceState: Bundle) {
    super.onPostCreate(savedInstanceState)

    if (!isInitialized)
      return

    trackVehiclesToggle = findViewById(R.id.track_vehicles).asInstanceOf[ToggleButton]
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

    val gpsToggle = findViewById(R.id.toggle_gps).asInstanceOf[ToggleButton]
    gpsToggle.setOnCheckedChangeListener(new OnCheckedChangeListener {
      def onCheckedChanged(button: CompoundButton, checked: Boolean) {
        setGpsEnabled(checked)
      }
    })

    val showMyLocationButton = findViewById(R.id.show_my_location).asInstanceOf[ImageButton]
    showMyLocationButton.setOnClickListener(new OnClickListener {
      def onClick(view: View) {
        val location = currentLocation
        if (location != null) {
          navigateTo(location.getLatitude, location.getLongitude)
        }
      }
    })

    onNewIntent(getIntent)
  }

  override def onNewIntent(intent: Intent) {
    setIntent(intent)
    hasOldState = false

    if (vehiclesWatcher != null)
      vehiclesWatcher.stopUpdatingVehiclesLocation()
    vehiclesWatcher = null

    routes.clear()
    removeAllRouteOverlays()

    val db = getApplication.asInstanceOf[CustomApplication].database

    groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1)
    if (groupId != -1) {
      val (groupName, dbRouteIds) = db.transaction {
        (db.getGroupName(groupId), db.getGroupItems(groupId))
      }
      routesInfo = dbRouteIds map { rid =>
        closing(db.fetchRoute(rid)) { c =>
          core.Route(c.vehicleType, c.externalId, c.name, c.firstStopName, c.lastStopName)
        }
      }

      setTitle(groupName)

      loadRoutesData()

    } else {
      // Get route reference.
      val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
      val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
      val vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

      Log.d(TAG, "New intent received, route ID: %s" format routeId)

      routesInfo = Set(core.Route(vehicleType, routeId, routeName, "", ""))

      setTitle(RouteListBaseActivity.getRouteTitle(this, vehicleType, routeName))

      loadRouteInfo(vehicleType, routeId, routeName)
    }

    announceRoutes(routesInfo.toSeq.zipWithIndex map { case (r, i) => (r, rainbow(i)) })

    vehiclesWatcher = new VehiclesWatcher(this, routesInfo.map(r => (r.vehicleType, r.id, r.name)), new Listener {
      def onVehiclesLocationUpdated(vehicles: Either[String, Seq[VehicleInfo]]) {
        RouteMapLike.this.onVehiclesLocationUpdated()
      }

      def onVehiclesLocationUpdateCancelled() {
        RouteMapLike.this.onVehiclesLocationUpdateCancelled()
      }

      def onVehiclesLocationUpdateStarted() {
        RouteMapLike.this.onVehiclesLocationUpdateStarted()
      }
    })

    vehiclesWatcher.subscribe(updateVehiclesLocation)

    if (!hasOldState) {
      val rootView = getWindow.getDecorView
      rootView.getViewTreeObserver.addOnGlobalLayoutListener(new OnGlobalLayoutListener {
        def onGlobalLayout() {
          rootView.getViewTreeObserver.removeGlobalOnLayoutListener(this)

          val savedCameraPosition = if (groupId != -1) db.loadGroupMapPosition(groupId) else None
          savedCameraPosition match {
            case Some(pos) => restoreMapCameraPosition(MapCameraPosition.fromJson(new JSONObject(pos)))
            case None => showWholeRoutes()
          }
        }
      })
    }
  }

  def showWholeRoutes() {
    if (routes.nonEmpty) {
      // If some routes information is available when activity is started, then move
      // map camera so that all loaded routes are fully visible.
      var bounds: RectF = null
      routes.values foreach { route =>
        // For some reason, RectF.union doesn't work as expected.
        if (bounds == null) {
          bounds = new RectF(route.bounds)
        } else {
          bounds.left = math.min(bounds.left, route.bounds.left)
          bounds.bottom = math.min(bounds.bottom, route.bounds.bottom)
          bounds.right = math.max(bounds.right, route.bounds.right)
          bounds.top = math.max(bounds.top, route.bounds.top)
        }
        Log.d(TAG, "Route: %s, bounds: %s" format(route.bounds, bounds))
      }
      Log.d(TAG, "Show whole routes: %s" format bounds)
      navigateTo(bounds.left, bounds.top, bounds.right, bounds.bottom)

    } else {
      // Otherwise show whole city.
      Log.d(TAG, "There are no loaded routes, show whole city")
      navigateTo(82.76, 55.202, 83.16, 54.835)
    }
  }

  override def onResume() {
    super.onResume()

    if (updatingVehiclesLocationIsOn && vehiclesWatcher != null)
      vehiclesWatcher.startUpdatingVehiclesLocation()
  }

  override def onPause() {
    if (updatingVehiclesLocationIsOn && vehiclesWatcher != null)
      vehiclesWatcher.stopUpdatingVehiclesLocation()

    if (groupId != -1) {
      Log.d(TAG, s"Save camera position for group #$groupId")
      val pos = getMapCameraPosition
      val db = getApplication.asInstanceOf[CustomApplication].database
      db.saveGroupMapPosition(groupId, pos.toJson.toString)
    }

    super.onPause()
  }

  def loadRouteInfo(vehicleType: VehicleType.Value, routeId: String, routeName: String) {
    val db = getApplication.asInstanceOf[CustomApplication].database

    dataManager.requestRoutesList(
      new ForegroundProcessIndicator(this, () => loadRouteInfo(vehicleType, routeId, routeName)),
      createProcessIndicator()
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
    routesInfo.zipWithIndex foreach Function.tupled(loadData)
  }

  def loadData(routeInfo: core.Route, index: Int) {
    // Load route details.
    dataManager.requestRoutePoints(
      routeInfo.vehicleType, routeInfo.id, routeInfo.name, routeInfo.begin, routeInfo.end,
      new ForegroundProcessIndicator(this, () => loadData(routeInfo, index)),
      createProcessIndicator()
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
        val forwardRoutePoints = points.slice(0, firstBackwardPointIndex + 1)
        val backwardRoutePoints = points.slice(firstBackwardPointIndex, points.length) :+ points.head
        assert(forwardRoutePoints.last == backwardRoutePoints.head)
        assert(backwardRoutePoints.last == forwardRoutePoints.head)

        // Calculate rectangle (and it's center) containing whole route.
        val top = points.map(_.y).max
        val left = points.map(_.x).min
        val bottom = points.map(_.y).min
        val right = points.map(_.x).max
        val bounds = new RectF(left.toFloat, top.toFloat, right.toFloat, bottom.toFloat)

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

        val routeParams = RouteInfo(
          forwardRoutePoints, backwardRoutePoints,
          bounds, routeStops.toSet, stopNames.toSet,
          rainbow(index)
        )

        createRouteOverlays(routeInfo, routeParams)
        routes((routeInfo.vehicleType, routeInfo.id)) = routeParams
      }
    }
  }

  def onVehiclesLocationUpdateStarted() {
    startBackgroundProcessIndication()
  }

  def onVehiclesLocationUpdateCancelled() {
    stopBackgroundProcessIndication()

    clearVehicleMarkers()
  }

  def onVehiclesLocationUpdated() {
    stopBackgroundProcessIndication()
  }

  def onLocationUpdated(location: Location) {
    Log.d("RouteMapActivity", "Location updated: %s" format location)
    setLocationMarker(location)

    findViewById(R.id.show_my_location).setVisibility(if (location != null) View.VISIBLE else View.INVISIBLE)
  }

  def updateVehiclesLocation(result: Either[String, Seq[VehicleInfo]]) {
    result match {
      case Right(vehicles) =>
        val vehiclesPointsAndAngles = android_utils.measure(TAG, "snapping %d vehicles" format vehicles.length) {
          vehicles map { v =>
            routes.get((v.vehicleType, v.routeId)) match {
              case None => (v, Pt(v.latitude, v.longitude), Some(v.azimuth / 180.0 * math.Pi), getResources.getColor(R.color.forward_vehicle))
              case Some(route) =>
                val (pt, segment) = v.direction match {
                  case Some(Direction.Forward) => core.snapVehicleToRoute(v, route.forwardRoutePoints)
                  case Some(Direction.Backward) => core.snapVehicleToRoute(v, route.backwardRoutePoints)
                  case None => (Pt(v.longitude, v.latitude), None)
                }

                val angle = segment map { s =>
                  math.atan2(s._2.y - s._1.y, s._2.x - s._1.x) * 180 / math.Pi
                }
                (v, pt, angle, route.color)
            }
          }
        }

        vehiclesData = vehiclesPointsAndAngles

        runOnUiThread { () =>
          setVehicles(vehiclesData)
        }

      case Left(message) =>
        vehiclesData = Seq()
        runOnUiThread { () =>
          setVehicles(vehiclesData)
          Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
  }

  def getRouteStrokeWidth(zoomLevel: Float): Float = {
    // From getZoomLevel documentation: "At zoom level 1 (fully zoomed out), the equator of the
    // earth is 256 pixels long. Each successive zoom level is magnified by a factor of 2."
    // We want to fix physical width of route stroke (in meters), but make it still visible
    // on zoom level 1 by limiting minimum width in pixels.
    val metersPerPixel = (40e6 / (128 * math.pow(2, zoomLevel))).toFloat
    math.max(PHYSICAL_ROUTE_STROKE_WIDTH / metersPerPixel, MIN_ROUTE_STROKE_WIDTH)
  }

  def formatVehicleSchedule(vehicleInfo: parsing.VehicleInfo): String = {
    vehicleInfo.schedule match {
      case VehicleSchedule.Schedule(schedule) =>
        schedule.map { case (time, stop) => getString(R.string.vehicle_schedule_row, time, stop) }.mkString("\n")
      case VehicleSchedule.Status(status) => status
      case VehicleSchedule.NotProvided => ""
    }
  }
}