package net.kriomant.gortrans

import _root_.android.os.Bundle

import net.kriomant.gortrans.core.VehicleType
import android.support.v4.widget.CursorAdapter
import android.widget._
import com.actionbarsherlock.app.ActionBar.{Tab, TabListener}
import android.support.v4.app.{FragmentPagerAdapter, ListFragment, FragmentTransaction}
import android.view.{ViewGroup, LayoutInflater, View}
import com.actionbarsherlock.app.{SherlockFragmentActivity, ActionBar}
import net.kriomant.gortrans.DataManager.ProcessIndicator
import android.support.v4.view.ViewPager.OnPageChangeListener
import android.widget.AdapterView.OnItemLongClickListener
import android.app.Activity
import scala.collection.mutable

class RouteListBaseActivity extends SherlockFragmentActivity with BaseActivity with TypedActivity {
	private[this] final val TAG = classOf[RouteListBaseActivity].getSimpleName

	var tabsOrder: Seq[core.VehicleType.Value] = null
	var tabFragmentsMap: mutable.Map[VehicleType.Value, RoutesListFragment] = mutable.Map()

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		setContentView(R.layout.main_activity)

		val actionBar = getSupportActionBar
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS)

		val vehicleTypeDrawables = Map(
			VehicleType.Bus -> R.drawable.tab_bus,
			VehicleType.TrolleyBus -> R.drawable.tab_trolleybus,
			VehicleType.TramWay -> R.drawable.tab_tram,
			VehicleType.MiniBus -> R.drawable.tab_minibus
		).mapValues(getResources.getDrawable(_))

		val tabPager = findView(TR.tab_pager)

		// Fix tabs order.
		tabsOrder = Seq(VehicleType.Bus, VehicleType.TrolleyBus, VehicleType.TramWay, VehicleType.MiniBus)

		tabsOrder.zipWithIndex foreach { case (vehicleType, i) =>
			val icon = vehicleTypeDrawables(vehicleType)

			val tab = actionBar.newTab
				.setIcon(icon)
				.setTabListener(new TabListener {
				def onTabSelected(tab: Tab, ft: FragmentTransaction) {
					tabPager.setCurrentItem(i)
				}
				def onTabReselected(tab: Tab, ft: FragmentTransaction) {}
				def onTabUnselected(tab: Tab, ft: FragmentTransaction) {}
			})

			actionBar.addTab(tab)
		}

		tabPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager) {
			def getCount = tabsOrder.size
			def getItem(pos: Int) = new RoutesListFragment(tabsOrder(pos))
		})
		tabPager.setOnPageChangeListener(new OnPageChangeListener {
			def onPageScrolled(p1: Int, p2: Float, p3: Int) {}
			def onPageScrollStateChanged(p1: Int) {}

			def onPageSelected(pos: Int) {
				actionBar.setSelectedNavigationItem(pos)
			}
		})
	}

	override def onStart() {
		super.onStart()

		loadRoutes()
	}

	def updateRoutesList() {
		tabFragmentsMap.values.foreach { _.refresh() }
	}

	def loadRoutes() {
		val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		val progressBar = findView(TR.progress_bar)

		val foregroundProcessIndicator = new ForegroundProcessIndicator(this, loadRoutes)
		val backgroundProcessIndicator = new ProcessIndicator {
			def startFetch() {
				Toast.makeText(RouteListBaseActivity.this, R.string.background_update_started, Toast.LENGTH_SHORT).show()
				progressBar.setVisibility(View.VISIBLE)
			}

			def stopFetch() {
				progressBar.setVisibility(View.INVISIBLE)
			}

			def onSuccess() {
				Toast.makeText(RouteListBaseActivity.this, R.string.background_update_stopped, Toast.LENGTH_SHORT).show()
			}

			def onError() {
				Toast.makeText(RouteListBaseActivity.this, R.string.background_update_error, Toast.LENGTH_SHORT).show()
			}
		}

		dataManager.requestRoutesList(foregroundProcessIndicator, backgroundProcessIndicator) {
			updateRoutesList()
		}
	}

	/** This method is called by tab fragments when their views are created. */
	def registerRoutesList(fragment: RoutesListFragment) {
	}
}

class RoutesListFragment extends ListFragment {
	private[this] final val ARGUMENT_VEHICLE_TYPE = "vehicleType"

	def this(vehicleType: VehicleType.Value) {
		this()

		val arguments = new Bundle
		arguments.putInt(ARGUMENT_VEHICLE_TYPE, vehicleType.id)
		setArguments(arguments)
	}

	private[this] def vehicleType = VehicleType(getArguments.getInt(ARGUMENT_VEHICLE_TYPE))

	var cursor: Database.RoutesTable.Cursor = null

	override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) = {
		inflater.inflate(R.layout.routes_list_tab, container, false)
	}

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		val database = getActivity.getApplication.asInstanceOf[CustomApplication].database
		cursor = database.fetchRoutes(vehicleType)
		getActivity.startManagingCursor(cursor)

		val listAdapter = new CursorAdapter(getActivity, cursor)
			with ListAdapter
			with EasyCursorAdapter[Database.RoutesTable.Cursor]
		{
			val context = getActivity
			val itemLayout = R.layout.routes_list_item
			case class SubViews(number: TextView, begin: TextView, end: TextView)

			def findSubViews(view: View) = SubViews(
				view.findViewById(R.id.route_name).asInstanceOf[TextView],
				view.findViewById(R.id.start_stop_name).asInstanceOf[TextView],
				view.findViewById(R.id.end_stop_name).asInstanceOf[TextView]
			)

			def adjustItem(cursor: Database.RoutesTable.Cursor, views: SubViews) {
				views.number.setText(cursor.name)
				views.begin.setText(cursor.firstStopName)
				views.end.setText(cursor.lastStopName)
			}
		}

		setListAdapter(listAdapter)
	}

	override def onViewCreated(view: View, savedInstanceState: Bundle) {
		super.onViewCreated(view, savedInstanceState)

		getListView.setOnItemLongClickListener(new OnItemLongClickListener {
			def onItemLongClick(parent: AdapterView[_], view: View, position: Int, id: Long) = {
				getListView.setItemChecked(position, true)
				true
			}
		})

		getActivity.asInstanceOf[RouteListBaseActivity].registerRoutesList(this)
	}

	def refresh() {
		if (cursor != null)
			cursor.requery()
	}

	override def onAttach(activity: Activity) {
		super.onAttach(activity)

		activity.asInstanceOf[RouteListBaseActivity].tabFragmentsMap(vehicleType) = this
	}


}

