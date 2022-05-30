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
import android.widget.AdapterView.OnItemClickListener

object MainActivity {
	val EXTRA_VEHICLE_TYPE = "vehicleType"

	def createIntent(caller: Context, vehicleType: VehicleType.Value = VehicleType.Bus): Intent = {
		val intent = new Intent(caller, classOf[MainActivity])
		intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent
	}
}

class MainActivity extends MainActivityBase with HavingSidebar {
	override protected def onDrawerClosed() {
		if (tabsAreEmbedded) tabsView.setVisibility(View.VISIBLE)
	}

	override protected def onDrawerOpened() {
		if (tabsAreEmbedded) tabsView.setVisibility(View.INVISIBLE)
	}
}

class MainActivityBase extends RouteListBaseActivity with CreateGroupDialog.Listener {
	import MainActivity._

	private[this] final val TAG = classOf[MainActivityBase].getSimpleName

	var actionModeHelper: MultiListActionModeHelper = null

	override val layoutResource = R.layout.main_activity

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

	  val actionBar = getSupportActionBar
	  actionBar.setDisplayHomeAsUpEnabled(true)

	  actionModeHelper = new MultiListActionModeHelper(this, ContextActions)

		if (bundle != null) {
			// Restore index of currently selected tab.
			setSelectedTab(bundle.getInt("tabIndex"))
		} else {
			val vehicleType = VehicleType(getIntent.getIntExtra(EXTRA_VEHICLE_TYPE, VehicleType.Bus.id))
			setSelectedTab(tabsOrder.indexOf(vehicleType))
		}
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

	override def onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		// Save index of currently selected tab.
		outState.putInt("tabIndex", getSelectedTab)
	}

	/** This method is called by tab fragments when their views are created. */
	override def registerRoutesList(fragment: RoutesListFragment) {
		super.registerRoutesList(fragment)

		val listView = fragment.getListView
		val cursor = fragment.cursor

		listView.setOnItemClickListener(new OnItemClickListener {
			def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long) {
				cursor.moveToPosition(position)

				val intent = RouteInfoActivity.createIntent(MainActivityBase.this, cursor.externalId, cursor.name, cursor.vehicleType)
				startActivity(intent)
			}
		})

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
				val routeIds = actionModeHelper.getListViews.flatMap(_.getCheckedItemIds)
				val createGroupDialog = new CreateGroupDialog(routeIds.toSet)
				createGroupDialog.show(getSupportFragmentManager, "create_group_dialog")
				true
			}
			case _ => false
		}

		def onDestroyActionMode(mode: ActionMode) {}

		def itemCheckedStateChanged(mode: ActionMode) {
			val count = actionModeHelper.getListViews.map(Compatibility.getCheckedItemCount(_)).sum
			mode.setTitle(compatibility.plurals.getQuantityString(MainActivityBase.this, R.plurals.routes, count, count))
		}
	}

	def onCreateGroup(dialog: CreateGroupDialog, groupId: Long) {
		actionModeHelper.finish()
	}
}

class ActionModeCallbackWrapper(callback: ActionMode.Callback) extends ActionMode.Callback {
	def onCreateActionMode(mode: ActionMode, menu: Menu) = callback.onCreateActionMode(mode, menu)
	def onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)
	def onActionItemClicked(mode: ActionMode, item: MenuItem) = callback.onActionItemClicked(mode, item)
	def onDestroyActionMode(mode: ActionMode) { callback.onDestroyActionMode(mode) }
}

