package net.kriomant.gortrans

import _root_.android.os.Bundle

import android.app.ListActivity
import net.kriomant.gortrans.parsing.{RouteStop, RoutePoint}
import android.view.View.OnClickListener
import android.view.View
import android.content.{Context, Intent}
import android.widget.{ListView, TextView, ListAdapter}
import net.kriomant.gortrans.core._
import com.actionbarsherlock.app.SherlockListActivity
import com.actionbarsherlock.view.{MenuItem, Menu}

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

class RouteInfoActivity extends SherlockListActivity with TypedActivity {
	import RouteInfoActivity._

	private[this] final val TAG = "RouteInfoActivity"

	private[this] var dataManager: DataManager = null

	private[this] var foldedRoute: Seq[FoldedRouteStop[String]] = null
	private[this] var routeId: String = null
	private[this] var routeName: String = null
	private[this] var vehicleType: VehicleType.Value = null

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		setContentView(R.layout.route_info)

		dataManager = getApplication.asInstanceOf[CustomApplication].dataManager

		// Disable list item dividers so that route stop icons
		// together look like solid route line.
		getListView.setDivider(null)

		val intent = getIntent
		routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

		val routeNameFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_n,
			VehicleType.TrolleyBus -> R.string.trolleybus_n,
			VehicleType.TramWay -> R.string.tramway_n,
			VehicleType.MiniBus -> R.string.minibus_n
		).mapValues(getString)

		val actionBar = getSupportActionBar
		actionBar.setDisplayHomeAsUpEnabled(true)
		actionBar.setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))
		actionBar.setSubtitle(R.string.route)

		val routePoints = dataManager.getRoutePoints(vehicleType, routeId, routeName)
		if (routePoints.nonEmpty) {

			val stopNames = routePoints.collect {
				case RoutePoint(Some(RouteStop(name, _)), _, _) => name
			}
			foldedRoute = core.foldRoute(stopNames, identity)

			val listAdapter = new RouteStopsAdapter(this, foldedRoute)
			setListAdapter(listAdapter)
		} else {
			val view = findView(TR.error_message)
			view.setText(R.string.no_route_points)
			view.setVisibility(View.VISIBLE)
		}
	}

	override def onCreateOptionsMenu(menu: Menu): Boolean = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.route_info_menu, menu)
		true
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case R.id.show_map => {
			val intent = RouteMapActivity.createIntent(this, routeId, routeName, vehicleType)
			startActivity(intent)
			true
		}
		case android.R.id.home => {
			val intent = MainActivity.createIntent(this)
			intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(intent)
			true
		}
		case _ => false
	}

	override def onListItemClick(l: ListView, v: View, position: Int, id: Long) {
		val stopName = foldedRoute(position).name
		val stopsMap = dataManager.getStopsList()

		val fixedStopName = core.fixStopName(vehicleType, routeName, stopName)
		val stopId = stopsMap.getOrElse(fixedStopName, -1)

		val intent = RouteStopInfoActivity.createIntent(this, routeId, routeName, vehicleType, stopId, stopName)
		startActivity(intent)
	}
}

class RouteStopsAdapter(val context: Context, foldedRoute: Seq[FoldedRouteStop[String]])
	extends ListAdapter with EasyAdapter with SeqAdapter {

	val items = foldedRoute
	val itemLayout = R.layout.route_info_item
	case class SubViews(icon: View, name: TextView)

	def findSubViews(view: View) = SubViews(
		icon = view.findViewById(R.id.route_stop_icon).asInstanceOf[View],
		name = view.findViewById(R.id.route_stop_name).asInstanceOf[TextView]
	)

	def adjustItem(position: Int, views: SubViews) {
		views.icon.setBackgroundResource(position match {
			case 0 => R.drawable.first_stop
			case x if x == foldedRoute.length-1 => R.drawable.last_stop
			case x => foldedRoute(x).directions match {
				case DirectionsEx.Forward => R.drawable.forth_only_stop
				case DirectionsEx.Backward => R.drawable.back_only_stop
				case DirectionsEx.Both => R.drawable.back_and_forth_stop
			}
		})
		views.name.setText(foldedRoute(position).name)
	}
}
