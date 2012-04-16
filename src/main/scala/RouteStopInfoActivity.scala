package net.kriomant.gortrans

import android.app.ListActivity
import android.os.{Handler, Bundle}
import android.widget.{ListAdapter, TextView}
import android.text.format.{DateUtils, DateFormat}
import java.util.{Date, Calendar}
import android.view.View.OnClickListener
import android.view.{Window, View}
import android.util.Log
import android.content.{Intent, Context}
import net.kriomant.gortrans.core.{Route, Direction, VehicleType}

object RouteStopInfoActivity {
	private[this] val CLASS_NAME = classOf[RouteStopInfoActivity].getName
	final val TAG = CLASS_NAME

	final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
	final val EXTRA_STOP_ID = CLASS_NAME + ".STOP_ID"
	final val EXTRA_STOP_NAME = CLASS_NAME + ".STOP_NAME"
}

/** List of closest vehicle arrivals for given route stop.
 */
class RouteStopInfoActivity extends ListActivity with TypedActivity with ShortcutTarget {
	import RouteStopInfoActivity._

	val handler = new Handler

	var routeId: String = null
	var routeName: String = null
	var vehicleType: VehicleType.Value = null
	var stopId: Int = -1
	var stopName: String = null
	var direction: Direction.Value = Direction.Forward
	var route: Route = null

	val client = new Client

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		// Enable to show indeterminate progress indicator in activity header.
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)

		setContentView(R.layout.route_stop_info)

		// Get route reference.
		val intent = getIntent
		routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))
		stopId = intent.getIntExtra(EXTRA_STOP_ID, -1)
		stopName = intent.getStringExtra(EXTRA_STOP_NAME)

		val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		route = dataManager.getRoutesList()(vehicleType).view.filter(_.id == routeId).head

		// Set title.
		val routeNameFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_route,
			VehicleType.TrolleyBus -> R.string.trolleybus_route,
			VehicleType.TramWay -> R.string.tramway_route,
			VehicleType.MiniBus -> R.string.minibus_route
		).mapValues(getString)
		setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))

		setDirectionText()
		findView(TR.toggle_direction).setOnClickListener(new OnClickListener {
			def onClick(p1: View) {
				direction = Direction.inverse(direction)
				setDirectionText()
				refreshArrivals()
			}
		})

		findView(TR.refresh_arrivals).setOnClickListener(new OnClickListener {
			def onClick(view: View) {
				refreshArrivals()
			}
		})

		findView(TR.show_schedule).setOnClickListener(new OnClickListener {
			def onClick(view: View) {
				val intent = new Intent(RouteStopInfoActivity.this, classOf[StopScheduleActivity])
				intent.putExtra(StopScheduleActivity.EXTRA_ROUTE_ID, routeId)
				intent.putExtra(StopScheduleActivity.EXTRA_ROUTE_NAME, routeName)
				intent.putExtra(StopScheduleActivity.EXTRA_VEHICLE_TYPE, vehicleType.id)
				intent.putExtra(StopScheduleActivity.EXTRA_STOP_ID, stopId)
				intent.putExtra(StopScheduleActivity.EXTRA_STOP_NAME, stopName)
				startActivity(intent)
			}
		})
		refreshArrivals()
	}

	def setDirectionText() {
		val fmt = direction match {
			case Direction.Forward => "%1$s ⇒ %2$s"
			case Direction.Backward => "%2$s ⇒ %1$s"
		}
		findView(TR.direction_text).setText(fmt format (route.begin, route.end))
	}

	def getShortcutNameAndIcon: (String, Int) = {
		val vehicleShortName = getString(vehicleType match {
			case VehicleType.Bus => R.string.bus_short
			case VehicleType.TrolleyBus => R.string.trolleybus_short
			case VehicleType.TramWay => R.string.tramway_short
			case VehicleType.MiniBus => R.string.minibus_short
		})
		val name = getString(R.string.stop_arrivals_shortcut_format, vehicleShortName, routeName, stopName)
		(name, R.drawable.next_arrivals_shortcut)
	}

	def refreshArrivals() {
		setProgressBarIndeterminateVisibility(true)

		val expectedArrivals = parsing.parseExpectedArrivals(client.getExpectedArrivals(routeId, vehicleType, stopId, direction))

		val list = getListView
		val no_arrivals_view = findView(TR.no_arrivals)
		expectedArrivals match {
			case Some(arrivals) => {
				setListAdapter(new ArrivalsListAdapter(this, arrivals))
				no_arrivals_view.setVisibility(View.GONE)
				list.setVisibility(View.VISIBLE)
			}

			case None => {
				setListAdapter(null)
				no_arrivals_view.setVisibility(View.VISIBLE)
				list.setVisibility(View.GONE)
			}
		}

		setProgressBarIndeterminateVisibility(false)
	}
}

class ArrivalsListAdapter(val context: Context, val items: Seq[Date])
	extends ListAdapter with EasyAdapter with SeqAdapter
{
	case class SubViews(interval: TextView, time: TextView)

	val itemLayout = android.R.layout.simple_list_item_2

	def findSubViews(view: View) = SubViews (
		interval = view.findViewById(android.R.id.text1).asInstanceOf[TextView],
		time = view.findViewById(android.R.id.text2).asInstanceOf[TextView]
	)

	def adjustItem(position: Int, views: SubViews) {
		val item = items(position)
		val calendar = Calendar.getInstance
		val now = calendar.getTimeInMillis
		calendar.setTime(item)
		val when = calendar.getTimeInMillis
		Log.d("gortrans", "Now: %s, when: %s" format (new Date, item))
		views.interval.setText(DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS))
		views.time.setText(DateFormat.getTimeFormat(context).format(item))
	}
}

