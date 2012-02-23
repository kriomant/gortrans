package net.kriomant.gortrans

import _root_.android.os.Bundle

import android.app.ListActivity
import android.util.Log
import net.kriomant.gortrans.core.VehicleType
import net.kriomant.gortrans.utils.closing
import net.kriomant.gortrans.utils.readerUtils
import java.io._
import net.kriomant.gortrans.Client.{RouteDirection, RouteInfoRequest}
import net.kriomant.gortrans.parsing.{RouteStop, RoutePoint}
import android.widget.ArrayAdapter

object RouteInfoActivity {
	private[this] val CLASS_NAME = classOf[RouteInfoActivity].getName

	final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
}

class RouteInfoActivity extends ListActivity with TypedActivity {
	import RouteInfoActivity._

	private[this] final val TAG = "RouteInfoActivity"
	
	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		val intent = getIntent
		val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		val vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

		val routeNameFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_route,
			VehicleType.TrolleyBus -> R.string.trolleybus_route,
			VehicleType.TramWay -> R.string.tramway_route,
			VehicleType.MiniBus -> R.string.minibus_route
		).mapValues(getString)

		setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))
		
		implicit val context = this
		val routePoints = DataManager.getRoutePoints(vehicleType, routeId)

		val stopNames = routePoints.collect {
			case RoutePoint(Some(RouteStop(name, _)), _, _) => name
		} toArray

		val listAdapter = new ArrayAdapter[String](
			this,
			android.R.layout.simple_list_item_1, android.R.id.text1,
			stopNames
		)

		setListAdapter(listAdapter)
	}

}

