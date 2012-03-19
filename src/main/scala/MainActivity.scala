package net.kriomant.gortrans

import _root_.android.os.Bundle

import scala.collection.JavaConverters._
import android.view.View
import net.kriomant.gortrans.core.VehicleType
import android.content.Intent
import android.app.TabActivity
import android.widget.{AdapterView, TabHost, ListView, SimpleAdapter}
import android.widget.AdapterView.OnItemClickListener

class MainActivity extends TabActivity with TypedActivity {
	private[this] final val TAG = "MainActivity"

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

	  setContentView(R.layout.routes_list)

	  val tabHost = getTabHost

	  val vehicleTypeNames = Map(
		  VehicleType.Bus -> R.string.bus,
		  VehicleType.TrolleyBus -> R.string.trolleybus,
		  VehicleType.TramWay -> R.string.tramway,
		  VehicleType.MiniBus -> R.string.minibus
	  ).mapValues(getString)

	  val vehicleTypeDrawables = Map(
		  VehicleType.Bus -> R.drawable.tab_bus,
			VehicleType.TrolleyBus -> R.drawable.tab_trolleybus,
			VehicleType.TramWay -> R.drawable.tab_tram,
			VehicleType.MiniBus -> R.drawable.tab_minibus
	  ).mapValues(getResources.getDrawable(_))

	  val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		val tabs = dataManager.getRoutesList().map { p =>
			val (vehicleType, routes) = p

			val data = routes.map { r =>
				Map(
					"number" -> r.name,
					"begin" -> r.begin,
					"end" -> r.end
				).asJava
			}.asJava

			val factory = new TabHost.TabContentFactory {
				def createTabContent(p1: String) = {
					val listAdapter = new SimpleAdapter(
						MainActivity.this, data,
						R.layout.routes_list_item,
						Array("number", "begin", "end"),
						Array(R.id.route_name, R.id.start_stop_name, R.id.end_stop_name)
					)
					val list = new ListView(MainActivity.this)
					list.setAdapter(listAdapter)
					list.setOnItemClickListener(new OnItemClickListener {
						def onItemClick(adapterView: AdapterView[_], view: View, position: Int, id: Long) {
							val route = routes(position)

							val intent = new Intent(MainActivity.this, classOf[RouteInfoActivity])
							intent.putExtra(RouteInfoActivity.EXTRA_ROUTE_ID, route.id)
							intent.putExtra(RouteInfoActivity.EXTRA_ROUTE_NAME, route.name)
							intent.putExtra(RouteInfoActivity.EXTRA_VEHICLE_TYPE, route.vehicleType.id)
							startActivity(intent)
						}
					})

					list
				}
			}

			val tab = tabHost.newTabSpec("routes")
			tab.setIndicator(vehicleTypeNames(vehicleType), vehicleTypeDrawables(vehicleType))
			tab.setContent(factory)

			tab
		}
	  
	  tabs.foreach(tabHost.addTab(_))
  }

}
