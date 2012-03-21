package net.kriomant.gortrans

import _root_.android.os.Bundle

import net.kriomant.gortrans.core.{ScheduleType, Direction, VehicleType}
import android.support.v4.view.PagerAdapter
import android.app.Activity
import scala.collection.JavaConverters._
import android.util.Log
import android.view._
import android.content.{Intent, Context}
import android.widget.{Toast, SimpleAdapter, ListView}

object StopScheduleActivity {
	private[this] val CLASS_NAME = classOf[RouteInfoActivity].getName

	final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
	final val EXTRA_STOP_ID = CLASS_NAME + ".STOP_ID"
	final val EXTRA_STOP_NAME = CLASS_NAME + ".STOP_NAME"
}

class StopScheduleActivity extends Activity with TypedActivity with ShortcutTarget {
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
			VehicleType.Bus -> R.string.bus_stop_schedule,
			VehicleType.TrolleyBus -> R.string.trolleybus_stop_schedule,
			VehicleType.TramWay -> R.string.tramway_stop_schedule,
			VehicleType.MiniBus -> R.string.minibus_stop_schedule
		).mapValues(getString)

		setTitle(stopScheduleFormatByVehicleType(vehicleType).format(routeName, stopName))

		val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		val scheduleTypes = dataManager.getAvailableRouteScheduleTypes(vehicleType, routeId, Direction.Forward)
		val schedules = scheduleTypes.toSeq.map{ case (scheduleType, scheduleName) =>
			(scheduleName, dataManager.getStopSchedule(stopId, vehicleType, routeId, Direction.Forward, scheduleType))
		}

		val viewPager = findView(TR.schedule_tabs)
		viewPager.setAdapter(new SchedulePagesAdapter(this, schedules))
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

