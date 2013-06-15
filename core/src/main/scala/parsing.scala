package net.kriomant.gortrans

import org.json._

import net.kriomant.gortrans.core._
import net.kriomant.gortrans.utils.BooleanUtils
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import org.xml.sax._
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMResult
import javax.xml.transform.sax.SAXSource
import org.w3c.dom.{Element, Node, NodeList, Document}
import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import util.matching.Regex.Match
import java.util.regex.Pattern
import net.kriomant.gortrans.core.Route
import java.util
import java.net.{URI, URLDecoder, URL}

object parsing {

	val NSK_TIME_ZONE = util.TimeZone.getTimeZone("+07")

	class ParsingException(msg: String) extends Exception(msg)

	implicit def jsonArrayUtils(arr: JSONArray) = new {
		def ofObjects: IndexedSeq[JSONObject] = new scala.collection.IndexedSeq[JSONObject] {
			def length = arr.length
			def apply(idx: Int) = arr.getJSONObject(idx)
		}
		def ofInts: IndexedSeq[Int] = new scala.collection.IndexedSeq[Int] {
			def length = arr.length
			def apply(idx: Int) = arr.getInt(idx)
		}
	}

	def combineDateAndTime(date: Date, time: Date, timeZone: util.TimeZone): Date = {
		val dateCalendar = Calendar.getInstance(timeZone)
		val timeCalendar = Calendar.getInstance(timeZone)

		dateCalendar.setTime(date)
		timeCalendar.setTime(time)

		dateCalendar.set(Calendar.HOUR_OF_DAY, timeCalendar.get(Calendar.HOUR_OF_DAY))
		dateCalendar.set(Calendar.MINUTE, timeCalendar.get(Calendar.MINUTE))
		dateCalendar.set(Calendar.SECOND, timeCalendar.get(Calendar.SECOND))
		dateCalendar.set(Calendar.MILLISECOND, timeCalendar.get(Calendar.MILLISECOND))

		dateCalendar.getTime
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
		(vtype, obj.getJSONArray("ways").ofObjects map {
			j => parseRoute(vtype, j)
		} toSeq)
	}

	type RoutesInfo = Map[VehicleType.Value, Seq[Route]]

	def parseRoutes(arr: JSONArray): RoutesInfo = {
		arr.ofObjects map parseSection toMap
	}

	def parseRoutesJson(json: String): RoutesInfo = {
		val tokenizer = new JSONTokener(json)
		val arr = new JSONArray(tokenizer)
		parseRoutes(arr)
	}

	abstract sealed class VehicleSchedule
	object VehicleSchedule {
		case object NotProvided extends VehicleSchedule
		case class Status(status: String) extends VehicleSchedule
		case class Schedule(schedule: Seq[(String, String)]) extends VehicleSchedule
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
		                      schedule: VehicleSchedule
		                      )

	def parseVehiclesLocation(obj: JSONObject, serverTime: Date): Seq[VehicleInfo] = {
		obj.getJSONArray("markers").ofObjects map {
			o: JSONObject =>
				val routeName = o.getString("title")
				val vehicleType = VehicleType(o.getString("id_typetr").toInt - 1)
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

				val timeString = o.getString("time_nav")
				val time = try {
					new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(timeString)
				} catch {
					// According to crash reports nskgortrans sometimes sends time without date
					// part.
					case e: java.text.ParseException => {
						val dateFormat = new SimpleDateFormat("HH:mm:ss")
						dateFormat.setTimeZone(NSK_TIME_ZONE)
						combineDateAndTime(serverTime, dateFormat.parse(timeString), NSK_TIME_ZONE)
					}
				}

				val azimuth = o.getString("azimuth").toInt
				val schedule = o.getString("rasp") match {
					case "" | "-" | "--" => VehicleSchedule.NotProvided
					case str if !str.contains('|') => VehicleSchedule.Status(str)
					case str => VehicleSchedule.Schedule(str.split('|').map { s =>
						val parts = s.split("\\+", 2)
						(parts(0), parts(1))
					})
				}
				val speed = o.getString("speed").toInt

				VehicleInfo(vehicleType, routeId, routeName, scheduleNr, direction, latitude, longitude, time, azimuth, speed, schedule)
		} toSeq
	}

