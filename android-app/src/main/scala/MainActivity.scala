package net.kriomant.gortrans

import _root_.android.os.Bundle

import net.kriomant.gortrans.core.VehicleType
import android.support.v4.widget.CursorAdapter
import android.widget._
import com.actionbarsherlock.app.ActionBar.{Tab, TabListener}
import android.support.v4.app.{FragmentPagerAdapter, ListFragment, FragmentTransaction}
import android.view.{ViewGroup, LayoutInflater, View}
import com.actionbarsherlock.app.{SherlockFragmentActivity, ActionBar}
import com.actionbarsherlock.view.{ActionMode, MenuItem, Menu}
import android.content.{DialogInterface, Context, Intent}
import net.kriomant.gortrans.DataManager.ProcessIndicator
import android.support.v4.view.ViewPager.OnPageChangeListener
import android.widget.AdapterView.OnItemLongClickListener

object MainActivity {
	val EXTRA_VEHICLE_TYPE = "vehicleType"

	def createIntent(caller: Context, vehicleType: VehicleType.Value = VehicleType.Bus): Intent = {
		val intent = new Intent(caller, classOf[MainActivity])
		intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent
	}
}

class MainActivity extends SherlockFragmentActivity with TypedActivity with CreateGroupDialog.Listener {
	import MainActivity._

	private[this] final val TAG = "MainActivity"

	var tabFragmentsMap: Map[VehicleType.Value, RoutesListFragment] = null
	var actionModeHelper: MultiListActionModeHelper = null

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

	  actionModeHelper = new MultiListActionModeHelper(this, ContextActions)

	  // Fix tabs order.
	  val tabsOrder = Seq(VehicleType.Bus, VehicleType.TrolleyBus, VehicleType.TramWay, VehicleType.MiniBus)
	  val tabFragments = tabsOrder map { vehicleType => new RoutesListFragment(vehicleType) }
	  tabFragmentsMap = tabsOrder.zip(tabFragments).toMap

	  tabsOrder.zipWithIndex foreach { case (vehicleType, i) =>
		  val fragment = tabFragments(i)
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
		  def getCount = tabFragments.size
		  def getItem(pos: Int) = tabFragments(pos)
	  })
	  tabPager.setOnPageChangeListener(new OnPageChangeListener {
		  def onPageScrolled(p1: Int, p2: Float, p3: Int) {}
		  def onPageScrollStateChanged(p1: Int) {}

		  def onPageSelected(pos: Int) {
			  actionBar.setSelectedNavigationItem(pos)
		  }
	  })

	  if (bundle != null) {
		  // Restore index of currently selected tab.
		  actionBar.setSelectedNavigationItem(bundle.getInt("tabIndex"))
	  } else {
		  val vehicleType = VehicleType(getIntent.getIntExtra(EXTRA_VEHICLE_TYPE, VehicleType.Bus.id))
		  actionBar.setSelectedNavigationItem(tabsOrder.indexOf(vehicleType))
	  }
  }

	override def onStart() {
		super.onStart()

		loadRoutes()
	}


	override def onCreateOptionsMenu(menu: Menu): Boolean = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.route_list_menu, menu)
		true
	}


	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case R.id.search =>
			onSearchRequested()
			true

		case _ => super.onOptionsItemSelected(item)
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
				Toast.makeText(MainActivity.this, R.string.background_update_started, Toast.LENGTH_SHORT).show()
				progressBar.setVisibility(View.VISIBLE)
			}

			def stopFetch() {
				progressBar.setVisibility(View.INVISIBLE)
			}

			def onSuccess() {
				Toast.makeText(MainActivity.this, R.string.background_update_stopped, Toast.LENGTH_SHORT).show()
			}

			def onError() {
				Toast.makeText(MainActivity.this, R.string.background_update_error, Toast.LENGTH_SHORT).show()
			}
		}

		dataManager.requestRoutesList(foregroundProcessIndicator, backgroundProcessIndicator) {
			updateRoutesList()
		}
	}

	override def onSaveInstanceState(outState: Bundle) {
		// Save index of currently selected tab.
		outState.putInt("tabIndex", getSupportActionBar.getSelectedNavigationIndex)
	}

	/** This method is called by tab fragments when their views are created. */
	def registerRoutesList(listView: ListView) {
		actionModeHelper.attach(listView)
	}

	object ContextActions extends ActionMode.Callback with ListSelectionActionModeCallback {
		def onCreateActionMode(mode: ActionMode, menu: Menu) = {
			val inflater = mode.getMenuInflater
			inflater.inflate(R.menu.route_list_actions, menu)
			true
		}

		def onPrepareActionMode(mode: ActionMode, menu: Menu) = { true }
		def onActionItemClicked(mode: ActionMode, item: MenuItem) = item.getItemId match {
			case R.id.create_group => {
				val createGroupDialog = new CreateGroupDialog
				createGroupDialog.show(getSupportFragmentManager, "create_group_dialog")
				true
			}
			case _ => false
		}

		def onDestroyActionMode(mode: ActionMode) {}

		def itemCheckedStateChanged(mode: ActionMode) {
			val count = actionModeHelper.getListViews.map(Compatibility.getCheckedItemCount(_)).sum
			mode.setTitle(compatibility.plurals.getQuantityString(MainActivity.this, R.plurals.routes, count, count))
		}
	}

	def onCreateGroup(name: String) {
		val db = getApplication.asInstanceOf[CustomApplication].database

		db.transaction {
			val groupId = db.createGroup(name)
			actionModeHelper.getListViews.foreach { listView =>
				if (Compatibility.getCheckedItemCount(listView) > 0) {
					listView.getCheckedItemIds.foreach { routeId => db.addRouteToGroup(groupId, routeId) }
				}
			}
		}
		actionModeHelper.finish()

		Toast.makeText(this, getString(R.string.group_created, name), Toast.LENGTH_SHORT).show()
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

		if (savedInstanceState != null) {
			getListView.onRestoreInstanceState(savedInstanceState.getParcelable("list"))
		}
	}

	override def onViewCreated(view: View, savedInstanceState: Bundle) {
		super.onViewCreated(view, savedInstanceState)

		getListView.setOnItemLongClickListener(new OnItemLongClickListener {
			def onItemLongClick(parent: AdapterView[_], view: View, position: Int, id: Long) = {
				getListView.setItemChecked(position, true)
				true
			}
		})

		getActivity.asInstanceOf[MainActivity].registerRoutesList(getListView)
	}

	def refresh() {
		if (cursor != null)
			cursor.requery()
	}

	override def onListItemClick(l: ListView, v: View, position: Int, id: Long) {
		cursor.moveToPosition(position)

		val intent = RouteInfoActivity.createIntent(getActivity, cursor.externalId, cursor.name, cursor.vehicleType)
		startActivity(intent)
	}

	override def onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putParcelable("list", getListView.onSaveInstanceState())
	}
}

class ActionModeCallbackWrapper(callback: ActionMode.Callback) extends ActionMode.Callback {
	def onCreateActionMode(mode: ActionMode, menu: Menu) = callback.onCreateActionMode(mode, menu)
	def onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)
	def onActionItemClicked(mode: ActionMode, item: MenuItem) = callback.onActionItemClicked(mode, item)
	def onDestroyActionMode(mode: ActionMode) { callback.onDestroyActionMode(mode) }
}

