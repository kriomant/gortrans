package net.kriomant.gortrans

import _root_.android.os.Bundle

import net.kriomant.gortrans.core.VehicleType
import android.widget._
import com.actionbarsherlock.app.ActionBar.{Tab, TabListener}
import android.support.v4.app.{ListFragment, FragmentTransaction}
import android.view.View
import com.actionbarsherlock.app.{SherlockFragmentActivity, ActionBar, SherlockActivity}
import android.content.res.Configuration
import android.content.{Context, Intent}

object MainActivity {
	def createIntent(caller: Context): Intent = {
		new Intent(caller, classOf[MainActivity])
	}
}

class MainActivity extends SherlockFragmentActivity with TypedActivity {
	private[this] final val TAG = "MainActivity"

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

	  val actionBar = getSupportActionBar
	  actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS)
	  actionBar.setDisplayShowTitleEnabled(false)
	  actionBar.setDisplayShowHomeEnabled(false)

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

			val fragment = new RoutesListFragment(vehicleType)

			val tab = actionBar.newTab
				.setIcon(vehicleTypeDrawables(vehicleType))
				.setTabListener(new TabListener {
					def onTabSelected(tab: Tab, ft: FragmentTransaction) {
						ft.replace(android.R.id.content, fragment)
					}
					def onTabReselected(tab: Tab, ft: FragmentTransaction) {}
					def onTabUnselected(tab: Tab, ft: FragmentTransaction) {}
				})

			tab
		}
	  
	  tabs.foreach(actionBar.addTab(_))

	  if (bundle != null) {
		  // Restore index of currently selected tab.
		  actionBar.setSelectedNavigationItem(bundle.getInt("tabIndex"))
	  }
  }

	override def onSaveInstanceState(outState: Bundle) {
		// Save index of currently selected tab.
		outState.putInt("tabIndex", getSupportActionBar.getSelectedNavigationIndex)
	}
}

class RoutesListFragment extends ListFragment {
	var routes: Seq[core.Route] = null

	def this(vehicleType: VehicleType.Value) = {
		this()

		val args = new Bundle
		args.putInt("vehicleTypeId", vehicleType.id)
		setArguments(args)
	}

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		val vehicleTypeId = getArguments.getInt("vehicleTypeId")

		val dataManager = getActivity.getApplication.asInstanceOf[CustomApplication].dataManager
		routes = dataManager.getRoutesList()(VehicleType(vehicleTypeId))

		val listAdapter = new ListAdapter with EasyAdapter with SeqAdapter {
			val context = getActivity
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

		setListAdapter(listAdapter)

		if (savedInstanceState != null) {
			getListView.onRestoreInstanceState(savedInstanceState.getParcelable("list"))
		}
	}

	override def onListItemClick(l: ListView, v: View, position: Int, id: Long) {
		val route = routes(position)

		val intent = RouteInfoActivity.createIntent(getActivity, route.id, route.name, route.vehicleType)
		startActivity(intent)
	}

	override def onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putParcelable("list", getListView.onSaveInstanceState())
	}
}
