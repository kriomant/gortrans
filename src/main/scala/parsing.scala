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
import net.kriomant.gortrans.parsing.NodeUtils

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

