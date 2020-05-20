package net.kriomant.gortrans

import android.content.{Context, Intent}
import android.os.Bundle
import android.support.v4.widget.CursorAdapter
import android.support.v7.app.ActionBarActivity
import android.view.{Menu, MenuItem, View}
import android.widget.AdapterView.OnItemClickListener
import android.widget.{AdapterView, ListAdapter, ListView, TextView}
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.utils.closing

object RouteInfoActivity {
  private[this] val CLASS_NAME = classOf[RouteInfoActivity].getName
  private final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
  private final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
  private final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"

  def createIntent(caller: Context, routeId: String, routeName: String, vehicleType: VehicleType.Value): Intent = {
    val intent = new Intent(caller, classOf[RouteInfoActivity])
    intent.putExtra(EXTRA_ROUTE_ID, routeId)
    intent.putExtra(EXTRA_ROUTE_NAME, routeName)
    intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
    intent
  }
}

class RouteInfoActivity extends ActionBarActivity with BaseActivity {

  import RouteInfoActivity._

  private[this] final val TAG = "RouteInfoActivity"

  private[this] var stopsCursor: Database.FoldedRouteStopsTable.Cursor = _

  private[this] var listView: ListView = _
  private[this] var optionsMenu: Menu = _
  private[this] var dataManager: DataManager = _

  private[this] var routeId: String = _
  private[this] var routeName: String = _
  private[this] var vehicleType: VehicleType.Value = _
  private[this] var routeBegin: String = _
  private[this] var routeEnd: String = _

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

    setContentView(R.layout.route_info)

    listView = findViewById(android.R.id.list).asInstanceOf[ListView]
//    listView.setOnItemClickListener(new OnItemClickListener {
//      def onItemClick(p1: AdapterView[_], p2: View, p3: Int, p4: Long) {
//        onListItemClick(p1, p2, p3, p4)
//      }
//    })

    // Disable list item dividers so that route stop icons
    // together look like solid route line.
    listView.setDivider(null)

    val intent = getIntent
    routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
    routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
    vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

    val actionBar = getSupportActionBar
    actionBar.setDisplayHomeAsUpEnabled(true)
    actionBar.setTitle(RouteListBaseActivity.getRouteTitle(this, vehicleType, routeName))
    actionBar.setSubtitle(R.string.route)

    dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
  }

  override def onStart() {
    super.onStart()
    loadRouteInfo()
  }

  def loadRouteInfo() {
    dataManager.requestRoutesList(
      new ForegroundProcessIndicator(this, loadRouteInfo),
      new ActionBarProcessIndicator(this)
    ) {
      val db = getApplication.asInstanceOf[CustomApplication].database
      closing(db.fetchRoute(vehicleType, routeId)) { cursor =>
        routeBegin = cursor.firstStopName
        routeEnd = cursor.lastStopName
      }
      loadRoutePoints()
    }
  }

  def loadRoutePoints() {
    dataManager.requestRoutePoints(
      vehicleType, routeId, routeName, routeBegin, routeEnd,
      new ForegroundProcessIndicator(this, loadRoutePoints),
      new ActionBarProcessIndicator(this)
    ) {
      if (stopsCursor == null) {
        val db = getApplication.asInstanceOf[CustomApplication].database

        stopsCursor = db.fetchRouteStops(vehicleType, routeId)
        startManagingCursor(stopsCursor)

        val listAdapter = new RouteStopsAdapter(this, stopsCursor)
        listView.setAdapter(listAdapter)

      } else {
        stopsCursor.requery()
      }

      if (stopsCursor.getCount != 0) {
        findViewById(R.id.error_message).setVisibility(View.GONE)
        listView.setVisibility(View.VISIBLE)
      } else {
        val view = findViewById(R.id.error_message).asInstanceOf[TextView]
        view.setText(R.string.no_route_points)
        view.setVisibility(View.VISIBLE)
        listView.setVisibility(View.GONE)
      }

      if (optionsMenu != null) {
        optionsMenu.findItem(R.id.show_map).setEnabled(stopsCursor.getCount != 0)
      }
    }

    dataManager.requestStopsList(
      new ForegroundProcessIndicator(this, loadRoutePoints),
      new ActionBarProcessIndicator(this)
    ) {}
  }

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    super.onCreateOptionsMenu(menu)
    getMenuInflater.inflate(R.menu.route_info_menu, menu)
    optionsMenu = menu
    true
  }

  override def onPrepareOptionsMenu(menu: Menu): Boolean = {
    super.onPrepareOptionsMenu(menu)
    menu.findItem(R.id.show_map).setEnabled(stopsCursor != null && stopsCursor.getCount > 0)
    true
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
    case R.id.show_map =>
      val intent = RouteMapLike.createIntent(this, routeId, routeName, vehicleType)
      startActivity(intent)
      true
    case android.R.id.home =>
      val intent = MainActivity.createIntent(this, vehicleType)
      intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      startActivity(intent)
      true
    case _ => false
  }

  def onListItemClick(l: AdapterView[_], v: View, position: Int, id: Long) {
    stopsCursor.moveToPosition(position)
    val stopName = stopsCursor.name

    openStopInfo(stopName)
  }

  private[this] def openStopInfo(stopName: String) {
    dataManager.requestStopsList(
      new ForegroundProcessIndicator(this, () => openStopInfo(stopName)),
      new ActionBarProcessIndicator(this)
    ) {
      val fixedStopName = core.fixStopName(vehicleType, routeName, stopName)
      val db = getApplication.asInstanceOf[CustomApplication].database
      val stopId = db.findStopId(fixedStopName).getOrElse(-1)

      val intent = RouteStopInfoActivity.createIntent(this, routeId, routeName, vehicleType, stopId, stopName)
      startActivity(intent)
    }
  }
}

class RouteStopsAdapter(val context: Context, cursor: Database.FoldedRouteStopsTable.Cursor)
  extends CursorAdapter(context, cursor)
    with EasyCursorAdapter[Database.FoldedRouteStopsTable.Cursor]
    with ListAdapter {
  val itemLayout: Int = R.layout.route_info_item

  def findSubViews(view: View): SubViews = SubViews(
    icon = view.findViewById(R.id.route_stop_icon),
    name = view.findViewById(R.id.route_stop_name).asInstanceOf[TextView]
  )

  def adjustItem(cursor: Database.FoldedRouteStopsTable.Cursor, views: SubViews) {
    val LAST = cursor.getCount - 1
    views.icon.setBackgroundResource((cursor.getPosition, cursor.directions) match {
      case (0, DirectionsEx.Forward) => R.drawable.first_forth_only_stop
      case (0, DirectionsEx.Backward) => R.drawable.first_back_only_stop
      case (0, DirectionsEx.Both) => R.drawable.first_back_and_forth_stop

      case (LAST, DirectionsEx.Forward) => R.drawable.last_forth_only_stop
      case (LAST, DirectionsEx.Backward) => R.drawable.last_back_only_stop
      case (LAST, DirectionsEx.Both) => R.drawable.last_back_and_forth_stop

      case (_, DirectionsEx.Forward) => R.drawable.forth_only_stop
      case (_, DirectionsEx.Backward) => R.drawable.back_only_stop
      case (_, DirectionsEx.Both) => R.drawable.back_and_forth_stop
    })
    views.name.setText(cursor.name)
  }

  case class SubViews(icon: View, name: TextView)

}
