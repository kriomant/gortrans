package net.kriomant.gortrans

import android.os.{Handler, Bundle}
import android.text.format.{DateUtils, DateFormat}
import java.util.{Date, Calendar}
import android.view.View.OnClickListener
import android.view.View
import android.util.Log
import android.content.{Intent, Context}
import android.widget.{ListView, ListAdapter, TextView}
import com.actionbarsherlock.app.SherlockActivity
import com.actionbarsherlock.view.{Window, MenuItem, Menu}
import net.kriomant.gortrans.parsing.{VehicleInfo, RoutePoint, RouteStop}
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.parsing.RoutePoint
import scala.Right
import net.kriomant.gortrans.core.Route
import scala.Some
import net.kriomant.gortrans.parsing.RouteStop
import scala.Left
import net.kriomant.gortrans.parsing.VehicleInfo

object RouteStopInfoActivity {
	private[this] val CLASS_NAME = classOf[RouteStopInfoActivity].getName
	final val TAG = CLASS_NAME

	private final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	private final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	private final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
	private final val EXTRA_STOP_ID = CLASS_NAME + ".STOP_ID"
	private final val EXTRA_STOP_NAME = CLASS_NAME + ".STOP_NAME"

	def createIntent(
		caller: Context, routeId: String, routeName: String, vehicleType: VehicleType.Value,
		stopId: Int, stopName: String
	): Intent = {
		val intent = new Intent(caller, classOf[RouteStopInfoActivity])
		intent.putExtra(EXTRA_ROUTE_ID, routeId)
		intent.putExtra(EXTRA_ROUTE_NAME, routeName)
		intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent.putExtra(EXTRA_STOP_ID, stopId)
		intent.putExtra(EXTRA_STOP_NAME, stopName)
		intent
	}

	final val REFRESH_PERIOD = 60 * 1000 /* ms */
}

/** List of closest vehicle arrivals for given route stop.
 */
