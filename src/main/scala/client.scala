package net.kriomant.gortrans

import net.kriomant.gortrans.core.RoutesInfo
import java.net.{HttpURLConnection, URL}
import java.io.{InputStreamReader, BufferedInputStream}
import utils.readerUtils

/** Client for maps.nskgortrans.ru site.
	*/
class Client {
	final val HOST = "maps.nskgortrans.ru"

	def getRoutesList(): RoutesInfo = {
		val url = new URL("http", HOST, "listmarsh.php?r")
		val conn = url.openConnection().asInstanceOf[HttpURLConnection]
		try {
			val stream = new BufferedInputStream(conn.getInputStream())
			// TODO: Use more effective android.util.JsonReader on API level 11.
			val content = new InputStreamReader(stream).readAll()
			parsing.parseRoutesJson(content)
		} finally {
			conn.disconnect()
		}
	}
}