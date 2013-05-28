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
	def createIntent(caller: Context, selectedRoutes: Set[Long] = Set.empty): Intent = {
		val intent = new Intent(caller, classOf[RouteChooseActivity])
		intent.putExtra(EXTRA_ROUTE_IDS, selectedRoutes.toArray)
		intent
	}

	val EXTRA_ROUTE_IDS = "route-ids"

	private def resultToIntent(routeIds: collection.Set[Long]) = {
		val intent = new Intent
		intent.putExtra(EXTRA_ROUTE_IDS, routeIds.toArray)
		intent
	}

	def intentToResult(intent: Intent): Set[Long] = {
		intent.getLongArrayExtra(EXTRA_ROUTE_IDS).toSet
	}
}
class RouteChooseActivity extends RouteListBaseActivity {
	import RouteChooseActivity._

	val listViews = mutable.Buffer[ListView]()
	var routeIds = mutable.Set.empty[Long]

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		val actionBar = getSupportActionBar
		actionBar.setIcon(R.drawable.accept)

		val ids = getIntent.getLongArrayExtra(EXTRA_ROUTE_IDS)
		if (ids != null) {
			routeIds ++= ids
		}

		updateControls()
	}

	/** This method is called by tab fragments when their views are created. */
	override def registerRoutesList(fragment: RoutesListFragment) {
		super.registerRoutesList(fragment)

		val listView = fragment.getListView
		listViews += listView
		listView.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE)

		if (routeIds.nonEmpty) {
			val adapter = listView.getAdapter
			for (i <- 0 until adapter.getCount) {
				val id = adapter.getItemId(i)
				if (routeIds contains id)
					listView.setItemChecked(i, true)
			}
		}

		listView.setOnItemClickListener(new OnItemClickListener {
			def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long) {
				if (listView.isItemChecked(position))
					routeIds += id
				else
					routeIds -= id

				updateControls()
			}
		})
	}

	private def updateControls() {
		val actionBar = getSupportActionBar

		routeIds.size match {
			case 0 =>
				actionBar.setHomeButtonEnabled(false)
				actionBar.setTitle(R.string.choose_routes)
			case count =>
				actionBar.setHomeButtonEnabled(true)
				actionBar.setTitle(compatibility.plurals.getQuantityString(RouteChooseActivity.this, R.plurals.routes, count, count))
		}
	}

	override def onOptionsItemSelected(item: MenuItem) = item.getItemId match {
		case android.R.id.home =>
			setResult(Activity.RESULT_OK, RouteChooseActivity.resultToIntent(routeIds))
			finish()
			true

		case _ => super.onOptionsItemSelected(item)
	}
}
