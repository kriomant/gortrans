package net.kriomant.gortrans

import android.content.{Intent, Context}
import com.actionbarsherlock.view.{MenuItem, Menu}
import android.os.Bundle
import android.widget.{AdapterView, AbsListView, ListView}
import android.view
import view.View
import android.widget.AdapterView.{OnItemClickListener, OnItemSelectedListener}
import scala.collection.mutable
import android.app.Activity

object RouteChooseActivity {
	def createIntent(caller: Context): Intent = new Intent(caller, classOf[RouteChooseActivity])

	val EXTRA_ROUTE_IDS = "route-ids"

	def resultToIntent(routeIds: Set[Long]) = {
		val intent = new Intent
		intent.putExtra(EXTRA_ROUTE_IDS, routeIds.toArray)
		intent
	}

	def intentToResult(intent: Intent): Set[Long] = {
		intent.getLongArrayExtra(EXTRA_ROUTE_IDS).toSet
	}
}
class RouteChooseActivity extends RouteListBaseActivity {
	val listViews = mutable.Buffer[ListView]()

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		val actionBar = getSupportActionBar
		actionBar.setIcon(R.drawable.accept)
	}

	/** This method is called by tab fragments when their views are created. */
	override def registerRoutesList(fragment: RoutesListFragment) {
		super.registerRoutesList(fragment)

		val listView = fragment.getListView
		listViews += listView
		listView.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE)

		val actionBar = getSupportActionBar

		listView.setOnItemClickListener(new OnItemClickListener {
			def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long) {
				listViews.map(Compatibility.getCheckedItemCount(_)).sum match {
					case 0 =>
						actionBar.setHomeButtonEnabled(false)
						actionBar.setTitle(R.string.choose_routes)
					case count =>
						actionBar.setHomeButtonEnabled(true)
						actionBar.setTitle(compatibility.plurals.getQuantityString(RouteChooseActivity.this, R.plurals.routes, count, count))
				}
			}
		})
	}

	override def onOptionsItemSelected(item: MenuItem) = item.getItemId match {
		case android.R.id.home =>
			val routeIds = listViews.flatMap(_.getCheckedItemIds).toSet
			setResult(Activity.RESULT_OK, RouteChooseActivity.resultToIntent(routeIds))
			finish()
			true

		case _ => super.onOptionsItemSelected(item)
	}
}
