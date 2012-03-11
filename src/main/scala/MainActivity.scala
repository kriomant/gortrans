package net.kriomant.gortrans

import _root_.android.os.Bundle

import android.app.ListActivity
import scala.collection.JavaConverters._
import android.view.View
import net.kriomant.gortrans.core.{Route, VehicleType}
import android.widget.{ListView, SimpleAdapter}
import android.content.Intent

class MainActivity extends ListActivity with TypedActivity {
	private[this] final val TAG = "MainActivity"

	private[this] var routesList: Seq[Route] = null

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

	  val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		routesList = dataManager.getRoutesList().values.flatten.toSeq

	  val vehicleTypeNames = Map(
	    VehicleType.Bus -> R.string.bus,
	    VehicleType.TrolleyBus -> R.string.trolleybus,
	    VehicleType.TramWay -> R.string.tramway,
	    VehicleType.MiniBus -> R.string.minibus
	  ).mapValues(getString)

		val data = routesList.map { r =>
			Map(
				"number" -> getString(R.string.route_name_format,
					vehicleTypeNames(r.vehicleType),
					r.name
				),
				"description" -> getString(R.string.route_description_format, r.begin, r.end)
			).asJava
		}.asJava

	  val listAdapter = new SimpleAdapter(
			this, data,
			android.R.layout.two_line_list_item,
			Array("number", "description"),
			Array(android.R.id.text1, android.R.id.text2)
		)

	  setListAdapter(listAdapter)
  }

	override def onListItemClick(l: ListView, v: View, position: Int, id: Long) {
		val route = routesList(position)

		val intent = new Intent(this, classOf[RouteInfoActivity])
		intent.putExtra(RouteInfoActivity.EXTRA_ROUTE_ID, route.id)
		intent.putExtra(RouteInfoActivity.EXTRA_ROUTE_NAME, route.name)
		intent.putExtra(RouteInfoActivity.EXTRA_VEHICLE_TYPE, route.vehicleType.id)
		startActivity(intent)
	}

}
