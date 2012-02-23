package net.kriomant.gortrans

import java.io._
import net.kriomant.gortrans.utils.{closing, readerUtils}
import android.util.Log
import net.kriomant.gortrans.core.VehicleType
import net.kriomant.gortrans.parsing.{RoutePoint, RoutesInfo}
import net.kriomant.gortrans.Client.{RouteDirection, RouteInfoRequest}
import android.content.Context

object DataManager {
	private[this] final val TAG = "DataManager"

	val client = new Client

	def getRoutesList()(implicit context: Context): RoutesInfo = {
		val cacheName = "routes.json"
		getCachedOrFetch(cacheName, () => client.getRoutesList(), parsing.parseRoutesJson(_))
	}

	def getRoutePoints(vehicleType: VehicleType.Value, routeId: String)(implicit context: Context): Seq[RoutePoint] = {
		val cacheName = "points/%s-%s.json".format(vehicleType.toString, routeId)
		getCachedOrFetch(
			cacheName,
			() => client.getRoutesInfo(Seq(RouteInfoRequest(vehicleType, routeId, RouteDirection.Both))),
			json => parsing.parseRoutesPoints(json)(routeId)
		)
	}

	def readFromCache(relPath: String)(implicit context: Context): Option[String] = {
		try {
			val path = new File(context.getCacheDir, relPath)
			closing(new FileInputStream(path)) { s =>
				closing(new InputStreamReader(s)) { r =>
					Some(r.readAll())
				}
			}
		} catch {
			case _: FileNotFoundException => None
		}
	}

	def writeToCache(relPath: String, data: String)(implicit context: Context) {
		val path = new File(context.getCacheDir, relPath)

		if (! path.getParentFile.exists())
			path.getParentFile.mkdirs()

		closing(new FileOutputStream(path)) { s =>
			closing(new OutputStreamWriter(s)) { w =>
				w.write(data)
			}
		}
	}

	def getCachedOrFetch[T](cacheName: String, fetch: () => String, parse: String => T)(implicit context: Context): T = {
		val cached = readFromCache(cacheName)
		val data = cached getOrElse fetch()

		val parsed = parse(data)

		// Cache just retrieved info only if it was successfully parsed.
		if (cached.isEmpty)
			writeToCache(cacheName, data)

		parsed
	}
}