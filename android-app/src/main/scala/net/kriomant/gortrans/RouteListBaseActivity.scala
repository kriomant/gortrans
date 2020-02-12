package net.kriomant.gortrans

import java.util.regex.Pattern

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.support.v4.app.{FragmentPagerAdapter, ListFragment}
import android.support.v4.view.ViewPager
import android.support.v4.view.ViewPager.OnPageChangeListener
import android.support.v4.widget.CursorAdapter
import android.util.TypedValue
import android.view.View.OnClickListener
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.AdapterView.OnItemLongClickListener
import android.widget._
import com.actionbarsherlock.app.SherlockFragmentActivity
import com.actionbarsherlock.internal.widget.ScrollingTabContainerView
import net.kriomant.gortrans.DataManager.ProcessIndicator
import net.kriomant.gortrans.Database.RoutesTable
import net.kriomant.gortrans.core.VehicleType

import scala.collection.mutable

object RouteListBaseActivity {
  val routeRenames = Map(
    (VehicleType.Bus, "51л") -> "651л",
    (VehicleType.Bus, "4") -> "1004",
    (VehicleType.Bus, "10") -> "1011",
    (VehicleType.Bus, "20") -> "1020э",
    (VehicleType.Bus, "27") -> "1027",
    (VehicleType.Bus, "29") -> "1029",
    (VehicleType.Bus, "30") -> "1030",
    (VehicleType.Bus, "30") -> "1030с",
    (VehicleType.Bus, "34") -> "1034",
    (VehicleType.Bus, "28") -> "1038",
    (VehicleType.Bus, "42") -> "1042",
    (VehicleType.Bus, "60") -> "1060",
    (VehicleType.Bus, "64") -> "1064",
    (VehicleType.Bus, "95") -> "1095",
    (VehicleType.Bus, "96") -> "1096",
    (VehicleType.Bus, "3") -> "1103",
    (VehicleType.Bus, "9") -> "1109",
    (VehicleType.Bus, "13") -> "1113",
    (VehicleType.Bus, "19") -> "1119",
    (VehicleType.Bus, "11") -> "1129",
    (VehicleType.Bus, "31") -> "1131",
    (VehicleType.Bus, "35") -> "1135",
    (VehicleType.Bus, "57") -> "1137",
    (VehicleType.Bus, "41") -> "1141",
    (VehicleType.Bus, "5") -> "1150",
    (VehicleType.Bus, "53") -> "1153",
    (VehicleType.Bus, "59") -> "1159",
    (VehicleType.Bus, "73") -> "1173",
    (VehicleType.Bus, "79") -> "1179",
    (VehicleType.Bus, "97") -> "1197",
    (VehicleType.Bus, "98") -> "1198",
    (VehicleType.Bus, "14") -> "1204",
    (VehicleType.Bus, "18") -> "1208",
    (VehicleType.Bus, "18к") -> "1208к",
    (VehicleType.Bus, "22") -> "1209",
    (VehicleType.Bus, "15") -> "1215",
    (VehicleType.Bus, "71") -> "1231",
    (VehicleType.Bus, "32") -> "1232",
    (VehicleType.Bus, "39") -> "1239",
    (VehicleType.Bus, "49") -> "1242",
    (VehicleType.Bus, "43") -> "1243",
    (VehicleType.Bus, "6") -> "1260",
    (VehicleType.Bus, "1") -> "1301",
    (VehicleType.Bus, "12") -> "1312",
    (VehicleType.Bus, "24") -> "1324",
    (VehicleType.Bus, "33") -> "1331",
    (VehicleType.Bus, "37") -> "1337",
    (VehicleType.Bus, "50") -> "1350",
    (VehicleType.Bus, "44") -> "1444",
    (VehicleType.Bus, "17л") -> "1717л",

    (VehicleType.MiniBus, "9") -> "1009",
    (VehicleType.MiniBus, "9а") -> "1009а",
    (VehicleType.MiniBus, "12") -> "1012",
    (VehicleType.MiniBus, "46") -> "1016",
    (VehicleType.MiniBus, "28") -> "1028",
    (VehicleType.MiniBus, "31") -> "1031",
    (VehicleType.MiniBus, "5") -> "1045",
    (VehicleType.MiniBus, "17") -> "1047",
    (VehicleType.MiniBus, "48") -> "1048",
    (VehicleType.MiniBus, "50") -> "1050",
    (VehicleType.MiniBus, "53") -> "1053",
    (VehicleType.MiniBus, "10") -> "1057",
    (VehicleType.MiniBus, "62") -> "1062",
    (VehicleType.MiniBus, "62а") -> "1062а",
    (VehicleType.MiniBus, "64") -> "1068",
    (VehicleType.MiniBus, "73") -> "1073",
    (VehicleType.MiniBus, "91") -> "1091",
    (VehicleType.MiniBus, "24") -> "1104",
    (VehicleType.MiniBus, "7") -> "1107",
    (VehicleType.MiniBus, "17а") -> "1117",
    (VehicleType.MiniBus, "8") -> "1118",
    (VehicleType.MiniBus, "25") -> "1125",
    (VehicleType.MiniBus, "11") -> "1128",
    (VehicleType.MiniBus, "30") -> "1130",
    (VehicleType.MiniBus, "42") -> "1142",
    (VehicleType.MiniBus, "88") -> "1148",
    (VehicleType.MiniBus, "52") -> "1152",
    (VehicleType.MiniBus, "86") -> "1186",
    (VehicleType.MiniBus, "87") -> "1187",
    (VehicleType.MiniBus, "72") -> "1202",
    (VehicleType.MiniBus, "32") -> "1212",
    (VehicleType.MiniBus, "43") -> "1223",
    (VehicleType.MiniBus, "34") -> "1234",
    (VehicleType.MiniBus, "35") -> "1235",
    (VehicleType.MiniBus, "49") -> "1244",
    (VehicleType.MiniBus, "51") -> "1251",
    (VehicleType.MiniBus, "55") -> "1255",
    (VehicleType.MiniBus, "57") -> "1257",
    (VehicleType.MiniBus, "21") -> "1321",
    (VehicleType.MiniBus, "22") -> "1444а"
  )

