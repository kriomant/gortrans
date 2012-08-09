package net.kriomant.gortrans

import _root_.android.os.Bundle

import net.kriomant.gortrans.core.{Direction, VehicleType}
import android.support.v4.view.PagerAdapter
import scala.collection.JavaConverters._
import android.util.Log
import android.view._
import android.widget.{SimpleAdapter, ListView}
import com.actionbarsherlock.app.SherlockActivity
import com.actionbarsherlock.view.MenuItem
import android.content.{Intent, Context}
import java.util.Calendar

object StopScheduleActivity {
	private[this] val CLASS_NAME = classOf[RouteInfoActivity].getName

	private final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	private final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	private final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
	private final val EXTRA_STOP_ID = CLASS_NAME + ".STOP_ID"
	private final val EXTRA_STOP_NAME = CLASS_NAME + ".STOP_NAME"

	def createIntent(
		caller: Context, routeId: String, routeName: String, vehicleType: VehicleType.Value,
		stopId: Int, stopName: String
	): Intent = {
		val intent = new Intent(caller, classOf[StopScheduleActivity])
		intent.putExtra(EXTRA_ROUTE_ID, routeId)
		intent.putExtra(EXTRA_ROUTE_NAME, routeName)
		intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent.putExtra(EXTRA_STOP_ID, stopId)
		intent.putExtra(EXTRA_STOP_NAME, stopName)
		intent
	}
}

class StopScheduleActivity extends SherlockActivity with TypedActivity with ShortcutTarget {
	import StopScheduleActivity._

	private[this] final val TAG = "StopScheduleActivity"

	private var routeId: String = null
	private var routeName: String = null
	private var vehicleType: VehicleType.Value = null
	private var stopId: Int = -1
	private var stopName: String = null

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		setContentView(R.layout.stop_schedule_activity)

		val intent = getIntent
		routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))
		stopId = intent.getIntExtra(EXTRA_STOP_ID, -1)
		stopName = intent.getStringExtra(EXTRA_STOP_NAME)

		val stopScheduleFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_n,
			VehicleType.TrolleyBus -> R.string.trolleybus_n,
			VehicleType.TramWay -> R.string.tramway_n,
			VehicleType.MiniBus -> R.string.minibus_n
		).mapValues(getString)

		val actionBar = getSupportActionBar
		actionBar.setTitle(stopScheduleFormatByVehicleType(vehicleType).format(routeName, stopName))
		actionBar.setSubtitle(stopName)
		actionBar.setDisplayHomeAsUpEnabled(true)
	}

	override def onStart() {
		super.onStart()
		loadData()
	}

	def loadData() {
		new AsyncTaskBridge[Unit, Map[core.ScheduleType.Value, (String, Seq[(Int, Seq[Int])])]] {
			override def doInBackgroundBridge() = {
				val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager

				val scheduleTypes = dataManager.getAvailableRouteScheduleTypes(vehicleType, routeId, Direction.Forward)
				scheduleTypes.map { case (scheduleType, scheduleName) =>
					scheduleType -> (scheduleName, dataManager.getStopSchedule(stopId, vehicleType, routeId, Direction.Forward, scheduleType))
				}
			}

			override def onPostExecute(schedulesMap: Map[core.ScheduleType.Value, (String, Seq[(Int, Seq[Int])])]) {
				if (schedulesMap nonEmpty) {
					// Schedules are presented as map, it is needed to order them somehow.
					// I assume 'keys' and 'values' traverse items in the same order.
					val schedules = schedulesMap.values.toSeq
					val typeToIndex = schedulesMap.keys.zipWithIndex.toMap

					// Display schedule.
					val viewPager = findView(TR.schedule_tabs)
					viewPager.setAdapter(new SchedulePagesAdapter(StopScheduleActivity.this, schedules))

					// Select page corresponding to current day of week.
					val dayOfWeek = Calendar.getInstance.get(Calendar.DAY_OF_WEEK)
					val optIndex = (dayOfWeek match {
						case Calendar.SATURDAY | Calendar.SUNDAY => typeToIndex.get(core.ScheduleType.Holidays)
						case _ => typeToIndex.get(core.ScheduleType.Workdays)
					}).orElse(typeToIndex.get(core.ScheduleType.Daily))

					optIndex map { index => viewPager.setCurrentItem(index) }

					viewPager.setVisibility(View.VISIBLE);

				} else {
					findView(TR.no_schedules).setVisibility(View.VISIBLE)
				}
			}
		}.execute()
	}

	class SchedulePagesAdapter(context: Context, schedules: Seq[(String, Seq[(Int, Seq[Int])])]) extends PagerAdapter {
		def getCount: Int = schedules.length

		override def getPageTitle(position: Int): CharSequence = schedules(position)._1

		override def instantiateItem(container: ViewGroup, position: Int): AnyRef = {
			val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater]
			val view = inflater.inflate(R.layout.stop_schedule_tab, container, false).asInstanceOf[ListView]
			container.addView(view)

			val stopSchedule = schedules(position)._2
			Log.d("StopScheduleActivity", stopSchedule.length.toString)

			val adapter = new SimpleAdapter(context,
				stopSchedule.map{it => Map("hour" -> it._1, "minutes" -> it._2.mkString(" ")).asJava}.asJava,
				R.layout.stop_schedule_item,
				Array("hour", "minutes"), Array(R.id.hour, R.id.minutes)
			)
			view.setAdapter(adapter)

			view
		}

		override def destroyItem(container: ViewGroup, position: Int, `object`: AnyRef) {
			container.removeView(`object`.asInstanceOf[View])
		}

		override def setPrimaryItem(container: ViewGroup, position: Int, `object`: AnyRef) {}

		def isViewFromObject(p1: View, p2: AnyRef): Boolean = p1 == p2.asInstanceOf[View]
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case android.R.id.home => {
			val intent = RouteStopInfoActivity.createIntent(this, routeId, routeName, vehicleType, stopId, stopName)
			startActivity(intent)
			true
		}
		case _ => super.onOptionsItemSelected(item)
	}

	def getShortcutNameAndIcon: (String, Int) = {
		val vehicleShortName = getString(vehicleType match {
			case VehicleType.Bus => R.string.bus_short
			case VehicleType.TrolleyBus => R.string.trolleybus_short
			case VehicleType.TramWay => R.string.tramway_short
			case VehicleType.MiniBus => R.string.minibus_short
		})
		val name = getString(R.string.stop_schedule_shortcut_format, vehicleShortName, routeName, stopName)
		(name, R.drawable.route_stop_schedule)
	}
}

