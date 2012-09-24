package net.kriomant.gortrans

import com.actionbarsherlock.app.SherlockActivity
import android.os.Bundle
import com.actionbarsherlock.view.Menu
import android.view.{View, MenuItem}
import android.widget.{ListView, TextView, SimpleAdapter, ListAdapter}
import android.content.Context
import android.text.{Spanned, SpannableStringBuilder}
import android.text.style.ImageSpan
import net.kriomant.gortrans.core.VehicleType

class GroupsActivity extends SherlockActivity with TypedActivity {
	private[this] var groupList: ListView = _

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.groups_activity)

		groupList = findView(TR.group_list)
	}

	override def onCreateOptionsMenu(menu: Menu) = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.route_groups_menu, menu)
		true
	}

	override def onStart() {
		super.onStart()

		val db = getApplication.asInstanceOf[CustomApplication].database
		val groups = db.loadGroups()
		groupList.setAdapter(new RouteGroupsAdapter(this, groups))
	}

	override def onMenuItemSelected(featureId: Int, item: MenuItem) = item.getItemId match {
		case R.id.routes_list =>
			startActivity(MainActivity.createIntent(this))
			true

		case _ => super.onMenuItemSelected(featureId, item)
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
}
