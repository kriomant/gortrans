package net.kriomant.gortrans

import android.os.{Handler, Bundle}
import android.text.format.{DateUtils, DateFormat}
import java.util.{Date, Calendar}
import android.view.View.OnClickListener
import android.view.View
import android.util.Log
import android.content.{Intent, Context}
import android.widget.{Toast, ListView, ListAdapter, TextView}
import com.actionbarsherlock.app.SherlockActivity
import com.actionbarsherlock.view.{Window, MenuItem, Menu}
import net.kriomant.gortrans.core._
import net.kriomant.gortrans.utils.closing
import net.kriomant.gortrans.parsing.RoutePoint
import scala.Right
import net.kriomant.gortrans.core.Route
import scala.Some
import scala.Left
import net.kriomant.gortrans.parsing.VehicleInfo
import net.kriomant.gortrans.geometry.Point
import scala.collection.mutable
import java.net.{SocketTimeoutException, SocketException, ConnectException, UnknownHostException}
import java.io.EOFException

object RouteStopInfoActivity {
	private[this] val CLASS_NAME = classOf[RouteStopInfoActivity].getName
	final val TAG = CLASS_NAME

	private final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	private final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	private final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
	private final val EXTRA_STOP_ID = CLASS_NAME + ".STOP_ID"
	private final val EXTRA_STOP_NAME = CLASS_NAME + ".STOP_NAME"
	private final val EXTRA_FOLDED_STOP_INDEX = CLASS_NAME + ".FOLDED_STOP_INDEX"

	def createIntent(
		caller: Context, routeId: String, routeName: String, vehicleType: VehicleType.Value,
		stopId: Int, stopName: String, foldedStopIndex: Int = -1
	): Intent = {
		val intent = new Intent(caller, classOf[RouteStopInfoActivity])
		intent.putExtra(EXTRA_ROUTE_ID, routeId)
		intent.putExtra(EXTRA_ROUTE_NAME, routeName)
		intent.putExtra(EXTRA_VEHICLE_TYPE, vehicleType.id)
		intent.putExtra(EXTRA_STOP_ID, stopId)
		intent.putExtra(EXTRA_STOP_NAME, stopName)
		intent.putExtra(EXTRA_FOLDED_STOP_INDEX, foldedStopIndex)
		intent
	}

	final val REFRESH_PERIOD = 60 * 1000 /* ms */
}

/** List of closest vehicle arrivals for given route stop.
 */