class RouteStopInfoActivity extends SherlockActivity
	with TypedActivity
	with ShortcutTarget
	with SherlockAsyncTaskIndicator
	with VehiclesWatcher
{
	import RouteStopInfoActivity._

	class RefreshArrivalsTask extends AsyncTaskBridge[Object, Object, Either[String, Seq[Date]]]
		with AsyncProcessIndicator[Object, Object, Either[String, Seq[Date]]]
	{
		// "Object with Object" is workaround for some strange Scala bug.
		override def doInBackgroundBridge(params: Array[Object]) = {
			val response = client.getExpectedArrivals(routeId, vehicleType, stopId, direction)
			parsing.parseExpectedArrivals(response, stopName, new Date)
		}

		override def onPostExecute(arrivals: Either[String, Seq[Date]]) {
			setArrivals(arrivals)
			super.onPostExecute(arrivals)
		}
	}

	var routeId: String = null
	var routeName: String = null
	var vehicleType: VehicleType.Value = null
	var stopId: Int = -1
	var stopName: String = null
	var availableDirections: DirectionsEx.Value = null
	var direction: Direction.Value = null
	var route: Route = null
	var routePoints: Seq[RoutePoint] = null
	var foldedRoute: Seq[FoldedRouteStop[RoutePoint]] = null
	var pointPositions: Seq[Double] = null

	val handler = new Handler

	var periodicRefresh = new PeriodicTimer(REFRESH_PERIOD)(refreshArrivals)

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
		setSupportProgressBarIndeterminateVisibility(false)
		setProgressBarIndeterminateVisibility(false)

		setContentView(R.layout.route_stop_info)

		setSupportProgressBarIndeterminateVisibility(false)
		setProgressBarIndeterminateVisibility(false)

		// Get route reference.
		val intent = getIntent
		routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))
		stopId = intent.getIntExtra(EXTRA_STOP_ID, -1)
		stopName = intent.getStringExtra(EXTRA_STOP_NAME)

		val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		route = dataManager.getRoutesList()(vehicleType).view.filter(_.id == routeId).head
		routePoints = dataManager.getRoutePoints(vehicleType, routeId, routeName)

		// Set title.
		val routeNameFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_n,
			VehicleType.TrolleyBus -> R.string.trolleybus_n,
			VehicleType.TramWay -> R.string.tramway_n,
			VehicleType.MiniBus -> R.string.minibus_n
		).mapValues(getString)

		val actionBar = getSupportActionBar
		actionBar.setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))
		actionBar.setSubtitle(stopName)
		actionBar.setDisplayHomeAsUpEnabled(true)

		// Get available directions.
		val stopPoints: Seq[RoutePoint] = routePoints.filter(_.stop.isDefined)
		foldedRoute = core.foldRoute[RoutePoint](stopPoints, _.stop.get.name)
		availableDirections = foldedRoute.find(s => s.name == stopName).get.directions

		direction = availableDirections match {
			case DirectionsEx.Backward => Direction.Backward
			case _ => Direction.Forward
		}

		setDirectionText()

		val (totalLength, positions) = core.straightenRoute(routePoints)
		pointPositions = positions

		val straightenedStops = (positions zip routePoints).collect {
			case (pos, RoutePoint(Some(RouteStop(name, _)), _, _)) => (pos.toFloat, name)
		}
		val folded = core.foldRouteInternal(straightenedStops.map(_._2))

		// Create maps from stop name to index separately for forward and backward route parts.
		val forwardStops  = folded.collect{case (name, Some(i), _) => (name, i)}.toMap
		val backwardStops = folded.collect{case (name, _, Some(i)) => (name, i)}.toMap
		val stops = direction match {
			case Direction.Forward  => forwardStops
			case Direction.Backward => backwardStops
		}

		val flatRoute = findView(TR.flat_route)
		flatRoute.setStops(totalLength.toFloat, straightenedStops, stops(stopName))

		if (availableDirections == DirectionsEx.Both) {
			val toggleDirectionButton = findView(TR.toggle_direction)
			toggleDirectionButton.setOnClickListener(new OnClickListener {
				def onClick(p1: View) {
					direction = Direction.inverse(direction)
					setDirectionText()
					refreshArrivals()

					val stops = direction match {
						case Direction.Forward  => forwardStops
						case Direction.Backward => backwardStops
					}
					flatRoute.setStops(totalLength.toFloat, straightenedStops, stops(stopName))
				}
			})
			toggleDirectionButton.setVisibility(View.VISIBLE)
		}
	}

	def getVehiclesToTrack = (vehicleType, routeId, routeName) // type, routeId, routeName

	def onVehiclesLocationUpdateStarted() {}
	def onVehiclesLocationUpdateCancelled() {}
	def onVehiclesLocationUpdated(vehicles: Seq[VehicleInfo]) {
		val splitPos = core.splitRoutePosition(foldedRoute, routePoints)
		val (forward, backward) = routePoints.splitAt(splitPos)
		// Snap vehicles moving in appropriate direction to route.
		val snapped = vehicles map { v =>
			v.direction match {
				case Some(d) => {
					val (routePart, offset) = d match {
						case Direction.Forward => (forward, 0.0)
						case Direction.Backward => (backward, pointPositions(splitPos))
					}
					snapVehicleToRouteInternal(v, routePart) map { case (segmentIndex, pointPos) =>
						offset + pointPositions(segmentIndex) + pointPos * (pointPositions(segmentIndex+1) - pointPositions(segmentIndex))
					}
				}
				case None => None
			}
		} flatten

		val flatRoute = findView(TR.flat_route)
		flatRoute.setVehicles(snapped.map(_.toFloat))
	}

	protected override def onPause() {
		stopUpdatingVehiclesLocation()
		periodicRefresh.stop()

		super.onPause()
	}

	override def onResume() {
		super.onResume()

		refreshArrivals()
		periodicRefresh.start()
		startUpdatingVehiclesLocation()
	}

	override def onCreateOptionsMenu(menu: Menu): Boolean = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.route_stop_info_menu, menu)
		true
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case android.R.id.home => {
			val intent = RouteInfoActivity.createIntent(this, route.id, route.name, route.vehicleType)
			intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(intent)
			true
		}
		case R.id.refresh => refreshArrivals(); true
		case R.id.show_schedule => {
			val intent = StopScheduleActivity.createIntent(this, routeId, routeName, vehicleType, stopId, stopName)
			startActivity(intent)
			true
		}
		case _ => super.onOptionsItemSelected(item)
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
		val task = new RefreshArrivalsTask
		task.execute()
	}

	def setArrivals(arrivals: Either[String, Seq[Date]]) {
		val list = findViewById(android.R.id.list).asInstanceOf[ListView]
		val no_arrivals_view = findView(TR.no_arrivals)
		arrivals match {
			case Right(Seq()) =>
				list.setAdapter(null)
				no_arrivals_view.setVisibility(View.VISIBLE)
				no_arrivals_view.setText(R.string.no_arrivals)
				list.setVisibility(View.GONE)

			case Right(arr) =>
				list.setAdapter(new ArrivalsListAdapter(this, arr))
				no_arrivals_view.setVisibility(View.GONE)
				list.setVisibility(View.VISIBLE)

			case Left(message) =>
				list.setAdapter(null)
				no_arrivals_view.setVisibility(View.VISIBLE)
				no_arrivals_view.setText(getResources.getString(R.string.cant_get_arrivals, message))
				list.setVisibility(View.GONE)
		}
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
