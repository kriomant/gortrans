package net.kriomant.gortrans

import java.net.URI
import java.text.SimpleDateFormat
import java.util

import net.kriomant.gortrans.core.{Direction, VehicleType}
import net.kriomant.gortrans.parsing.{VehicleInfo, VehicleSchedule, combineDateAndTime, parseVehiclesLocation}
import org.scalatest.FunSuite

object ut {
  def date(str: String, zone: String = "+00"): util.Date = date(str, util.TimeZone.getTimeZone(zone))

  def date(str: String, zone: util.TimeZone): util.Date = {
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm")
    dateFormat.setTimeZone(zone)
    dateFormat.parse(str)
  }
}

class CombineDateAndTimeTest extends FunSuite {

  import ut.date

  test("combines date and time") {
    assert(
      combineDateAndTime(date("2012-03-04 01:34"), date("1980-05-06 13:46"), util.TimeZone.getTimeZone("GMT"))
        === date("2012-03-04 13:46")
    )
  }

  test("another timezone") {
    val datePart = date("2012-01-01 19:00", "-06") // 2012-01-02 01:00 GMT
    val timePart = date("2000-01-01 11:46", "-06") // 2000-01-01 17:46 GMT
    assert(
      combineDateAndTime(datePart, timePart, util.TimeZone.getTimeZone("-06"))
        === date("2012-01-01 11:46", "-06") // 2012-01-01 17:46 GMT
    )
  }
}

class ParseVehiclesLocationTest extends FunSuite {

  import ut.date

  test("short timestamp format") {
    val response =
      """
			{"markers":[
				{"title":"13","id_typetr":"2","marsh":"13","graph":"1","direction":"A",
				"lat":"55.019932","lng":"82.923119","time_nav":"14:54:00","azimuth":"286",
				"rasp":"14:34+Stop 2|","speed":"15"
			}]}
		"""

    assert(
      parseVehiclesLocation(response, date("2012-03-04 01:02", parsing.NSK_TIME_ZONE))
        === Seq(
        VehicleInfo(
          VehicleType.TrolleyBus, "13", "13", 1, Some(Direction.Forward), 55.019932f, 82.923119f,
          date("2012-03-04 14:54:00", parsing.NSK_TIME_ZONE), 286, 15, VehicleSchedule.Schedule(Seq(("14:34", "Stop 2")))
        )
      )
    )
  }

  test("status instead of schedule") {
    val response =
      """
			{"markers":[
				{"title":"13","id_typetr":"2","marsh":"13","graph":"1","direction":"A",
				"lat":"55.019932","lng":"82.923119","time_nav":"14:54:00","azimuth":"286",
				"rasp":"Отстой","speed":"15"
			}]}
		               		"""

    assert(
      parseVehiclesLocation(response, date("2012-03-04 01:02", parsing.NSK_TIME_ZONE))
        === Seq(
        VehicleInfo(
          VehicleType.TrolleyBus, "13", "13", 1, Some(Direction.Forward), 55.019932f, 82.923119f,
          date("2012-03-04 14:54:00", parsing.NSK_TIME_ZONE), 286, 15, VehicleSchedule.Status("Отстой")
        )
      )
    )
  }

  test("no schedule data") {
    val response =
      """
			{"markers":[
				{"title":"13","id_typetr":"2","marsh":"13","graph":"1","direction":"A",
				"lat":"55.019932","lng":"82.923119","time_nav":"14:54:00","azimuth":"286",
				"rasp":"-","speed":"15"
			}]}
		               		               		"""

    assert(
      parseVehiclesLocation(response, date("2012-03-04 01:02", parsing.NSK_TIME_ZONE))
        === Seq(
        VehicleInfo(
          VehicleType.TrolleyBus, "13", "13", 1, Some(Direction.Forward), 55.019932f, 82.923119f,
          date("2012-03-04 14:54:00", parsing.NSK_TIME_ZONE), 286, 15, VehicleSchedule.NotProvided
        )
      )
    )
  }
}

class ParseNewsTest extends FunSuite {
  test("news") {

    val page =
      """
		             |<div class="component-pad">
		             |<div class="blog">
		             |			<div class="leading">
		             |<h2 class="contentheading">
		             |	title1</h2>
		             |
		             |<p class="buttonheading">
		             |	<a href="/index.php?view=article&amp;catid=1:newscat&amp;id=171:-q-q-43-60&amp;tmpl=component&amp;print=1&amp;layout=default&amp;page=" title="Печать" onclick="window.open(this.href,'win2','status=no,toolbar=no,scrollbars=yes,titlebar=no,menubar=no,resizable=yes,width=640,height=480,directories=no,location=no'); return false;" rel="nofollow"><span class="icon print"></span></a></p>
		             |
		             | content1<div class="jcomments-links"> <a href="/index.php?option=com_content&amp;view=article&amp;id=171:-q-q-43-60&amp;catid=1:newscat&amp;Itemid=15#addcomments" class="comment-link">Добавить комментарий</a></div>
		             |		</div>
		             |		<span class="leading_separator">&nbsp;</span>
		             |			<div class="leading">
		             |
		             |<h2 class="contentheading">
		             |	title2 </h2>
		             |
		             |
		             |<p class="buttonheading">
		             |	<a href="/index.php?view=article&amp;catid=1:newscat&amp;id=170:2013-05-15-02-14-55&amp;tmpl=component&amp;print=1&amp;layout=default&amp;page=" title="Печать" onclick="window.open(this.href,'win2','status=no,toolbar=no,scrollbars=yes,titlebar=no,menubar=no,resizable=yes,width=640,height=480,directories=no,location=no'); return false;" rel="nofollow"><span class="icon print"></span></a></p>
		             |
		             | <div>content2<br /></div>
		             |<div class="jcomments-links"><a class="readmore-link" href="/index.php?option=com_content&amp;view=article&amp;id=170:2013-05-15-02-14-55&amp;catid=1:newscat&amp;Itemid=15" title="Изменение нумерации маршрутов ">Подробнее...</a> <a href="/index.php?option=com_content&amp;view=article&amp;id=170:2013-05-15-02-14-55&amp;catid=1:newscat&amp;Itemid=15#addcomments" class="comment-link">Добавить комментарий</a></div>
		             |
		             |		</div>
		             |  </div>
		             |</div>
		           """.stripMargin

    val expected = Seq(
      core.NewsStory("171:-q-q-43-60", "title1", "content1", None),
      core.NewsStory("170:2013-05-15-02-14-55", "title2", "content2", Some(new URI("http://nskgortrans.ru/index.php?option=com_content&view=article&id=170:2013-05-15-02-14-55&catid=1:newscat&Itemid=15")))
    )

    expect(expected) {
      parsing.parseNews(page)
    }
  }
}
