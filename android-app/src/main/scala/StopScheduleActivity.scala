package net.kriomant.gortrans

import _root_.android.os.Bundle

import net.kriomant.gortrans.core.{Direction, VehicleType}
import android.support.v4.view.PagerAdapter
import scala.collection.JavaConverters._
import android.util.Log
import android.view._
import android.widget.{TextView, SimpleAdapter, ListView}
import com.actionbarsherlock.app.SherlockActivity
import com.actionbarsherlock.view.MenuItem
import android.content.{Intent, Context}
import java.util.Calendar
import CursorIterator.cursorUtils
import android.text.{SpannableString, Spanned, SpannableStringBuilder}
import android.text.style.{CharacterStyle, ForegroundColorSpan}

object StopScheduleActivity {
	private[this] val CLASS_NAME = classOf[RouteInfoActivity].getName

	private final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	private final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	private final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
	private final val EXTRA_STOP_ID = CLASS_NAME + ".STOP_ID"
	private final val EXTRA_STOP_NAME = CLASS_NAME + ".STOP_NAME"
	private final val EXTRA_DIRECTION = CLASS_NAME + ".DIRECTION"
	private final val EXTRA_FOLDED_STOP_INDEX = CLASS_NAME + ".FOLDED_STOP_INDEX"

	def createIntent(
		caller: Context, routeId: String, routeName: String, vehicleType: VehicleType.Value,
		stopId: Int, stopName: String, foldedStopIndex: Int, direction: Direction.Value
	): Intent = {
		val intent = new Intent(caller, classOf[StopScheduleActivity])
		intent.putExtra(EXTRA_ROUTE_ID, routeId)
		intent.putExtra(EXTRA_ROUTE_NAME, routeName)
		intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent.putExtra(EXTRA_STOP_ID, stopId)
		intent.putExtra(EXTRA_STOP_NAME, stopName)
		intent.putExtra(EXTRA_DIRECTION, direction.id)
		intent.putExtra(EXTRA_FOLDED_STOP_INDEX, foldedStopIndex)
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
	private var foldedStopIndex: Int = -1
	private var direction: Direction.Value = null

	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		setContentView(R.layout.stop_schedule_activity)

		val intent = getIntent
		routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))
		stopId = intent.getIntExtra(EXTRA_STOP_ID, -1)
		stopName = intent.getStringExtra(EXTRA_STOP_NAME)
		direction = Direction(intent.getIntExtra(EXTRA_DIRECTION, -1))
		foldedStopIndex = intent.getIntExtra(EXTRA_FOLDED_STOP_INDEX, -1)

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
		val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager

		dataManager.requestStopSchedules(
			vehicleType, routeId, stopId, direction,
			new ForegroundProcessIndicator(this, loadData),
			new ActionBarProcessIndicator(this)
		) {
			val database = getApplication.asInstanceOf[CustomApplication].database

			val dbRouteId = database.findRoute(vehicleType, routeId)
			val cursor = database.fetchSchedules(dbRouteId, stopId, direction)

			val schedulesMap = cursor.map { c =>
				c.scheduleType -> ((c.scheduleName, c.schedule.groupBy(_._1).mapValues(_.map(_._2)).toSeq.sortBy(_._1)))
			}.toMap

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

				viewPager.setVisibility(View.VISIBLE)

			} else {
				findView(TR.no_schedules).setVisibility(View.VISIBLE)
			}
		}
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

			val calendar = Calendar.getInstance()
			val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
			val currentMinute = calendar.get(Calendar.MINUTE)

			def formatTimes(hour: Int, minutes: Seq[Int]) = {
				val hourStr = new SpannableString(hour.toString)
				val minBuilder = new SpannableStringBuilder

				if (hour < currentHour) {
					val span = new ForegroundColorSpan(getResources.getColor(R.color.schedule_past))

					hourStr.setSpan(CharacterStyle.wrap(span), 0, hourStr.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

					minBuilder.append(minutes.mkString(" "))
					minBuilder.setSpan(span, 0, minBuilder.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

				} else if (hour > currentHour) {
					val span = new ForegroundColorSpan(getResources.getColor(R.color.schedule_future))

					hourStr.setSpan(CharacterStyle.wrap(span), 0, hourStr.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

					minBuilder.append(minutes.mkString(" "))
					minBuilder.setSpan(span, 0, minBuilder.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

				} else {
					val (before, after) = minutes.span(_ < currentMinute)

					val spanPast = new ForegroundColorSpan(getResources.getColor(R.color.schedule_past))
					val spanFuture = new ForegroundColorSpan(getResources.getColor(R.color.schedule_future))

					hourStr.setSpan(CharacterStyle.wrap(spanFuture), 0, hourStr.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

					minBuilder.append(before.mkString(" "))
					minBuilder.setSpan(spanPast, 0, minBuilder.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

					minBuilder.append(" ")

					val mark = minBuilder.length
					minBuilder.append(after.mkString(" "))
					minBuilder.setSpan(spanFuture, mark, minBuilder.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
				}

				(hourStr, minBuilder)
			}

			val adapter = new SeqAdapter with EasyAdapter {
				case class SubViews(hour: TextView, minutes: TextView)
				val context = StopScheduleActivity.this
				val itemLayout = R.layout.stop_schedule_item
				def items = stopSchedule

				def findSubViews(view: View) = SubViews(
					view.findViewById(R.id.hour).asInstanceOf[TextView],
					view.findViewById(R.id.minutes).asInstanceOf[TextView]
				)

				def adjustItem(position: Int, views: SubViews) {
					val (hour, minutes) = items(position)
					val (hourText, minutesText) = formatTimes(hour, minutes)
					views.hour.setText(hourText)
					views.minutes.setText(minutesText)
				}
			}
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
			val intent = RouteStopInfoActivity.createIntent(this, routeId, routeName, vehicleType, stopId, stopName, foldedStopIndex)
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

