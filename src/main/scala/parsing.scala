package net.kriomant.gortrans

import org.json._

import net.kriomant.gortrans.core._
import net.kriomant.gortrans.utils.booleanUtils
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import org.xml.sax._
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMResult
import javax.xml.transform.sax.SAXSource
import org.w3c.dom.{Element, Node, NodeList, Document}
import java.text.SimpleDateFormat
import java.util.Date

object parsing {

	implicit def jsonArrayTraversable(arr: JSONArray) = new Traversable[JSONObject] {
		def foreach[T](f: JSONObject => T) = {
			for (i <- 0 until arr.length) {
				f(arr.getJSONObject(i))
			}
		}
	}

	def parseRoute(vehicleType: VehicleType.Value, obj: JSONObject) = Route(
		vehicleType,
		id = obj.getString("marsh"),
		name = obj.getString("name"),
		begin = obj.getString("stopb"),
		end = obj.getString("stope")
	)
	
	def parseSection(obj: JSONObject): (VehicleType.Value, Seq[Route]) = {
			val vtype = VehicleType(obj.getInt("type"))
			(vtype, obj.getJSONArray("ways") map {j => parseRoute(vtype, j)} toSeq)
	}

	type RoutesInfo = Map[VehicleType.Value, Seq[Route]]

	def parseRoutes(arr: JSONArray): RoutesInfo = {
		arr map parseSection toMap
	}

	def parseRoutesJson(json: String): RoutesInfo = {
		val tokenizer = new JSONTokener(json)
		val arr = new JSONArray(tokenizer)
		parseRoutes(arr)
	}

  case class VehicleInfo(
    vehicleType: VehicleType.Value,
    routeId: String,
    routeName: String,
    scheduleNr: Int,
    direction: Option[Direction.Value],
    latitude: Float, longitude: Float,
    time: Date,
    azimuth: Int,
    speed: Int,
    schedule: Seq[(String, String)]
  )
  
  def parseVehiclesLocation(obj: JSONObject): Seq[VehicleInfo] = {
    obj.getJSONArray("markers") map { o: JSONObject =>
      val routeName = o.getString("title")
      val vehicleType = VehicleType(o.getString("id_typetr").toInt-1)
      val routeId = o.getString("marsh")
      val scheduleNr = o.getString("graph").toInt
      val direction = {
        val dir = o.getString("direction")
        (dir != "-") ? (dir match {
          case "A" => Direction.Forward
          case "B" => Direction.Backward
        })
      }
      val latitude = o.getString("lat").toFloat
      val longitude = o.getString("lng").toFloat
      val time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(o.getString("time_nav"))
      val azimuth = o.getString("azimuth").toInt
      val schedule = o.getString("rasp").split('|').map { l =>
        l.splitAt(l.indexOf('+'))
      }
      val speed = o.getString("speed").toInt

      VehicleInfo(vehicleType, routeId, routeName, scheduleNr, direction, latitude, longitude, time, azimuth, speed, schedule)
    } toSeq
  }

  def parseVehiclesLocation(json: String): Seq[VehicleInfo] = {
    val tokenizer = new JSONTokener(json)
    val obj = new JSONObject(tokenizer)
    parseVehiclesLocation(obj)
  }

	def parseStopsList(content: String): Map[String, Int] = {
		content.lines.map{ line =>
			val parts = line.split('|')
			// Line format is "name|name|0|id"
			(parts(0), parts(3).toInt)
		}.toMap
	}

	case class RouteStop(name: String, length: Int)
	case class RoutePoint(stop: Option[RouteStop], latitude: Double, longitude: Double)

	def parseRoutesPoints(obj: JSONObject): Map[String, Seq[RoutePoint]] = {
		obj.getJSONArray("all") flatMap { o =>
			o.getJSONArray("r") map { m =>
				val routeId = m.getString("marsh")
				val points: Seq[RoutePoint] = m.getJSONArray("u").toSeq map { p =>
					val lat = p.getDouble("lat")
					val lng = p.getDouble("lng")
					val stop = (p has "n") ? RouteStop(p.getString("n"), p.getInt("len"))
					RoutePoint(stop, lat, lng)
				}
				routeId -> points
			}
		} toMap
	}

	def parseRoutesPoints(json: String): Map[String, Seq[RoutePoint]] = {
		parseRoutesPoints(new JSONObject(new JSONTokener(json)))
	}

	case class StopInfo(id: Int, name: String)
	
	def parseRouteStops(xml: String): Seq[StopInfo] = {
		val stops = new scala.collection.mutable.ArrayBuffer[StopInfo]

		android.util.Xml.parse(xml, new DefaultHandler {
			override def startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
				if (localName == "stop") {
					stops += StopInfo(attrs.getValue("id").toInt, attrs.getValue("title"))
				}
			}
		})
		
		stops
	}

	def parseAvailableScheduleTypes(xml: String): Map[ScheduleType.Value, String] = {
		var schedules = Map[ScheduleType.Value, String]()

		android.util.Xml.parse(xml, new DefaultHandler {
			override def startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
				if (localName == "schedule") {
					schedules += ((ScheduleType(attrs.getValue("id").toInt), attrs.getValue("title")))
				}
			}
		})

		schedules
	}

	class NodeListAsTraversable(nodeList: NodeList) extends Traversable[Node] {
		def foreach[U](f: (Node) => U) {
			for (i <- 0 until nodeList.getLength)
				f(nodeList.item(i))
		}
	}
	implicit def nodeListAsTraversable(nodeList: NodeList) = new NodeListAsTraversable(nodeList)

	class NodeUtils(node: Node) {
		def getNextSiblingElement: Element = {
			var sibling = node.getNextSibling
			while (sibling != null && sibling.getNodeType != Node.ELEMENT_NODE)
				sibling = sibling.getNextSibling
			sibling.asInstanceOf[Element]
		}
	}
	implicit def nodeUtils(node: Node) = new NodeUtils(node)
	
	def parseStopSchedule(html: String): Seq[(Int, Seq[Int])] = {
		val doc = parseHtml(html)
		(doc.getElementsByTagName("td")
			.view
			.collect { case e: Element if e.getAttribute("class") == "td_plan_h" => {
				val hour = e.getElementsByTagName("span").head.asInstanceOf[Element].getTextContent.toInt
				val minutes = for (
					minutesNode <- Option(e.getNextSiblingElement)
					if minutesNode.getAttribute("class") == "td_plan_m"
				) yield minutesNode.getElementsByTagName("div").map{_.getTextContent.toInt}.toSeq
				(hour, minutes getOrElse Seq())
			}}
		).toSeq
	}

	def parseHtml(html: String): Document = {
		val reader = new StringReader(html)
		val parser = new org.ccil.cowan.tagsoup.Parser()
		//tagsoupParser.setFeature(org.ccil.cowan.tagsoup.Parser.namespacesFeature, false);
		//tagsoupParser.setFeature(org.ccil.cowan.tagsoup.Parser.namespacePrefixesFeature, false);
		val transformer = TransformerFactory.newInstance().newTransformer()
		val domResult = new DOMResult
		transformer.transform(new SAXSource(parser, new InputSource(reader)), domResult)
		domResult.getNode.asInstanceOf[Document]
	}
}

