package net.kriomant.gortrans

import java.util.Date

import net.kriomant.gortrans.parsing._
import org.scalatest.FunSuite

class ParseExpectedArrivalsTest extends FunSuite {
  test("response with 'closest departure time' is properly parsed") {
    val response =
      """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
<html>
<head>
<link rel="icon" href="../favicon.ico" type="image/x-icon">
<meta http-equiv="content-type" content="application/xhtml+xml; charset=utf-8" />
<meta name="MobileOptimized" content="176" />
<meta name="viewport" content="width = 200" />
<link rel="stylesheet" href="pda.css" type="text/css" />
<title>Расписание маршрута №15</title>
</head>
<body>
<div id="content">
<div id="head">
Расписание движения №15        <br />c ост. «М "Площадь К.Маркса" (т)»
<br />в сторону ост. «Юго-Западный ж/м»
</div>
<br />
<br />Ближайшее время отправления <br />М &quot;Площадь К.Маркса&quot; (т)<br /><span class="time">22:58&nbsp;</span><br /><br />Страница создана 02/05/2012 в 22:54<br />
<a href="/index.php?v=&z=&m=0015&tt=3&t=1&s=848"><input type="button" value="Назад"></a>
<a href="/index.php?v=&z=&m=0015&tt=3&t=1&s=848&r=A&p=1&rand=99860505"><input type="button" value="Обновить"></a>
<br /><a href="/index.php"><input type="button" value="Главная"></a>
<a href="/index.php?m=0015&tt=3&t="><input type="button" value="Карту"></a>
</div>
</body>
</html>"""
    val now = new Date(2012, 1, 1, 0, 0, 0)
    val arrivals = parseExpectedArrivals(response, """М "Площадь К.Маркса" (т)""", now)
    assert(arrivals === Right(Seq(new Date(2012, 1, 1, 22, 58))))
  }

  test("empty arrivals list") {
    val response =
      """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
<html>
<head>
<link rel="icon" href="../favicon.ico" type="image/x-icon">
<meta http-equiv="content-type" content="application/xhtml+xml; charset=utf-8" />
<meta name="MobileOptimized" content="176" />
<meta name="viewport" content="width = 200" />
<link rel="stylesheet" href="pda.css" type="text/css" />
<title>Расписание маршрута №15</title>
</head>
<body>
<div id="content">
<div id="head">
Расписание движения №15        <br />c ост. «Кинотеатр "Обь" (Оловозавод.)»
<br />в сторону ост. «Юго-Западный ж/м»
</div>
<br />
Отправления с остановки <br />«Бугринская роща» - <br />От ост. «Бугринская роща» до ост. «Кинотеатр "Обь" (Оловозавод.)» 3 мин. пути.<br />
<br />Прибытие на остановку <br />«Площадь Сибиряков-Гвардейцев» - <span class="time">22:47 </span>
<br />От ост. «Кинотеатр "Обь" (Оловозавод.)» до ост. «Площадь Сибиряков-Гвардейцев» 14 мин. пути.<br /><br />Страница создана 02/05/2012 в 22:40<br />
<a href="/index.php?v=&z=&m=0015&tt=3&t=1&s=846"><input type="button" value="Назад"></a>
<a href="/index.php?v=&z=&m=0015&tt=3&t=1&s=846&r=A&p=1&rand=2103944390"><input type="button" value="Обновить"></a>
<br /><a href="/index.php"><input type="button" value="Главная"></a>
<a href="/index.php?m=0015&tt=3&t="><input type="button" value="Карту"></a>
</div>
</body>
</html>"""
    val now = new Date(2012, 1, 1, 0, 0, 0)
    val arrivals = parseExpectedArrivals(response, """Кинотеатр "Обь" (Оловозавод.)""", now)
    assert(arrivals === Right(Seq.empty))
  }

