package net.kriomant.gortrans

import _root_.android.os.Bundle

import net.kriomant.gortrans.core.VehicleType
import android.support.v4.widget.CursorAdapter
import android.widget._
import android.support.v4.app.{FragmentPagerAdapter, ListFragment}
import android.view.{ViewGroup, LayoutInflater, View}
import com.actionbarsherlock.app.{SherlockFragmentActivity}
import net.kriomant.gortrans.DataManager.ProcessIndicator
import android.support.v4.view.ViewPager.OnPageChangeListener
import android.widget.AdapterView.OnItemLongClickListener
import android.app.Activity
import scala.collection.mutable
import com.actionbarsherlock.internal.widget.ScrollingTabContainerView
import android.util.TypedValue
import android.view.View.OnClickListener

class RouteListBaseActivity extends SherlockFragmentActivity with BaseActivity with TypedActivity {
	private[this] final val TAG = classOf[RouteListBaseActivity].getSimpleName

	var tabsOrder: Seq[core.VehicleType.Value] = null
	var tabFragmentsMap: mutable.Map[VehicleType.Value, RoutesListFragment] = mutable.Map()
	var tabsView: ScrollingTabContainerView = null

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		setContentView(R.layout.main_activity)

		val actionBar = getSupportActionBar

		// Action bar 'tabs' navigation mode isn't used, because then tabs are part of action bar
		// even when they are displayed as second row and navigation drawer doesn't operlap them
		// which is ugly. So instead of using action bar navigation own tabs view is placed below
		// action bar.
		tabsView = new ScrollingTabContainerView(actionBar.getThemedContext)
		tabsView.setAllowCollapse(false) // Prevent collapsing to dropdown list.

		// Set tabs height the same as action bar's height. This is what ActionBarSherlock does.
		val tv = new TypedValue
		getTheme.resolveAttribute(com.actionbarsherlock.R.attr.actionBarSize, tv, true)
		val actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, getResources.getDisplayMetrics)
		tabsView.setContentHeight(actionBarHeight)

		// Insert at the very top.
		val layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		findView(TR.routes_content).addView(tabsView, 0, layoutParams)

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

			val tab = actionBar.newTab.setIcon(icon)
			tabsView.addTab(tab, false)

			// HACK: ScrollingTabContainerView doesn't have way to listen for tab selection event,
			// all it does is calls tab.select(), but it doesn't work because tabs are not added
			// to action bar.
			// Following code relies on ScrollingTabContainerView internals.
			tabsView.getChildAt(0).asInstanceOf[LinearLayout].getChildAt(i).setOnClickListener(new OnClickListener {
				def onClick(v: View) {
					tabPager.setCurrentItem(i)
				}
			})
		}

		tabPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager) {
			def getCount = tabsOrder.size
			def getItem(pos: Int) = new RoutesListFragment(tabsOrder(pos))
		})
		tabPager.setOnPageChangeListener(new OnPageChangeListener {
			def onPageScrolled(p1: Int, p2: Float, p3: Int) {}
			def onPageScrollStateChanged(p1: Int) {}

			def onPageSelected(pos: Int) {
				tabsView.setTabSelected(pos)
			}
		})
	}

	override def onStart() {
		super.onStart()

		loadRoutes()
	}

	protected def setSelectedTab(idx: Int) {
		tabsView.setTabSelected(idx)
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