	def parseVehiclesLocation(json: String, serverTime: Date): Seq[VehicleInfo] = {
		val tokenizer = new JSONTokener(json)
		val obj = new JSONObject(tokenizer)
		parseVehiclesLocation(obj, serverTime)
	}

	def parseStopsList(content: String): Map[String, Int] = {
		content.lines.map {
			line =>
				val parts = line.split('|')
				// Line format is "name|name|0|id"
				(parts(0), parts(3).toInt)
		}.toMap
	}

	case class RouteStop(
		name: String

		// Meaning of `length` field is not fully clear and it is not used by this app,
		// so just ignore it. If you want to uncomment it back then take into account that
		// this module relies heavily on `RoutePoint` (and thus `RouteStop`) object equality
		// and very often points returned by nskgortrans site have the same stop name and
		// coordinates, but different `length` value. So it is needed to find all comparisons
		// of `RoutePoint`s and rewrite them to ignore `length` field.
		// length: Int
	)

	case class RoutePoint(stop: Option[RouteStop], latitude: Double, longitude: Double)

	def parseRoutesPoints(obj: JSONObject): Map[String, Seq[RoutePoint]] = {
		obj.getJSONArray("trasses").ofObjects flatMap {
			o =>
				o.getJSONArray("r").ofObjects map {
					m =>
						val routeId = m.getString("marsh")
						val points: Seq[RoutePoint] = m.getJSONArray("u").ofObjects.map { p =>
							val lat = p.getDouble("lat")
							val lng = p.getDouble("lng")
							val stop = (p has "n") ? RouteStop(p.getString("n") /*, p.getInt("len")*/)
							RoutePoint(stop, lat, lng)
						}.toSeq
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

		val parser = javax.xml.parsers.SAXParserFactory.newInstance.newSAXParser()
		val source = new InputSource(new StringReader(xml))
		parser.parse(source, new DefaultHandler {
			override def startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
				if (qName == "stop") {
					stops += StopInfo(attrs.getValue("id").toInt, attrs.getValue("title"))
				}
			}
		})

		stops
	}

	def parseAvailableScheduleTypes(xml: String): Map[ScheduleType.Value, String] = {
		var schedules = Map[ScheduleType.Value, String]()

		val parser = javax.xml.parsers.SAXParserFactory.newInstance.newSAXParser()
		val source = new InputSource(new StringReader(xml))
		parser.parse(source, new DefaultHandler {
			override def startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
				if (qName == "schedule") {
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

		def \(tag: String): Traversable[Element] = {
			node.getChildNodes collect { case e: Element if e.getTagName == tag => e }
		}

		def \\(tag: String): Traversable[Element] = node match {
			case e: Element => e.getElementsByTagName(tag) map (_.asInstanceOf[Element])
			case _ => Seq.empty
		}

		def single(tag: String): Option[Element] = \(tag).headOption
	}

	implicit def nodeUtils(node: Node) = new NodeUtils(node)

	def parseStopSchedule(html: String): Seq[(Int, Seq[Int])] = {
		val doc = parseHtml(html)
		(doc.getElementsByTagName("td")
			.view
			.collect {
			case e: Element if e.getAttribute("class") == "td_plan_h" => {
				val hour = e.getElementsByTagName("span").head.asInstanceOf[Element].getTextContent.toInt
				val minutes = for (
					minutesNode <- Option(e.getNextSiblingElement)
					if minutesNode.getAttribute("class") == "td_plan_m"
				) yield minutesNode.getElementsByTagName("div").map {
						_.getTextContent.toInt
					}.toSeq
				(hour, minutes getOrElse Seq())
			}
		}
			).toSeq
	}

	def parseExpectedArrivals(html: String, stopName: String, now: Date): Either[String, Seq[Date]] = {
		def parseTimes(text: String): Seq[Date] = {
			val calendar = Calendar.getInstance
			text.split(" |&nbsp;|\u00A0").map {
				t =>
					val Array(h, m) = t.split(":", 2)
					calendar.setTime(now)
					calendar.set(Calendar.HOUR_OF_DAY, h.toInt)
					calendar.set(Calendar.MINUTE, m.toInt)
					calendar.set(Calendar.SECOND, 0)
					calendar.set(Calendar.MILLISECOND, 0)
					calendar.getTime
			}
		}

		val encodedName = stopName.replace("\"", "&quot;")
		val MARKERS = Seq(
			("""Ближайшее время отправления\s+<br />%s<br /><span class="time">([^<]+)</span>""" format Pattern.quote(encodedName)).r -> {
				m: Match =>
					Right(parseTimes(m.group(1)))
			},

			("""Отправления с остановки <br />«([^»]+)» - <br />От ост. «\1» до ост. «%s» (\d+) мин. пути.<br />""" format Pattern.quote(stopName)).r -> {
				m: Match =>
					Right(Seq.empty)
			},

			("""Отправления с остановки <br />«([^»]+)» - <span class="time">([^<]+)</span><br />От ост. «\1» до ост. «%s» (\d+) мин. пути.<br />""" format Pattern.quote(stopName)).r -> {
				m: Match =>
					val calendar = Calendar.getInstance
					val increment = m.group(3).toInt
					Right(parseTimes(m.group(2)) map {
						time =>
							calendar.setTime(time)
							calendar.add(Calendar.MINUTE, increment)
							calendar.getTime
					})
			},

			"""Данные не верны\.""".r -> {
				m: Match =>
					Left("Данные не верны")
			},

			"""В выбранном маршруте отсутствует данная остановка\.""".r -> {
				m: Match =>
					Left( """В выбранном маршруте отсутствует данная остановка""")
			},

			"""Прибытие транспорта не прогнозируется""".r -> {
				m: Match =>
					Left("Прибытие транспорта не прогнозируется")
			}
		)

		for ((regex, parser) <- MARKERS) {
			regex.findFirstMatchIn(html) match {
				case Some(m) => return parser(m)
				case None =>
			}
		}

		throw new ParsingException("Marker not found")
	}

	/** Returns news from newer to older.
	  */
	def parseNews(html: String): Seq[NewsStory] = {
		def withClass(className: String)(element: Element): Boolean = {
			element.getAttribute("class") == className
		}

		def parseStory(e: Element): Option[NewsStory] = {
			for (
				title <- e.single("h2").filter(withClass("contentheading")).map(_.getTextContent.trim);
				content = e.getChildNodes.filter(_.getNodeType == Node.TEXT_NODE).map(_.getTextContent).mkString(" ").trim;
				links <- (e \ "div").filter(withClass("jcomments-links")).headOption;
				readMoreLink = (links \ "a").filter(withClass("readmore-link")).headOption.flatMap(a => Option(a.getAttribute("href")));
				commentsLink <- (links \ "a").filter(withClass("comment-link")).headOption.flatMap(a => Option(a.getAttribute("href")));
				id <- decodeQueryString(new URI(commentsLink).getQuery).get("id")
			) yield NewsStory(id, title, content, readMoreLink.map(l => Client.HOST.toURI.resolve(l)))
		}

		val doc = parseHtml(html)
		val blogNode = (doc.getDocumentElement \\ "div") find { _.getAttribute("class") == "blog" }
		val storyNodes = blogNode match {
			case Some(node) => (node \ "div") filter (_.getAttribute("class") == "leading")
			case None => Seq()
		}

		storyNodes.toSeq flatMap { n => parseStory(n) }
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

	def decodeQueryString(query: String): Map[String, String] = {
		query.split("&").map(_.split("=")).collect {
			case Array(name, value) => (URLDecoder.decode(name, "UTF-8"), URLDecoder.decode(value, "UTF-8"))
		}.toMap
	}
}

