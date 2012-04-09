package net.kriomant.gortrans

import _root_.android.os.Bundle

import android.view.View
import net.kriomant.gortrans.core.VehicleType
import android.content.Intent
import android.app.TabActivity
import android.widget.AdapterView.OnItemClickListener
import android.widget._

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

			val factory = new TabHost.TabContentFactory {
				def createTabContent(p1: String) = {
					val listAdapter = new ListAdapter with EasyAdapter with SeqAdapter {
						val context = MainActivity.this
						val items = routes
						val itemLayout = R.layout.routes_list_item
						case class SubViews(number: TextView, begin: TextView, end: TextView)
						
						def findSubViews(view: View) = SubViews(
							view.findViewById(R.id.route_name).asInstanceOf[TextView],
							view.findViewById(R.id.start_stop_name).asInstanceOf[TextView],
							view.findViewById(R.id.end_stop_name).asInstanceOf[TextView]
						)

						def adjustItem(position: Int, views: SubViews) {
							val route = routes(position)
							views.number.setText(route.name)
							views.begin.setText(route.begin)
							views.end.setText(route.end)
						}
					}

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