  val routeNameFormatByVehicleType = Map(
    VehicleType.Bus -> R.string.bus_n,
    VehicleType.TrolleyBus -> R.string.trolleybus_n,
    VehicleType.TramWay -> R.string.tramway_n,
    VehicleType.MiniBus -> R.string.minibus_n
  )

  def getRouteTitle(context: Context, vehicleType: VehicleType.Value, routeName: String): String = {
    val name = routeName + routeRenames.get((vehicleType, routeName)).map(n => s" ($n)").getOrElse("")
    context.getString(routeNameFormatByVehicleType(vehicleType), name)
  }
}

class RouteListBaseActivity extends SherlockFragmentActivity with BaseActivity with TypedActivity {
  private[this] final val TAG = classOf[RouteListBaseActivity].getSimpleName
  protected val layoutResource: Int = R.layout.route_list_base_activity
  var tabsOrder: Seq[core.VehicleType.Value] = _
  var tabFragmentsMap: mutable.Map[VehicleType.Value, RoutesListFragment] = mutable.Map()
  var tabsView: ScrollingTabContainerView = _
  var selectedTabIndex: Int = 0

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

    setContentView(layoutResource)

    val actionBar = getSupportActionBar

    // Action bar 'tabs' navigation mode isn't used, because then tabs are part of action bar
    // even when they are displayed as second row and navigation drawer doesn't operlap them
    // which is ugly. So instead of using action bar navigation own tabs view is placed below
    // action bar.
    tabsView = new ScrollingTabContainerView(actionBar.getThemedContext)

