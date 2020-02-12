package net.kriomant.gortrans

import android.app.SearchManager
import android.content.{Context, Intent}
import android.os.Bundle
import android.support.v4.widget.CursorAdapter
import android.text.style.UnderlineSpan
import android.text.{SpannableString, Spanned}
import android.view.View
import android.widget.AdapterView.OnItemClickListener
import android.widget.{AdapterView, ImageView, ListView, TextView}
import com.actionbarsherlock.app.SherlockActivity
import com.actionbarsherlock.view.MenuItem
import net.kriomant.gortrans.core.VehicleType
import net.kriomant.gortrans.utils.closing

class SearchActivity extends SherlockActivity with TypedActivity with BaseActivity {
  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    val actionBar = getSupportActionBar
    actionBar.setDisplayHomeAsUpEnabled(true)

    setContentView(R.layout.search_activity)

    val list = findViewById(R.id.search_result_list).asInstanceOf[ListView]
    list.setOnItemClickListener(new OnItemClickListener {
      def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long) {
        val cursor = list.getItemAtPosition(position).asInstanceOf[Database.RoutesTable.Cursor]
        val intent = RouteInfoActivity.createIntent(SearchActivity.this, cursor.externalId, cursor.name, cursor.vehicleType)
        startActivity(intent)
      }
    })

    val intent = getIntent
    if (intent.getAction == Intent.ACTION_SEARCH) {
      val query = intent.getStringExtra(SearchManager.QUERY)
      search(query)

    } else if (intent.getAction == Intent.ACTION_VIEW) {
      val dbRouteId = intent.getDataString.toLong
      val db = getApplication.asInstanceOf[CustomApplication].database

      val routeInfoIntent = closing(db.fetchRoute(dbRouteId)) { cursor =>
        RouteInfoActivity.createIntent(this, cursor.externalId, cursor.name, cursor.vehicleType)
      }

      startActivity(routeInfoIntent)
      finish()
    }
  }

  def search(query: String) {
    val database = getApplication.asInstanceOf[CustomApplication].database

    val cursor = database.searchRoutes(query)
    startManagingCursor(cursor)

    val list = findViewById(R.id.search_result_list).asInstanceOf[ListView]
    list.setAdapter(new SearchResultAdapter(this, cursor, query))
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
    case android.R.id.home =>
      val intent = MainActivity.createIntent(this)
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      startActivity(intent)
      true

    case _ => super.onOptionsItemSelected(item)
  }
}

class SearchResultAdapter(val context: Context, cursor: Database.RoutesTable.Cursor, query: String)
  extends CursorAdapter(context, cursor)
    with EasyCursorAdapter[Database.RoutesTable.Cursor] {
  val itemLayout: Int = R.layout.search_list_item

  case class SubViews(vehicleType: ImageView, number: TextView, begin: TextView, end: TextView)

  def findSubViews(view: View): SubViews = SubViews(
    view.findViewById(R.id.vehicle_type_icon).asInstanceOf[ImageView],
    view.findViewById(R.id.route_name).asInstanceOf[TextView],
    view.findViewById(R.id.start_stop_name).asInstanceOf[TextView],
    view.findViewById(R.id.end_stop_name).asInstanceOf[TextView]
  )

  def adjustItem(cursor: Database.RoutesTable.Cursor, views: SubViews) {
    views.vehicleType.setImageResource(cursor.vehicleType match {
      case VehicleType.Bus => R.drawable.tab_bus
      case VehicleType.TrolleyBus => R.drawable.tab_trolleybus
      case VehicleType.TramWay => R.drawable.tab_tram
      case VehicleType.MiniBus => R.drawable.tab_minibus
    })

    val matchStart = cursor.name.indexOf(query)
    assert(matchStart != -1)
    val styledName = new SpannableString(cursor.name)
    val highlightStyle = new UnderlineSpan
    styledName.setSpan(highlightStyle, matchStart, matchStart + query.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

    views.number.setText(styledName)
    views.begin.setText(cursor.firstStopName)
    views.end.setText(cursor.lastStopName)
  }
}