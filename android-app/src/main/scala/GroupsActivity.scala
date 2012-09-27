package net.kriomant.gortrans

import com.actionbarsherlock.app.{SherlockFragmentActivity, SherlockActivity}
import android.os.Bundle
import com.actionbarsherlock.view.{ActionMode, Menu, MenuItem}
import android.view.View
import android.widget._
import android.content.{Intent, Context}
import android.text.{Spanned, SpannableStringBuilder}
import android.text.style.ImageSpan
import net.kriomant.gortrans.core.VehicleType
import com.actionbarsherlock.view
import android.widget.AdapterView.OnItemClickListener

object GroupsActivity {
	def createIntent(context: Context): Intent = {
		new Intent(context, classOf[GroupsActivity])
	}
}

class GroupsActivity extends SherlockFragmentActivity with TypedActivity {
	private[this] var groupList: ListView = _

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.groups_activity)

		groupList = findView(TR.group_list)
		groupList.setOnItemClickListener(new OnItemClickListener {
			def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long) {
				startActivity(RouteMapActivity.createShowGroupIntent(GroupsActivity.this, id))
			}
		})

		val actionModeHelper = new MultiListActionModeHelper(this, new ActionMode.Callback with ListSelectionActionModeCallback {
			def onCreateActionMode(mode: ActionMode, menu: Menu) = {
				mode.getMenuInflater.inflate(R.menu.route_groups_actions, menu)
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
				case _ => false
			}

			def onDestroyActionMode(mode: ActionMode) {}

			def itemCheckedStateChanged(mode: ActionMode) {
				if (groupList.getCheckedItemCount == 0)
					mode.finish()
				else
					mode.setTitle(groupList.getCheckedItemCount.toString + " groups")
			}
		})
		actionModeHelper.attach(groupList)
	}

	override def onCreateOptionsMenu(menu: Menu) = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.route_groups_menu, menu)
		true
	}

	override def onStart() {
		super.onStart()

		loadGroups()
	}

	private def loadGroups() {
		val db = getApplication.asInstanceOf[CustomApplication].database
		val groups = db.loadGroups()
		groupList.setAdapter(new RouteGroupsAdapter(this, groups))
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case R.id.routes_list =>
			startActivity(MainActivity.createIntent(this))
			true

		case _ => super.onOptionsItemSelected(item)
	}
}

object SpannableStringBuilderUtils {
	implicit def conversion(builder: SpannableStringBuilder) = new Object {
		def appendWithSpan(text: CharSequence, span: AnyRef) {
			val len = builder.length
			builder.append(text)
			builder.setSpan(span, len, builder.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
		}
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
	import SpannableStringBuilderUtils.conversion

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