    // Set tabs height the same as action bar's height. This is what ActionBarSherlock does.
    val tv = new TypedValue
    getTheme.resolveAttribute(com.actionbarsherlock.R.attr.actionBarSize, tv, true)
    val actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, getResources.getDisplayMetrics)
    tabsView.setContentHeight(actionBarHeight)

    if (tabsAreEmbedded) {
      actionBar.setCustomView(tabsView)
      actionBar.setDisplayShowCustomEnabled(true)
    } else {
      // Insert at the very top.
      val layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      findViewById(R.id.routes_content).asInstanceOf[LinearLayout].addView(tabsView, 0, layoutParams)
      tabsView.setAllowCollapse(false) // Prevent collapsing to dropdown list.
    }

    val vehicleTypeDrawables = Map(
      VehicleType.Bus -> R.drawable.tab_bus,
      VehicleType.TrolleyBus -> R.drawable.tab_trolleybus,
      VehicleType.TramWay -> R.drawable.tab_tram,
      VehicleType.MiniBus -> R.drawable.tab_minibus
    ).mapValues(getResources.getDrawable(_))

    val tabPager = findViewById(R.id.tab_pager).asInstanceOf[ViewPager]

    // Fix tabs order.
    tabsOrder = Seq(VehicleType.Bus, VehicleType.TrolleyBus, VehicleType.TramWay, VehicleType.MiniBus)

    tabsOrder.zipWithIndex foreach { case (vehicleType, i) =>
      val icon = vehicleTypeDrawables(vehicleType)

      val tab = actionBar.newTab.setIcon(icon)
      tabsView.addTab(tab, i == selectedTabIndex)

      // HACK: ScrollingTabContainerView doesn't have way to listen for tab selection event,
      // all it does is calls tab.select(), but it doesn't work because tabs are not added
      // to action bar.
      // Following code relies on ScrollingTabContainerView internals.
      tabsView.getChildAt(0).asInstanceOf[LinearLayout].getChildAt(i).setOnClickListener(new OnClickListener {
        def onClick(v: View) {
          tabPager.setCurrentItem(i)
          selectedTabIndex = i
        }
      })
    }

    tabPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager) {
      def getCount: Int = tabsOrder.size

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

  protected def tabsAreEmbedded: Boolean = getResources.getBoolean(R.bool.abs__action_bar_embed_tabs)

  override def onStart() {
    super.onStart()

    loadRoutes()
  }

  def updateRoutesList() {
    tabFragmentsMap.values.foreach {
      _.refresh()
    }
  }

  def loadRoutes() {
    val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
    val progressBar = findViewById(R.id.progress_bar).asInstanceOf[ProgressBar]

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

  protected def getSelectedTab: Int = selectedTabIndex

  protected def setSelectedTab(idx: Int) {
    selectedTabIndex = idx
    tabsView.setTabSelected(idx)
  }
}

class RoutesListFragment extends ListFragment {
  private[this] final val ARGUMENT_VEHICLE_TYPE = "vehicleType"
  var cursor: Database.RoutesTable.Cursor = _

  def this(vehicleType: VehicleType.Value) {
    this()

    val arguments = new Bundle
    arguments.putInt(ARGUMENT_VEHICLE_TYPE, vehicleType.id)
    setArguments(arguments)
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    inflater.inflate(R.layout.routes_list_tab, container, false)
  }

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    val database = getActivity.getApplication.asInstanceOf[CustomApplication].database
    cursor = database.fetchRoutes(vehicleType)
    getActivity.startManagingCursor(cursor)

    val listAdapter: CursorAdapter with ListAdapter with EasyCursorAdapter[RoutesTable.Cursor] = new CursorAdapter(getActivity, cursor)
      with ListAdapter
      with EasyCursorAdapter[Database.RoutesTable.Cursor] {
      val context: Context = getActivity
      val itemLayout: Int = R.layout.routes_list_item

      case class SubViews(number: TextView, oldNumber: TextView, begin: TextView, end: TextView)

      val nameRegex: Pattern = java.util.regex.Pattern.compile("^(.+) \\((.+)\\)$")

      def findSubViews(view: View): SubViews = SubViews(
        view.findViewById(R.id.route_name).asInstanceOf[TextView],
        view.findViewById(R.id.route_old_name).asInstanceOf[TextView],
        view.findViewById(R.id.start_stop_name).asInstanceOf[TextView],
        view.findViewById(R.id.end_stop_name).asInstanceOf[TextView]
      )

      def adjustItem(cursor: Database.RoutesTable.Cursor, views: SubViews) {
        val m = nameRegex.matcher(cursor.name)
        val (name, oldName) = if (m.matches) {
          (m.group(1), Some(m.group(2)))
        } else {
          (cursor.name, RouteListBaseActivity.routeRenames.get((vehicleType, cursor.name)))
        }

        val oldNameStr = oldName.map("(%s)" format _).getOrElse("")

        // Typical route name form is either digits only ("1243") or digits + letter
        // ("43л"). Append space to names without letter to align numbers on right edge of digits.
        // TextView font must be monospace for this to work.
        val lcName = name.toLowerCase
        val alignedName = if (lcName.last.isDigit) lcName + " " else lcName

        views.number.setText(alignedName)
        views.oldNumber.setText(oldNameStr)
        views.begin.setText(cursor.firstStopName)
        views.end.setText(cursor.lastStopName)
      }
    }

    setListAdapter(listAdapter)
  }

  override def onViewCreated(view: View, savedInstanceState: Bundle) {
    super.onViewCreated(view, savedInstanceState)

    getListView.setOnItemLongClickListener(new OnItemLongClickListener {
      def onItemLongClick(parent: AdapterView[_], view: View, position: Int, id: Long): Boolean = {
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

  private[this] def vehicleType = VehicleType(getArguments.getInt(ARGUMENT_VEHICLE_TYPE))


}

