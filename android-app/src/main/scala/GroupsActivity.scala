package net.kriomant.gortrans

import com.actionbarsherlock.app.{SherlockFragmentActivity, SherlockActivity}
import android.os.Bundle
import com.actionbarsherlock.view.{ActionMode, Menu, MenuItem}
import android.view.{ViewGroup, View}
import android.widget._
import android.content.{Intent, Context}
import android.text.{Spanned, SpannableStringBuilder}
import android.text.style.ImageSpan
import net.kriomant.gortrans.core.VehicleType
import com.actionbarsherlock.view
import android.widget.AdapterView.OnItemClickListener
import android.app.Activity

object GroupsActivity {
	val REQUEST_CREATE_GROUP = 1

	def createIntent(context: Context): Intent = {
		new Intent(context, classOf[GroupsActivity])
	}
}

class GroupsActivity extends GroupsActivityBase with HavingSidebar {
	override def onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)

		if (hasFocus) {
			// If this activity is shown for the first time after upgrade,
			// show sidebar to present new functionality to user.
			// This can't be done from onResume, because views are not yet
			// laid out there.
			val prefs = getPreferences(Context.MODE_PRIVATE)
			val SHOW_SIDEBAR_ON_START = "showSidebarOnStart"
			if (prefs.getBoolean(SHOW_SIDEBAR_ON_START, true)) {
				prefs.edit().putBoolean(SHOW_SIDEBAR_ON_START, false).commit()
				drawerLayout.openDrawer(drawer)
			}
		}
	}

}

class GroupsActivityBase extends SherlockFragmentActivity with BaseActivity with TypedActivity with CreateGroupDialog.Listener with HavingSidebar {
	private[this] var groupList: ListView = _

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.groups_activity)

		groupList = findView(TR.group_list)
		groupList.setEmptyView(findView(TR.group_list_empty))
		groupList.setOnItemClickListener(new OnItemClickListener {
			def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long) {
				val intent = RouteMapLike.createShowGroupIntent(GroupsActivityBase.this, id)
				startActivity(intent)
			}
		})

		val actionModeHelper = new MultiListActionModeHelper(this, new ActionMode.Callback with ListSelectionActionModeCallback {
			var menu_ : Menu = null

			def onCreateActionMode(mode: ActionMode, menu: Menu) = {
				mode.getMenuInflater.inflate(R.menu.route_groups_actions, menu)
				menu_ = menu
				true
			}

			def onPrepareActionMode(mode: ActionMode, menu: Menu) = false

			def onActionItemClicked(mode: ActionMode, item: view.MenuItem) = item.getItemId match {
				case R.id.delete_group =>
					val db = getApplication.asInstanceOf[CustomApplication].database
					db.transaction {
						groupList.getCheckedItemIds.foreach { groupId =>
							db.deleteGroup(groupId)
						}
					}
					mode.finish()
					loadGroups()
					true

				case R.id.edit_group =>
					val groupId = groupList.getCheckedItemIds.head
					mode.finish()

					val intent = EditGroupActivity.createIntent(GroupsActivityBase.this, groupId)
					startActivity(intent)

					true

				case _ => false
			}

			def onDestroyActionMode(mode: ActionMode) {}

			def itemCheckedStateChanged(mode: ActionMode) {
				val checkedItemCount = Compatibility.getCheckedItemCount(groupList)
				if (checkedItemCount == 0) {
					mode.finish()
				} else {
					mode.setTitle(compatibility.plurals.getQuantityString(GroupsActivityBase.this, R.plurals.groups, checkedItemCount, checkedItemCount))
					menu_.setGroupVisible(R.id.single_item_actions, checkedItemCount == 1)
				}
			}
		})
		actionModeHelper.attach(groupList)
	}

	override def onCreateOptionsMenu(menu: Menu) = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.route_groups_menu, menu)
		true
	}

	private def loadGroups() {
		val db = getApplication.asInstanceOf[CustomApplication].database
		val groups = db.loadGroups()
		groupList.setAdapter(new RouteGroupsAdapter(this, groups))
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case R.id.create_group =>
			startActivityForResult(RouteChooseActivity.createIntent(this), GroupsActivity.REQUEST_CREATE_GROUP)
			true

		case _ => super.onOptionsItemSelected(item)
	}

	var createGroupData: Intent = null

	override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = requestCode match {
		case GroupsActivity.REQUEST_CREATE_GROUP =>
			if (resultCode == Activity.RESULT_OK) {
				// There are two bugs in Android which don't allow to show dialog from onActivityResult:
				// * http://code.google.com/p/android/issues/detail?id=17787
				// * http://code.google.com/p/android/issues/detail?id=23761
				// So just set flag here and check it in onResume.
				createGroupData = data
			}

		case _ => super.onActivityResult(requestCode, resultCode, data)
	}


	override def onResume() {
		super.onResume()

		if (createGroupData != null) {
			createGroup(createGroupData)
			createGroupData = null
		}

		loadGroups()
	}


	override def onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)

		if (hasFocus) {
			// If this activity is shown for the first time after upgrade,
			// show sidebar to present new functionality to user.
			// This can't be done from onResume, because views are not yet
			// laid out there.
			val prefs = getPreferences(Context.MODE_PRIVATE)
			val SHOW_SIDEBAR_ON_START = "showSidebarOnStart"
			if (prefs.getBoolean(SHOW_SIDEBAR_ON_START, true)) {
				prefs.edit().putBoolean(SHOW_SIDEBAR_ON_START, false).commit()
				//sidebarContainer.animateOpen()
			}
		}
	}

	val TAG_CREATE_GROUP = "tag-create-group"

	def createGroup(data: Intent) {
		val routeIds = RouteChooseActivity.intentToResult(data)
		val dialog = new CreateGroupDialog(routeIds)
		dialog.show(getSupportFragmentManager, TAG_CREATE_GROUP)
	}

	def onCreateGroup(dialog: CreateGroupDialog, groupId: Long) {
		loadGroups()
	}
}

object RouteGroupsAdapter {
	val vehicleTypeDrawables = Map(
		VehicleType.Bus -> R.drawable.tab_bus,
		VehicleType.TrolleyBus -> R.drawable.tab_trolleybus,
		VehicleType.TramWay -> R.drawable.tab_tram,
		VehicleType.MiniBus -> R.drawable.tab_minibus
	)
}

class RouteGroupsAdapter(val context: Context, val items: Seq[Database.GroupInfo]) extends SeqAdapter with EasyAdapter {
	import android_utils.SpannableStringBuilderUtils

	case class SubViews(name: TextView, routes: TextView)
	val itemLayout = R.layout.group_list_item_layout

	def findSubViews(view: View) = SubViews(
		view.findViewById(R.id.group_name).asInstanceOf[TextView],
		view.findViewById(R.id.group_routes).asInstanceOf[TextView]
	)

	def adjustItem(position: Int, views: SubViews) {
		val group = items(position)

		views.name.setText(group.name)

		val builder = new SpannableStringBuilder
		group.routes.foreach { case (vehicleType, routeName) =>
			builder.appendWithSpan(" ", new ImageSpan(context, RouteGroupsAdapter.vehicleTypeDrawables(vehicleType)))
			builder.append(routeName)
			builder.append(" ")
		}
		views.routes.setText(builder)
	}


	override def hasStableIds: Boolean = true
	override def getItemId(position: Int): Long = items(position).id
}