  test("relative time") {
    val response =
      """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
<html>
<head>
<link rel="icon" href="../favicon.ico" type="image/x-icon">
<meta http-equiv="content-type" content="application/xhtml+xml; charset=utf-8" />
<meta name="MobileOptimized" content="176" />
<meta name="viewport" content="width = 200" />
<link rel="stylesheet" href="pda.css" type="text/css" />
<title>Расписание маршрута №15</title>
</head>
<body>
<div id="content">
<div id="head">
Расписание движения №15        <br />c ост. «Кинотеатр "Обь" (Оловозавод.)»
<br />в сторону ост. «Юго-Западный ж/м»
</div>
<br />
Отправления с остановки <br />«Бугринская роща» - <span class="time">21:30 </span><br />От ост. «Бугринская роща» до ост. «Кинотеатр "Обь" (Оловозавод.)» 3 мин. пути.<br />
<br />Прибытие на остановку <br />«Площадь Сибиряков-Гвардейцев» - <span class="time">22:47 </span>
<br />От ост. «Кинотеатр "Обь" (Оловозавод.)» до ост. «Площадь Сибиряков-Гвардейцев» 14 мин. пути.<br /><br />Страница создана 02/05/2012 в 22:40<br />
<a href="/index.php?v=&z=&m=0015&tt=3&t=1&s=846"><input type="button" value="Назад"></a>
<a href="/index.php?v=&z=&m=0015&tt=3&t=1&s=846&r=A&p=1&rand=2103944390"><input type="button" value="Обновить"></a>
<br /><a href="/index.php"><input type="button" value="Главная"></a>
<a href="/index.php?m=0015&tt=3&t="><input type="button" value="Карту"></a>
</div>
</body>
</html>"""
    val now = new Date(2012, 1, 1, 0, 0, 0)
    val arrivals = parseExpectedArrivals(response, """Кинотеатр "Обь" (Оловозавод.)""", now)
    assert(arrivals === Right(Seq(new Date(2012, 1, 1, 21, 33, 0))))
  }

  test("data error") {
    val response =
      """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
<html>
<head>
    <link rel="icon" href="../favicon.ico" type="image/x-icon">
    <meta http-equiv="content-type" content="application/xhtml+xml; charset=utf-8" />
    <meta name="MobileOptimized" content="176" />
    <meta name="viewport" content="width = 200" />
    <link rel="stylesheet" href="pda.css" type="text/css" />
    <title>Расписание маршрута №15</title>
</head>
<body>

<div id="content">
<div id="head">
		Расписание движения №15        <br />c ост. «Кинотеатр "Обь" (Оловозавод.)»
		<br />в сторону ост. «Юго-Западный ж/м»
</div>
<br />
Данные не верны.<br /><br />Страница создана 03/05/2012 в 20:17<br />
<a href="/index.php?v=&z=&m=0015&tt=3&t=1&s=846"><input type="button" value="Назад"></a>
<a href="/index.php?v=&z=&m=0015&tt=3&t=1&s=846&r=A&p=1&rand=748631087"><input type="button" value="Обновить"></a>

<br /><a href="/index.php"><input type="button" value="Главная"></a>
<a href="/index.php?m=0015&tt=3&t="><input type="button" value="Карту"></a>

</div>
</body>
</html>
"""
    val now = new Date(2012, 1, 1, 0, 0, 0)
    val arrivals = parseExpectedArrivals(response, """Кинотеатр "Обь" (Оловозавод.)""", now)
    assert(arrivals === Left("Данные не верны"))
  }

  test("no stop in route") {
    val response =
      """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
<html>
<head>
<link rel="icon" href="../favicon.ico" type="image/x-icon">
<meta http-equiv="content-type" content="application/xhtml+xml; charset=utf-8" />
<meta name="MobileOptimized" content="176" />
<meta name="viewport" content="width = 200" />
<link rel="stylesheet" href="pda.css" type="text/css" />
<title>Расписание маршрута №15</title>
</head>
<body>
<div id="content">
<div id="head">
Расписание движения №15        <br />c ост. «Площадь Свердлова»
<br />в сторону ост. «Вокзал "Новосибирск-Главный"»
</div>
<br />
В выбранном маршруте отсутствует данная остановка.<br /><br />Страница создана 03/05/2012 в 23:02<br />
<a href="/index.php?v=&z=&m=15&tt=8&t=1&s=130"><input type="button" value="Назад"></a>
<a href="/index.php?v=&z=&m=15&tt=8&t=1&s=130&r=A&p=1&rand=672513017"><input type="button" value="Обновить"></a>
<br /><a href="/index.php"><input type="button" value="Главная"></a>
<a href="/index.php?m=15&tt=8&t="><input type="button" value="Карту"></a>
</div>
</body>
</html>
"""

    val now = new Date(2012, 1, 1, 0, 0, 0)
    val arrivals = parseExpectedArrivals(response, """Площадь Свердлова""", now)
    assert(arrivals === Left("В выбранном маршруте отсутствует данная остановка"))
  }
}