class RouteStopInfoActivity extends SherlockActivity
	with BaseActivity
	with TypedActivity
	with ShortcutTarget
	with SherlockAsyncTaskIndicator
{
	import RouteStopInfoActivity._

	class RefreshArrivalsTask extends AsyncTaskBridge[Unit, Either[String, Seq[Date]]]
		with AsyncProcessIndicator[Unit, Either[String, Seq[Date]]]
	{
		override def doInBackgroundBridge() = {
			val fixedStopName = core.fixStopName(vehicleType, routeName, stopName)

			val client = getApplication.asInstanceOf[CustomApplication].dataManager.client
			try {
				val (response, serverTime) = client.getExpectedArrivals(routeId, vehicleType, stopId, direction)
				val now = new Date

				val arrivals = try {
					parsing.parseExpectedArrivals(response, fixedStopName, now).left.map { message =>
						getResources.getString(R.string.cant_get_arrivals, message)
					}
				} catch {
					case _: parsing.ParsingException => Left(getString(R.string.cant_parse_arrivals))
				}

				arrivals.right.map { times =>
					// Correct time difference between device and server.
					val diff = now.getTime - serverTime.getTime
					if (diff > 30 * 1000) {
						Log.w(TAG, "Time difference with server: %d ms" format diff)
					}
					times.map(t => new Date(t.getTime() + diff))
				}
			} catch {
				case ex @ (
					_: UnknownHostException |
					_: ConnectException |
					_: SocketException |
					_: EOFException |
					_: SocketTimeoutException
					) => {
					Log.v(TAG, "Network failure during arrivals fetching", ex)
					Left(getString(R.string.cant_fetch_arrivals))
				}
			}
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
	var foldedStopIndex: Int = -1

	var forwardPoints, backwardPoints: Seq[Point] = null
	var availableDirections: DirectionsEx.Value = null
	var direction: Direction.Value = null
	var route: Route = null
	var foldedRoute: Seq[FoldedRouteStop[RoutePoint]] = null
	var pointPositions: Seq[Double] = null

	var vehiclesWatcher: VehiclesWatcher = null

	var periodicRefresh = new PeriodicTimer(REFRESH_PERIOD)(refreshArrivals)

	override def onPrepareOptionsMenu(menu: Menu) = {
		if (stopId == -1) {
			menu.findItem(R.id.show_schedule).setEnabled(false)
			menu.findItem(R.id.refresh).setEnabled(false)
		}
		super.onPrepareOptionsMenu(menu)
	}

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
		setSupportProgressBarIndeterminateVisibility(false)
		setProgressBarIndeterminateVisibility(false)

		setContentView(R.layout.route_stop_info)

		setSupportProgressBarIndeterminateVisibility(false)
		setProgressBarIndeterminateVisibility(false)

		// Get route stop reference info.
		val intent = getIntent
		routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))
		stopId = intent.getIntExtra(EXTRA_STOP_ID, -1)
		stopName = intent.getStringExtra(EXTRA_STOP_NAME)
		foldedStopIndex = intent.getIntExtra(EXTRA_FOLDED_STOP_INDEX, -1)

		val actionBar = getSupportActionBar
		actionBar.setTitle(RouteListBaseActivity.getRouteTitle(this, vehicleType, routeName))
		actionBar.setSubtitle(stopName)
		actionBar.setDisplayHomeAsUpEnabled(true)

		if (stopId == -1) {
			val list = findViewById(android.R.id.list).asInstanceOf[ListView]
			val no_arrivals_view = findView(TR.no_arrivals)

			list.setAdapter(null)
			no_arrivals_view.setVisibility(View.VISIBLE)
			no_arrivals_view.setText(getResources.getString(R.string.no_arrivals))
			list.setVisibility(View.GONE)
		}

		vehiclesWatcher = new VehiclesWatcher(this, Set((vehicleType, routeId, routeName)), new VehiclesWatcher.Listener {
			def onVehiclesLocationUpdated(vehicles: Either[String, Seq[VehicleInfo]]) {
				RouteStopInfoActivity.this.onVehiclesLocationUpdated(vehicles)
			}

			def onVehiclesLocationUpdateCancelled() {}
			def onVehiclesLocationUpdateStarted() {}
		})
	}

	override def onStart() {
		super.onStart()
		loadData()
	}

	def loadData() {
		val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		dataManager.requestRoutesList(
			new ForegroundProcessIndicator(this, loadData),
			new ActionBarProcessIndicator(this)
		) {
			val db = getApplication.asInstanceOf[CustomApplication].database
			route = closing(db.fetchRoute(vehicleType, routeId)) { cursor =>
				new Route(vehicleType, routeId, cursor.name, cursor.firstStopName, cursor.lastStopName)
			}
			setDirectionText()
			loadRoutePoints()
		}
	}

	def loadRoutePoints() {
		val dataManager = getApplication.asInstanceOf[CustomApplication].dataManager
		dataManager.requestRoutePoints(
			vehicleType, routeId, routeName, route.begin, route.end,
			new ForegroundProcessIndicator(this, loadRoutePoints),
			new ActionBarProcessIndicator(this)
		) {
			routePointsUpdated()
		}
	}

	def onVehiclesLocationUpdated(result: Either[String, Seq[VehicleInfo]]) {
		val flatRoute = findView(TR.flat_route)

		result match {
			case Right(vehicles) => {
				// Snap vehicles moving in appropriate direction to route.
				val snapped = vehicles flatMap { v =>
					v.direction match {
						case Some(d) => {
							val (routePart, offset) = d match {
								case Direction.Forward => (forwardPoints, 0)
								case Direction.Backward => (backwardPoints, forwardPoints.length-1)
							}
							snapVehicleToRouteInternal(v, routePart) map { case (segmentIndex, pointPos) =>
								pointPositions(offset+segmentIndex) + pointPos * (pointPositions(offset+segmentIndex+1) - pointPositions(offset+segmentIndex))
							}
						}
						case None => None
					}
				}

				flatRoute.setVehicles(snapped.map(_.toFloat))
			}

			case Left(message) => {
				Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

				flatRoute.setVehicles(Seq())
			}
		}
	}

	protected override def onPause() {
		vehiclesWatcher.stopUpdatingVehiclesLocation()
		if (stopId != -1) {
			periodicRefresh.stop()
		}

		super.onPause()
	}

	override def onResume() {
		super.onResume()

		if (stopId != -1 && direction != null) {
			refreshArrivals()
			periodicRefresh.start()
		}

		vehiclesWatcher.startUpdatingVehiclesLocation()
	}

	override def onCreateOptionsMenu(menu: Menu): Boolean = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.route_stop_info_menu, menu)
		true
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case android.R.id.home => {
			val intent = RouteInfoActivity.createIntent(this, routeId, routeName, vehicleType)
			intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(intent)
			true
		}
		case R.id.refresh => if (stopId != -1) refreshArrivals(); true
		case R.id.show_schedule => {
			val intent = StopScheduleActivity.createIntent(this, routeId, routeName, vehicleType, stopId, stopName, direction)
			startActivity(intent)
			true
		}
		case _ => super.onOptionsItemSelected(item)
	}

	def setDirectionText() {
		if (direction != null && route != null) {
			val fmt = direction match {
				case Direction.Forward => "%1$s ⇒ %2$s"
				case Direction.Backward => "%2$s ⇒ %1$s"
			}
			findView(TR.direction_text).setText(fmt format (route.begin, route.end))
		}
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
				no_arrivals_view.setText(message)
				list.setVisibility(View.GONE)
		}
	}

	def routePointsUpdated() {
		val db = getApplication.asInstanceOf[CustomApplication].database

		import net.kriomant.gortrans.CursorIterator.cursorUtils

		// Load route data from database.
		val points = closing(db.fetchRoutePoints(vehicleType, routeId)) { cursor =>
			cursor.map(c => Point(c.longitude, c.latitude)).toIndexedSeq
		}
		val stops = closing(db.fetchRouteStops(vehicleType, routeId)) {
			_.map(c => FoldedRouteStop[Int](c.name, c.forwardPointIndex, c.backwardPointIndex)).toIndexedSeq
		}

		// If `foldedStopIndex` extra is not given (it is possible in case
		// of using shortcut created with previous versions) find stop
		// index by name.
		if (foldedStopIndex == -1) {
			foldedStopIndex = stops.indexWhere(s => s.name == stopName)
		}

		// Split points into forward and backward parts. They will be used
		// for snapping vehicle position to route.
		val firstBackwardStop = stops.reverseIterator.find(_.backward.isDefined).get
		val firstBackwardPointIndex = firstBackwardStop.backward.get
		val (fwdp, bwdp) = points.splitAt(firstBackwardStop.backward.get)
		forwardPoints = points.slice(0, firstBackwardPointIndex+1)
		backwardPoints = points.slice(firstBackwardPointIndex, points.length) :+ points.head
		assert(forwardPoints.last == backwardPoints.head)
		assert(backwardPoints.last == forwardPoints.head)

		// Convert point position to distance from route start to point.
		val (totalLength, positions) = core.straightenRoute(points)
		pointPositions = positions :+ totalLength

		// Convert point indices in stop data to point distance.
		val straightenedStops = stops.map { s =>
			FoldedRouteStop(s.name, s.forward.map(i => positions(i)), s.backward.map(i => positions(i)))
		}.toIndexedSeq

		// Now unfold stops and remember positions of current folded stop in unfolded list.
		var forwardStopIndex = -1
		var backwardStopIndex = -1
		val unfoldedStops = new mutable.ArrayBuffer[(Float, String)]
		for ((FoldedRouteStop(name, Some(pos), _), index) <- straightenedStops.zipWithIndex) {
			unfoldedStops += ((pos.toFloat, name))
			if (index == foldedStopIndex)
				forwardStopIndex = unfoldedStops.size-1
		}
		for ((FoldedRouteStop(name, _, Some(pos)), index) <- straightenedStops.zipWithIndex.reverse) {
			unfoldedStops += ((pos.toFloat, name))
			if (index == foldedStopIndex)
				backwardStopIndex = unfoldedStops.size-1
		}
		val stopIndexByDirection = Map(
			Direction.Forward  -> forwardStopIndex,
			Direction.Backward -> backwardStopIndex
		)

		// Choose current direction.
		availableDirections = stops(foldedStopIndex).directions
		direction = availableDirections match {
			case DirectionsEx.Backward => Direction.Backward
			case _ => Direction.Forward
		}
		setDirectionText()

		val flatRoute = findView(TR.flat_route)
		flatRoute.setStops(totalLength.toFloat, unfoldedStops, stopIndexByDirection(direction))

		if (availableDirections == DirectionsEx.Both) {
			val toggleDirectionButton = findView(TR.toggle_direction)
			toggleDirectionButton.setOnClickListener(new OnClickListener {
				def onClick(p1: View) {
					direction = Direction.inverse(direction)
					setDirectionText()
					if (stopId != -1) {
						refreshArrivals()
					}

					flatRoute.setStops(totalLength.toFloat, unfoldedStops, stopIndexByDirection(direction))
				}
			})
			toggleDirectionButton.setVisibility(View.VISIBLE)
		}
	}
}

class ArrivalsListAdapter(val context: Context, val items: Seq[Date])
	extends SeqAdapter with ListAdapter with EasyAdapter
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

