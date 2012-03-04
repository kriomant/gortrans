package net.kriomant.gortrans

import _root_.android.os.Bundle

import android.app.ListActivity
import net.kriomant.gortrans.parsing.{RouteStop, RoutePoint}
import android.view.View.OnClickListener
import android.database.DataSetObserver
import android.view.{LayoutInflater, ViewGroup, View}
import android.content.{Context, Intent}
import android.widget.{ListView, ImageView, TextView, ListAdapter}
import net.kriomant.gortrans.core._

object RouteInfoActivity {
	private[this] val CLASS_NAME = classOf[RouteInfoActivity].getName

	final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
}

class RouteInfoActivity extends ListActivity with TypedActivity {
	import RouteInfoActivity._

	private[this] final val TAG = "RouteInfoActivity"
	
	private[this] var foldedRoute: Seq[(String, DirectionsEx.Value)] = null
	private[this] var routeId: String = null
	private[this] var routeName: String = null
	private[this] var vehicleType: VehicleType.Value = null

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		setContentView(R.layout.route_info)

		// Disable list item dividers so that route stop icons
		// together look like solid route line.
		getListView.setDivider(null)

		val intent = getIntent
		routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

		val routeNameFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_route,
			VehicleType.TrolleyBus -> R.string.trolleybus_route,
			VehicleType.TramWay -> R.string.tramway_route,
			VehicleType.MiniBus -> R.string.minibus_route
		).mapValues(getString)

		setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))
		
		val showRouteMapButton = findView(TR.show_route_map)
		showRouteMapButton.setOnClickListener(new OnClickListener {
			def onClick(view: View) {
				val intent = new Intent(RouteInfoActivity.this, classOf[RouteMapActivity])
				intent.putExtra(RouteMapActivity.EXTRA_ROUTE_ID, routeId)
				intent.putExtra(RouteMapActivity.EXTRA_ROUTE_NAME, routeName)
				intent.putExtra(RouteMapActivity.EXTRA_VEHICLE_TYPE, vehicleType.id)
				startActivity(intent)
			}
		})
		
		implicit val context = this
		val routePoints = DataManager.getRoutePoints(vehicleType, routeId)
		val routeInfo = DataManager.getRoutesList().apply(vehicleType).find(r => r.id == routeId).get

		val stopNames = routePoints.collect {
			case RoutePoint(Some(RouteStop(name, _)), _, _) => name
		}
		foldedRoute = core.foldRoute(routeInfo, stopNames)

		val listAdapter = new RouteStopsAdapter(this, foldedRoute)
		setListAdapter(listAdapter)
	}

	override def onListItemClick(l: ListView, v: View, position: Int, id: Long) {
		implicit val context: Context = this

		val stopName = foldedRoute(position)._1
		val stopsMap = DataManager.getStopsList()
		val stopId = stopsMap(stopName)

		val intent = new Intent(this, classOf[StopScheduleActivity])
		intent.putExtra(StopScheduleActivity.EXTRA_ROUTE_ID, routeId)
		intent.putExtra(StopScheduleActivity.EXTRA_ROUTE_NAME, routeName)
		intent.putExtra(StopScheduleActivity.EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent.putExtra(StopScheduleActivity.EXTRA_STOP_ID, stopId)
		intent.putExtra(StopScheduleActivity.EXTRA_STOP_NAME, stopName)
		startActivity(intent)
	}
}

class RouteStopsAdapter(context: Context, foldedRoute: Seq[(String, DirectionsEx.Value)]) extends ListAdapter {
	case class Tag(icon: ImageView, name: TextView)
	
	// Data won't change, so observers are unneeded.
	def registerDataSetObserver(p1: DataSetObserver) {}
	def unregisterDataSetObserver(p1: DataSetObserver) {}

	def getCount: Int = foldedRoute.length
	def isEmpty: Boolean = foldedRoute.isEmpty

	def getItem(position: Int): AnyRef = null
	def getItemId(position: Int): Long = position
	def hasStableIds: Boolean = false

	def getView(position: Int, convertView: View, parent: ViewGroup): View = {
		val (view, tag) = if (convertView == null) {
			val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater]
			val view = inflater.inflate(R.layout.route_info_item, parent, false)
			val tag = Tag(
				icon = view.findViewById(R.id.route_stop_icon).asInstanceOf[ImageView],
				name = view.findViewById(R.id.route_stop_name).asInstanceOf[TextView]
			)
			view.setTag(tag)
			(view, tag)
		} else {
			(convertView, convertView.getTag.asInstanceOf[Tag])
		}
		
		tag.icon.setImageResource(position match {
			case 0 => R.drawable.first_stop
			case x if x == foldedRoute.length-1 => R.drawable.last_stop
			case x => foldedRoute(x)._2 match {
				case DirectionsEx.Forward => R.drawable.forth_only_stop
				case DirectionsEx.Backward => R.drawable.back_only_stop
				case DirectionsEx.Both => R.drawable.back_and_forth_stop
			}
		})
		tag.name.setText(foldedRoute(position)._1)

		view
	}

	def getItemViewType(position: Int): Int = 0
	def getViewTypeCount: Int = 1

	def areAllItemsEnabled(): Boolean = true
	def isEnabled(position: Int): Boolean = true
}
