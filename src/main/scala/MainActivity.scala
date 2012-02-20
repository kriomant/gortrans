package net.kriomant.gortrans

import _root_.android.os.Bundle

import android.app.ListActivity
import android.widget.SimpleAdapter
import scala.collection.JavaConverters._
import net.kriomant.gortrans.core.VehicleType

class MainActivity extends ListActivity with TypedActivity {
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    //setContentView(R.layout.main)

	  val client = new Client
	  val routesInfo = client.getRoutesList()

	  val vehicleTypeNames = Map(
	    VehicleType.Bus -> R.string.bus,
	    VehicleType.TrolleyBus -> R.string.trolleybus,
	    VehicleType.TramWay -> R.string.tramway,
	    VehicleType.MiniBus -> R.string.minibus
	  ).mapValues(getString)

		val data = routesInfo.values.flatten.toSeq.map { r =>
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
}
