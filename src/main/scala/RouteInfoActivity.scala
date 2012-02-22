package net.kriomant.gortrans

import _root_.android.os.Bundle

import android.app.ListActivity
import android.util.Log
import net.kriomant.gortrans.core.VehicleType
import net.kriomant.gortrans.utils.closing
import net.kriomant.gortrans.utils.readerUtils
import java.io._
import net.kriomant.gortrans.Client.{RouteDirection, RouteInfoRequest}
import net.kriomant.gortrans.parsing.{RouteStop, RoutePoint}
import android.widget.ArrayAdapter

object RouteInfoActivity {
	private[this] val CLASS_NAME = classOf[RouteInfoActivity].getName

	final val EXTRA_ROUTE_ID = CLASS_NAME + ".ROUTE_ID"
	final val EXTRA_ROUTE_NAME = CLASS_NAME + ".ROUTE_NAME"
	final val EXTRA_VEHICLE_TYPE = CLASS_NAME + ".VEHICLE_TYPE"
}

class RouteInfoActivity extends ListActivity with TypedActivity {
	import RouteInfoActivity._

	private[this] final val TAG = "RouteInfoActivity"
	
	override def onCreate(bundle: Bundle) {
		super.onCreate(bundle)

		val intent = getIntent
		val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
		val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME)
		val vehicleType = VehicleType(intent.getIntExtra(EXTRA_VEHICLE_TYPE, -1))

		val routeNameFormatByVehicleType = Map(
			VehicleType.Bus -> R.string.bus_route,
			VehicleType.TrolleyBus -> R.string.trolleybus_route,
			VehicleType.TramWay -> R.string.tramway_route,
			VehicleType.MiniBus -> R.string.minibus_route
		).mapValues(getString)

		setTitle(routeNameFormatByVehicleType(vehicleType).format(routeName))
		
		val routePointsJson = getCachedRouteInfo(routeId) getOrElse {
			Log.d(TAG, "Fetch route %s info".format(routeId))
			val client = new Client
			client.getRoutesInfo(Seq(RouteInfoRequest(vehicleType, routeId, RouteDirection.Both)))
		}

		val routePoints = parsing.parseRoutesPoints(routePointsJson)(routeId)

		cacheRoutePoints(routeId, routePointsJson)

		val stopNames = routePoints.collect {
			case RoutePoint(Some(RouteStop(name, _)), _, _) => name
		} toArray

		val listAdapter = new ArrayAdapter[String](
			this,
			android.R.layout.simple_list_item_1, android.R.id.text1,
			stopNames
		)

		setListAdapter(listAdapter)
	}

	private[this] def getRoutePointsCachePath(routeId: String) =
		new File(getCacheDir, "points/%s.json".format(routeId))

	def getCachedRouteInfo(routeId: String): Option[String] = {
		try {
			val path = getRoutePointsCachePath(routeId)
			closing(new FileInputStream(path)) { s =>
				closing(new InputStreamReader(s)) { r =>
					Some(r.readAll())
				}
			}
		} catch {
			case _: FileNotFoundException => None
		}
	}

	def cacheRoutePoints(routeId: String, routePoints: String) {
		val path = getRoutePointsCachePath(routeId)

		if (! path.getParentFile.exists())
			path.getParentFile.mkdirs()

		closing(new FileOutputStream(path)) { s =>
			closing(new OutputStreamWriter(s)) { w =>
				w.write(routePoints)
			}
		}
	}
}

