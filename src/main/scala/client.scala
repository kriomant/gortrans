package net.kriomant.gortrans

import java.net.{HttpURLConnection, URL}
import java.io.{InputStreamReader, BufferedInputStream}
import utils.readerUtils

/** Client for maps.nskgortrans.ru site.
	*/
class Client {
	final val HOST = "maps.nskgortrans.ru"

	def getRoutesList(): String = {
		val url = new URL("http", HOST, "listmarsh.php?r")
		val conn = url.openConnection().asInstanceOf[HttpURLConnection]
		try {
			val stream = new BufferedInputStream(conn.getInputStream())
			// TODO: Use more effective android.util.JsonReader on API level 11.
			new InputStreamReader(stream).readAll()
		} finally {
			conn.disconnect()
		}
	}
}