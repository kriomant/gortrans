package net.kriomant.gortrans

import _root_.android.os.Bundle

import net.kriomant.gortrans.core.VehicleType
import android.widget._
import com.actionbarsherlock.app.ActionBar.{Tab, TabListener}
import android.support.v4.app.{ListFragment, FragmentTransaction}
import android.view.View
import com.actionbarsherlock.app.{SherlockFragmentActivity, ActionBar}
import com.actionbarsherlock.view.Window
import android.content.{DialogInterface, Context, Intent}
import net.kriomant.gortrans.DataManager.DataConsumer
import android.app.{AlertDialog, ProgressDialog}

object MainActivity {
	def createIntent(caller: Context): Intent = {
		new Intent(caller, classOf[MainActivity])
	}
}

class MainActivity extends SherlockFragmentActivity with TypedActivity {
	private[this] final val TAG = "MainActivity"

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

	  setContentView(R.layout.main_activity)

	  val actionBar = getSupportActionBar
	  actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS)
	  actionBar.setDisplayShowTitleEnabled(false)
	  actionBar.setDisplayShowHomeEnabled(false)

	  if (bundle != null) {
		  // Restore index of currently selected tab.
		  actionBar.setSelectedNavigationItem(bundle.getInt("tabIndex"))
	  }
  }

	override def onStart() {
		super.onStart()

		loadRoutes()
	}

	def updateRoutesList(routes: parsing.RoutesInfo) {
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

		val actionBar = getSupportActionBar
		actionBar.removeAllTabs()

		val tabs = routes.map { p =>
			val (vehicleType, routes) = p

			val fragment = new RoutesListFragment(vehicleType)

			val tab = actionBar.newTab
				.setIcon(vehicleTypeDrawables(vehicleType))
				.setTabListener(new TabListener {
				def onTabSelected(tab: Tab, ft: FragmentTransaction) {
					ft.replace(R.id.tab_host, fragment)
				}
				def onTabReselected(tab: Tab, ft: FragmentTransaction) {}
				def onTabUnselected(tab: Tab, ft: FragmentTransaction) {}
			})

			tab
		}

		tabs.foreach(actionBar.addTab(_))
	}

	def loadRoutes() {
		val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		val progressBar = findView(TR.progress_bar)

		dataManager.requestRoutesList(
			new DataConsumer[parsing.RoutesInfo] {
				val progressDialog = {
					val d = new ProgressDialog(MainActivity.this)
					d.setTitle(R.string.loading)
					d.setMessage(getString(R.string.wait_please))
					d
				}

				def startFetch() {
					progressDialog.show()
				}

				def stopFetch() {
					progressDialog.dismiss()
				}

				def onData(data: parsing.RoutesInfo) {
					updateRoutesList(data)
				}

				def onError() {
					(new AlertDialog.Builder(MainActivity.this)
						.setTitle(R.string.cant_load)
						.setMessage(R.string.loading_failure)

						.setPositiveButton(R.string.retry, new DialogInterface.OnClickListener {
							def onClick(p1: DialogInterface, p2: Int) {
								p1.dismiss()
								loadRoutes()
							}
						})

						.setNegativeButton(R.string.abort, new DialogInterface.OnClickListener {
							def onClick(p1: DialogInterface, p2: Int) {
								p1.dismiss()
								finish()
							}
						})
					).create().show()
				}
			},
			new DataConsumer[parsing.RoutesInfo] {
				def startFetch() {
					Toast.makeText(MainActivity.this, R.string.background_update_started, Toast.LENGTH_SHORT).show()
					progressBar.setVisibility(View.VISIBLE)
				}

				def stopFetch() {
					progressBar.setVisibility(View.INVISIBLE)
				}

				def onData(data: parsing.RoutesInfo) {
					updateRoutesList(data)
					Toast.makeText(MainActivity.this, R.string.background_update_stopped, Toast.LENGTH_SHORT).show()
				}

				def onError() {
					Toast.makeText(MainActivity.this, R.string.background_update_error, Toast.LENGTH_SHORT).show()
				}
			}
		)
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
