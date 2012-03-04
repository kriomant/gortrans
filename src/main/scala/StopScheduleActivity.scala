package net.kriomant.gortrans

import _root_.android.os.Bundle

import android.app.ListActivity
import net.kriomant.gortrans.core.{ScheduleType, Direction, VehicleType}
import android.widget.SimpleAdapter
import scala.collection.JavaConverters._

object StopScheduleActivity {
	private[this] val CLASS_NAME = classOf[RouteInfoActivity].getName

	final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
	final val EXTRA_STOP_ID = CLASS_NAME + ".STOP_ID"
	final val EXTRA_STOP_NAME = CLASS_NAME + ".STOP_NAME"
}

class StopScheduleActivity extends ListActivity with TypedActivity {
	import StopScheduleActivity._

	private[this] final val TAG = "StopScheduleActivity"

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		val intent = getIntent
		val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		val vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))
		val stopId = intent.getIntExtra(EXTRA_STOP_ID, -1)
		val stopName = intent.getStringExtra(EXTRA_STOP_NAME)

		val stopScheduleFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_stop_schedule,
			VehicleType.TrolleyBus -> R.string.trolleybus_stop_schedule,
			VehicleType.TramWay -> R.string.tramway_stop_schedule,
			VehicleType.MiniBus -> R.string.minibus_stop_schedule
		).mapValues(getString)

		setTitle(stopScheduleFormatByVehicleType(vehicleType).format(routeName, stopName))

		implicit val context = this
		val stopSchedule = DataManager.getStopSchedule(stopId, vehicleType, routeId, Direction.Forward, ScheduleType.Daily)

		val adapter = new SimpleAdapter(this,
			stopSchedule.map{it => Map("hour" -> it._1, "minutes" -> it._2.mkString(" ")).asJava}.asJava,
			R.layout.stop_schedule_item,
			Array("hour", "minutes"), Array(R.id.hour, R.id.minutes)
		)
		setListAdapter(adapter)
	}

}

