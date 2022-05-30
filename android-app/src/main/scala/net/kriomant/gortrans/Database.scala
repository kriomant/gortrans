package net.kriomant.gortrans

import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import android.content.{ContentValues, Context}

import utils.closing
import android.util.Log
import android.database.{Cursor, CursorWrapper}
import net.kriomant.gortrans.core._
import java.util
import utils.BooleanUtils
import CursorIterator.cursorUtils
import net.kriomant.gortrans.parsing.RoutePoint

object Database {
	val TAG = getClass.getName

	val NAME = "gortrans"
	val VERSION = 7

	class Helper(context: Context) extends SQLiteOpenHelper(context, NAME, null, VERSION) {
		def onCreate(db: SQLiteDatabase) {
			Log.i(TAG, "Create database")

			transaction(db) {
				executeScript(db, "create")
			}
		}

		def onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
			transaction(db) {
				for (from <- oldVersion until newVersion) {
					Log.i(TAG, "Upgrade database from version %d to %d" format (from, from+1))
					executeScript(db, "upgrade-%d-%d" format (from, from+1))
				}
			}
		}

		override def onOpen(db: SQLiteDatabase) {
			super.onOpen(db)

			if (!db.isReadOnly) {
				db.execSQL("PRAGMA foreign_keys = ON")
				/*closing(db.rawQuery("PRAGMA foreign_keys = ON", null)) { cursor =>
					if (!(cursor.moveToNext() && cursor.getInt(0) == 1)) {
						throw new Exception("Foreign keys are not supported by database")
					}
				}*/
			}
		}

		private def executeScript(db: SQLiteDatabase, name: String) {
			val statements = closing(context.getAssets.open("db/%s.sql" format name)) { stream =>
				scala.io.Source.fromInputStream(stream).mkString.split("---x")
			}

			for (sql <- statements)
				db.execSQL(sql)
		}

		private def transaction[T](db: SQLiteDatabase)(f: => T): T = {
			db.beginTransaction()
			try {
				val result = f
				db.setTransactionSuccessful()
				result
			} finally {
				db.endTransaction()
			}
		}
	}

	def getWritable(context: Context) = new Database(new Helper(context).getWritableDatabase)

	object RoutesTable {
		val NAME = "routes"

		val ID_COLUMN = "_id"
		val VEHICLE_TYPE_COLUMN = "vehicleType"
		val EXTERNAL_ID_COLUMN = "externalId"
		val NAME_COLUMN = "name"
		val FIRST_STOP_NAME_COLUMN = "firstStopName"
		val LAST_STOP_NAME_COLUMN = "lastStopName"

		val ALL_COLUMNS = Array(
			ID_COLUMN, VEHICLE_TYPE_COLUMN, EXTERNAL_ID_COLUMN, NAME_COLUMN,
			FIRST_STOP_NAME_COLUMN, LAST_STOP_NAME_COLUMN
		)

		val ID_COLUMN_INDEX = 0
		val VEHICLE_TYPE_COLUMN_INDEX = 1
		val EXTERNAL_ID_COLUMN_INDEX = 2
		val NAME_COLUMN_INDEX = 3
		val FIRST_STOP_NAME_COLUMN_INDEX = 4
		val LAST_STOP_NAME_COLUMN_INDEX = 5

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def id = cursor.getLong(ID_COLUMN_INDEX)
			def vehicleType = VehicleType(cursor.getInt(VEHICLE_TYPE_COLUMN_INDEX))
			def externalId = cursor.getString(EXTERNAL_ID_COLUMN_INDEX)
			def name = cursor.getString(NAME_COLUMN_INDEX)
			def firstStopName = cursor.getString(FIRST_STOP_NAME_COLUMN_INDEX)
			def lastStopName = cursor.getString(LAST_STOP_NAME_COLUMN_INDEX)
		}
	}

	object RoutePointsTable {
		val NAME = "routePoints"

		val ID_COLUMN = "_id"
		val ROUTE_ID_COLUMN = "routeId"
		val INDEX_COLUMN = "idx"
		val LATITUDE_COLUMN = "latitude"
		val LONGITUDE_COLUMN = "longitude"

		val ALL_COLUMNS = Array(
			ID_COLUMN, ROUTE_ID_COLUMN, INDEX_COLUMN, LATITUDE_COLUMN, LONGITUDE_COLUMN
		)

		val ID_COLUMN_INDEX = 0
		val ROUTE_ID_COLUMN_INDEX = 1
		val INDEX_COLUMN_INDEX = 2
		val LATITUDE_COLUMN_INDEX = 3
		val LONGITUDE_COLUMN_INDEX = 4

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def routeId = cursor.getLong(ROUTE_ID_COLUMN_INDEX)
			def index = cursor.getInt(INDEX_COLUMN_INDEX)
			def latitude = cursor.getFloat(LATITUDE_COLUMN_INDEX)
			def longitude = cursor.getFloat(LONGITUDE_COLUMN_INDEX)
		}
	}

	object FoldedRouteStopsTable {
		val NAME = "foldedRouteStops"

		val ID_COLUMN = "_id"
		val ROUTE_ID_COLUMN = "routeId"
		val NAME_COLUMN = "name"
		val INDEX_COLUMN = "idx"
		val FORWARD_POINT_INDEX_COLUMN = "forwardPointIndex"
		val BACKWARD_POINT_INDEX_COLUMN = "backwardPointIndex"

		val ALL_COLUMNS = Array(
			ID_COLUMN, ROUTE_ID_COLUMN, NAME_COLUMN, INDEX_COLUMN, FORWARD_POINT_INDEX_COLUMN, BACKWARD_POINT_INDEX_COLUMN
		)

		val ID_COLUMN_INDEX = 0
		val ROUTE_ID_COLUMN_INDEX = 1
		val NAME_COLUMN_INDEX = 2
		val INDEX_COLUMN_INDEX = 3
		val FORWARD_POINT_INDEX_COLUMN_INDEX = 4
		val BACKWARD_POINT_INDEX_COLUMN_INDEX = 5

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def routeId = cursor.getLong(ROUTE_ID_COLUMN_INDEX)
			def name = cursor.getString(NAME_COLUMN_INDEX)
			def index = cursor.getInt(INDEX_COLUMN_INDEX)
			def forwardPointIndex = !cursor.isNull(FORWARD_POINT_INDEX_COLUMN_INDEX) ? cursor.getInt(FORWARD_POINT_INDEX_COLUMN_INDEX)
			def backwardPointIndex = !cursor.isNull(BACKWARD_POINT_INDEX_COLUMN_INDEX) ? cursor.getInt(BACKWARD_POINT_INDEX_COLUMN_INDEX)

			def directions = (forwardPointIndex, backwardPointIndex) match {
				case (Some(_), Some(_)) => DirectionsEx.Both
				case (Some(_), None) => DirectionsEx.Forward
				case (None, Some(_)) => DirectionsEx.Backward
				case (None, None) => throw new AssertionError
			}
		}
	}

	object SchedulesTable {
		val NAME = "stopSchedules"

		val ID_COLUMN = "_id"
		val ROUTE_ID_COLUMN = "routeId"
		val SCHEDULE_TYPE_COLUMN = "scheduleType"
		val SCHEDULE_NAME_COLUMN = "scheduleName"
		val DIRECTION_COLUMN = "direction"
		val STOP_ID_COLUMN = "stopId"
		val SCHEDULE_COLUMN = "schedule"

		val ALL_COLUMNS = Array(
			ID_COLUMN, ROUTE_ID_COLUMN, SCHEDULE_TYPE_COLUMN, SCHEDULE_NAME_COLUMN, DIRECTION_COLUMN,
			STOP_ID_COLUMN, SCHEDULE_COLUMN
		)

		val ID_COLUMN_INDEX = 0
		val ROUTE_ID_COLUMN_INDEX = 1
		val SCHEDULE_TYPE_COLUMN_INDEX = 2
		val SCHEDULE_NAME_COLUMN_INDEX = 3
		val DIRECTION_COLUMN_INDEX = 4
		val STOP_ID_COLUMN_INDEX = 5
		val SCHEDULE_COLUMN_INDEX = 6

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def id = cursor.getLong(ID_COLUMN_INDEX)
			def routeId = cursor.getLong(ROUTE_ID_COLUMN_INDEX)
			def scheduleType = ScheduleType(cursor.getInt(SCHEDULE_TYPE_COLUMN_INDEX))
			def scheduleName = cursor.getString(SCHEDULE_NAME_COLUMN_INDEX)
			def direction = Direction(cursor.getInt(DIRECTION_COLUMN_INDEX))
			def stopId = cursor.getInt(STOP_ID_COLUMN_INDEX)
			def schedule = cursor.getString(SCHEDULE_COLUMN_INDEX) match {
				case "" => Seq()
				case str => str.split(",").map { s =>
					val Array(hour, minute) = s.split(":", 2).map(_.toInt)
					(hour, minute)
				}.toSeq
			}
		}
	}

	object ScheduleStopsTable {
		val NAME = "scheduleStops"

		val ID_COLUMN = "_id"
		val NAME_COLUMN = "name"
		val STOP_ID_COLUMN = "stopId"

		val ALL_COLUMNS = Array(ID_COLUMN, NAME_COLUMN, STOP_ID_COLUMN)

		val ID_COLUMN_INDEX = 0
		val NAME_COLUMN_INDEX = 1
		val STOP_ID_COLUMN_INDEX = 2

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def id = cursor.getLong(ID_COLUMN_INDEX)
			def name = cursor.getString(NAME_COLUMN_INDEX)
			def stopId = cursor.getInt(STOP_ID_COLUMN_INDEX)
		}
	}

	object RouteGroupsTable {
		val NAME = "routeGroups"

		val ID_COLUMN = "_id"
		val NAME_COLUMN = "name"
		val CAMERA_POSITION_COLUMN = "cameraPosition"

		val ALL_COLUMNS = Array(ID_COLUMN, NAME_COLUMN)

		val ID_COLUMN_INDEX = 0
		val NAME_COLUMN_INDEX = 1

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def id = cursor.getLong(ID_COLUMN_INDEX)
			def name = cursor.getString(NAME_COLUMN_INDEX)
		}
	}

	object RouteGroupItemsTable {
		val NAME = "routeGroupItems"

		val ID_COLUMN = "_id"
		val GROUP_ID_COLUMN = "groupId"
		val ROUTE_ID_COLUMN = "routeId"

		val ALL_COLUMNS = Array(ID_COLUMN, GROUP_ID_COLUMN, ROUTE_ID_COLUMN)

		val ID_COLUMN_INDEX = 0
		val GROUP_ID_COLUMN_INDEX = 1
		val ROUTE_ID_COLUMN_INDEX = 2

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def id = cursor.getLong(ID_COLUMN_INDEX)
			def groupId = cursor.getLong(GROUP_ID_COLUMN_INDEX)
			def routeId = cursor.getLong(ROUTE_ID_COLUMN_INDEX)
		}
	}

	object RouteGroupList {
		val NAME = "routeGroupList"

		val GROUP_ID_COLUMN = "_id"
		val GROUP_NAME_COLUMN = "groupName"
		val VEHICLE_TYPE_COLUMN = "vehicleType"
		val ROUTE_NAME_COLUMN = "routeName"

		val ALL_COLUMNS = Array(GROUP_ID_COLUMN, GROUP_NAME_COLUMN, VEHICLE_TYPE_COLUMN, ROUTE_NAME_COLUMN)

		val GROUP_ID_COLUMN_INDEX = 0
		val GROUP_NAME_COLUMN_INDEX = 1
		val VEHICLE_TYPE_COLUMN_INDEX = 2
		val ROUTE_NAME_COLUMN_INDEX = 3

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def groupId = cursor.getLong(GROUP_ID_COLUMN_INDEX)
			def groupName = cursor.getString(GROUP_NAME_COLUMN_INDEX)
			def vehicleType = VehicleType(cursor.getInt(VEHICLE_TYPE_COLUMN_INDEX))
			def routeName = cursor.getString(ROUTE_NAME_COLUMN_INDEX)
		}
	}

	object NewsTable {
		val NAME = "news"

		val ID_COLUMN = "_id"
		val EXTERNAL_ID_COLUMN = "externalId"
		val TITLE_COLUMN = "title"
		val CONTENT_COLUMN = "content"
		val READ_MORE_LINK_COLUMN = "readMoreLink"
		val LOADED_AT_COLUMN = "loadedAt"

		val ALL_COLUMNS = Array(
			ID_COLUMN, EXTERNAL_ID_COLUMN, TITLE_COLUMN, CONTENT_COLUMN,
			READ_MORE_LINK_COLUMN, LOADED_AT_COLUMN
		)

		val ID_COLUMN_INDEX = 0
		val EXTERNAL_ID_COLUMN_INDEX = 1
		val TITLE_COLUMN_INDEX = 2
		val CONTENT_COLUMN_INDEX = 3
		val READ_MORE_LINK_COLUMN_INDEX = 4
		val LOADED_AT_COLUMN_INDEX = 5

		class Cursor(cursor: android.database.Cursor) extends CursorWrapper(cursor) {
			def externalId = cursor.getString(EXTERNAL_ID_COLUMN_INDEX)
			def title = cursor.getString(TITLE_COLUMN_INDEX)
			def content = cursor.getString(CONTENT_COLUMN_INDEX)
			def readMoreLink = !cursor.isNull(READ_MORE_LINK_COLUMN_INDEX) ? cursor.getString(READ_MORE_LINK_COLUMN_INDEX)
			def loadedAt = new util.Date(cursor.getLong(LOADED_AT_COLUMN_INDEX))
		}
	}

	def escapeLikeArgument(arg: String, escapeChar: Char): String = {
		(arg
			.replace(escapeChar.toString, escapeChar.toString+escapeChar.toString)
			.replace("%", escapeChar.toString+"%")
			.replace("_", escapeChar.toString+"_")
		)
	}

	case class GroupInfo(id: Long, name: String, routes: Set[(VehicleType.Value, String)])
}

class Database(db: SQLiteDatabase) {
	import Database._

	def fetchRoute(dbRouteId: Long): RoutesTable.Cursor = {
		val cursor = db.query(
			RoutesTable.NAME, RoutesTable.ALL_COLUMNS,
			"%s=?" format RoutesTable.ID_COLUMN, Array(dbRouteId.toString),
			null, null, null
		)

		if (!cursor.moveToFirst())
			throw new Exception("Route #%d not found" format dbRouteId)

		new RoutesTable.Cursor(cursor)
	}

	def fetchRoute(vehicleType: VehicleType.Value, externalId: String): RoutesTable.Cursor = {
		val cursor = db.query(
			RoutesTable.NAME, RoutesTable.ALL_COLUMNS,

			"%s=? AND %s=?" format (RoutesTable.VEHICLE_TYPE_COLUMN, RoutesTable.EXTERNAL_ID_COLUMN),
			Array(vehicleType.id.toString, externalId),

			null, null, null
		)

		if (!cursor.moveToFirst())
			throw new Exception("Route %s/%s not found" format (vehicleType, externalId))

		new RoutesTable.Cursor(cursor)
	}

	def fetchRoutes(): RoutesTable.Cursor = {
		new RoutesTable.Cursor(db.query(RoutesTable.NAME, RoutesTable.ALL_COLUMNS, null, null, null, null, null))
	}

	def fetchRoutes(vehicleType: VehicleType.Value): RoutesTable.Cursor = {
		new RoutesTable.Cursor(db.query(
			RoutesTable.NAME, RoutesTable.ALL_COLUMNS,
			RoutesTable.VEHICLE_TYPE_COLUMN formatted "%s=?", Array(vehicleType.id.toString),
			null, null, RoutesTable.NAME_COLUMN formatted "CAST(%s AS INTEGER)"
		))
	}

	def addOrUpdateRoute(vehicleType: VehicleType.Value, externalId: String, name: String, firstStopName: String, lastStopName: String) {
		val values = new ContentValues(5)
		values.put(RoutesTable.NAME_COLUMN, name)
		values.put(RoutesTable.FIRST_STOP_NAME_COLUMN, firstStopName)
		values.put(RoutesTable.LAST_STOP_NAME_COLUMN, lastStopName)

		val affected = db.update(
			RoutesTable.NAME, values,
			"%s=? AND %s=?" format (RoutesTable.VEHICLE_TYPE_COLUMN, RoutesTable.EXTERNAL_ID_COLUMN),
			Array(vehicleType.id.toString, externalId)
		)
		if (affected == 0) {
			values.put(RoutesTable.VEHICLE_TYPE_COLUMN, vehicleType.id.asInstanceOf[java.lang.Integer])
			values.put(RoutesTable.EXTERNAL_ID_COLUMN, externalId)
			db.insertOrThrow(RoutesTable.NAME, null, values)
		}

	}

	def deleteRoute(vehicleType: VehicleType.Value, externalId: String) {
		db.delete(RoutesTable.NAME, "vehicleType=? AND externalId=?", Array(vehicleType.id.toString, externalId))
	}

	def searchRoutes(query: String): RoutesTable.Cursor = {
		new RoutesTable.Cursor(db.query(
			RoutesTable.NAME, RoutesTable.ALL_COLUMNS,

			RoutesTable.NAME_COLUMN formatted "%s LIKE ? ESCAPE '~'",
			Array("%%%s%%" format escapeLikeArgument(query, '~')),

			null, null, RoutesTable.NAME_COLUMN formatted "CAST(%s AS INTEGER)"
		))
	}

	def getLastUpdateTime(code: String): Option[util.Date] = {
		closing(db.query("updateTimes", Array("lastUpdateTime"), "code=?", Array(code), null, null, null)) { cursor =>
			cursor.moveToNext() ? new util.Date(cursor.getLong(0))
		}
	}

	def updateLastUpdateTime(code: String, lastUpdateTime: util.Date) {
		val values = new ContentValues
		values.put("code", code)
		values.put("lastUpdateTime", lastUpdateTime.getTime.asInstanceOf[java.lang.Long])
		db.replaceOrThrow("updateTimes", null, values)
	}

	def findRoute(vehicleType: VehicleType.Value, externalRouteId: String): Long = {
		val cursor = db.query(
			RoutesTable.NAME, Array(RoutesTable.ID_COLUMN),

			"%s=? AND %s=?" format (RoutesTable.VEHICLE_TYPE_COLUMN, RoutesTable.EXTERNAL_ID_COLUMN),
			Array(vehicleType.id.toString, externalRouteId.toString),

			null, null, null
		)

		closing(cursor) { _ =>
			if (cursor.moveToNext())
				cursor.getLong(RoutesTable.ID_COLUMN_INDEX)
			else
				throw new Exception("Unknown route")
		}
	}

	def clearStopsAndPoints(routeId: Long) {
		db.delete(FoldedRouteStopsTable.NAME, "%s=?" format FoldedRouteStopsTable.ROUTE_ID_COLUMN, Array(routeId.toString))
		db.delete(RoutePointsTable.NAME, "%s=?" format RoutePointsTable.ROUTE_ID_COLUMN, Array(routeId.toString))
	}

	def addRoutePoint(routeId: Long, index: Int, point: RoutePoint) {
		val values = new ContentValues
		values.put(RoutePointsTable.ROUTE_ID_COLUMN, routeId.asInstanceOf[java.lang.Long])
		values.put(RoutePointsTable.INDEX_COLUMN, index.asInstanceOf[java.lang.Integer])
		values.put(RoutePointsTable.LATITUDE_COLUMN, point.latitude)
		values.put(RoutePointsTable.LONGITUDE_COLUMN, point.longitude)
		db.insert(RoutePointsTable.NAME, null, values)
	}

	def addRouteStop(routeId: Long, name: String, index: Int, forwardPointIndex: Option[Int], backwardPointIndex: Option[Int]) {
		require(forwardPointIndex.isDefined || backwardPointIndex.isDefined)

		val values = new ContentValues
		values.put(FoldedRouteStopsTable.ROUTE_ID_COLUMN, routeId.asInstanceOf[java.lang.Long])
		values.put(FoldedRouteStopsTable.NAME_COLUMN, name)
		values.put(FoldedRouteStopsTable.INDEX_COLUMN, index.asInstanceOf[java.lang.Integer])
		forwardPointIndex match {
			case Some(idx) => values.put(FoldedRouteStopsTable.FORWARD_POINT_INDEX_COLUMN, idx.asInstanceOf[java.lang.Integer])
			case None => values.putNull(FoldedRouteStopsTable.FORWARD_POINT_INDEX_COLUMN)
		}
		backwardPointIndex match {
			case Some(idx) => values.put(FoldedRouteStopsTable.BACKWARD_POINT_INDEX_COLUMN, idx.asInstanceOf[java.lang.Integer])
			case None => values.putNull(FoldedRouteStopsTable.BACKWARD_POINT_INDEX_COLUMN)
		}
		db.insertOrThrow(FoldedRouteStopsTable.NAME, null, values)
	}

	def fetchRouteStops(routeId: Long): FoldedRouteStopsTable.Cursor = {
		new FoldedRouteStopsTable.Cursor(db.query(
			FoldedRouteStopsTable.NAME, FoldedRouteStopsTable.ALL_COLUMNS,
			FoldedRouteStopsTable.ROUTE_ID_COLUMN formatted "%s=?", Array(routeId.toString),
			null, null, FoldedRouteStopsTable.INDEX_COLUMN
		))
	}

	def fetchRouteStops(vehicleType: VehicleType.Value, externalRouteId: String): FoldedRouteStopsTable.Cursor =
		fetchRouteStops(findRoute(vehicleType, externalRouteId))

	def fetchRoutePoints(routeId: Long): RoutePointsTable.Cursor = {
		new RoutePointsTable.Cursor(db.query(
			RoutePointsTable.NAME, RoutePointsTable.ALL_COLUMNS,
			RoutePointsTable.ROUTE_ID_COLUMN formatted "%s=?", Array(routeId.toString),
			null, null, RoutePointsTable.INDEX_COLUMN
		))
	}

	def fetchRoutePoints(vehicleType: VehicleType.Value, externalRouteId: String): RoutePointsTable.Cursor =
		fetchRoutePoints(findRoute(vehicleType, externalRouteId))

	def fetchSchedules(dbRouteId: Long, stopId: Int, direction: Direction.Value): SchedulesTable.Cursor = {
		new SchedulesTable.Cursor(db.query(
			SchedulesTable.NAME, SchedulesTable.ALL_COLUMNS,

			"routeId=? AND stopId=? AND direction=?",
			Array(dbRouteId.toString, stopId.toString, direction.id.toString),

			null, null, null
		))
	}

	def clearSchedules(dbRouteId: Long, stopId: Int, direction: Direction.Value) {
		db.delete(SchedulesTable.NAME, "routeId=? AND stopId=? AND direction=?", Array(dbRouteId.toString, stopId.toString, direction.id.toString))
	}

	def addSchedule(dbRouteId: Long, stopId: Int, direction: Direction.Value, scheduleType: ScheduleType.Value, scheduleName: String, schedule: Seq[(Int, Int)]) {
		val values = new ContentValues
		values.put(SchedulesTable.ROUTE_ID_COLUMN, dbRouteId.asInstanceOf[java.lang.Long])
		values.put(SchedulesTable.STOP_ID_COLUMN, stopId.asInstanceOf[java.lang.Integer])
		values.put(SchedulesTable.DIRECTION_COLUMN, direction.id.asInstanceOf[java.lang.Integer])
		values.put(SchedulesTable.SCHEDULE_TYPE_COLUMN, scheduleType.id.asInstanceOf[java.lang.Integer])
		values.put(SchedulesTable.SCHEDULE_NAME_COLUMN, scheduleName)
		values.put(SchedulesTable.SCHEDULE_COLUMN, schedule.map{case (hour, minute) => "%d:%d" format (hour, minute)}.mkString(","))
		db.insertOrThrow(SchedulesTable.NAME, null, values)
	}

	def clearStops() {
		db.delete(ScheduleStopsTable.NAME, null, null);
	}

	def addStop(name: String, stopId: Int) {
		val values = new ContentValues
		values.put(ScheduleStopsTable.NAME_COLUMN, name)
		values.put(ScheduleStopsTable.STOP_ID_COLUMN, stopId.asInstanceOf[java.lang.Integer])
		db.insertOrThrow(ScheduleStopsTable.NAME, null, values)
	}

	def findStopId(name: String): Option[Int] = {
		val cursor = new ScheduleStopsTable.Cursor(db.query(
			ScheduleStopsTable.NAME, ScheduleStopsTable.ALL_COLUMNS,
			"%s=?" format ScheduleStopsTable.NAME_COLUMN, Array(name),
			null, null, null
		))
		findOne(cursor) { _ => cursor.stopId }
	}

	def loadGroups(): Seq[GroupInfo] = {
		val cursor = new RouteGroupList.Cursor(db.query(
			RouteGroupList.NAME, RouteGroupList.ALL_COLUMNS,
			null, null, null, null, RouteGroupList.GROUP_ID_COLUMN
		))
		closing(cursor) { _ =>
			(cursor
				.map(c => (c.groupId, c.groupName, c.vehicleType, c.routeName))
				.toSeq.groupBy(_._1)
				.map{ case (groupId, items) => GroupInfo(groupId, items.head._2, items.map(i => (i._3, i._4)).toSet) }
				.toSeq
			)
		}
	}

	def getGroupName(groupId: Long): String = {
		val cursor = db.query(
			RouteGroupsTable.NAME, Array(RouteGroupsTable.NAME_COLUMN),
			"%s=?" format RouteGroupsTable.ID_COLUMN, Array(groupId.toString), null, null, null
		)
		fetchOne(cursor) { _ => cursor.getString(0) }
	}

	def getGroupItems(groupId: Long): Set[Long] = {
		val cursor = db.query(
			RouteGroupItemsTable.NAME, Array(RouteGroupItemsTable.ROUTE_ID_COLUMN),
			"%s=?" format RouteGroupItemsTable.GROUP_ID_COLUMN, Array(groupId.toString), null, null, null
		)
		closing(cursor) { _ =>
			cursor.map{ c => c.getLong(0) }.toSet
		}
	}

	def createGroup(name: String): Long = {
		val values = new ContentValues
		values.put(RouteGroupsTable.NAME_COLUMN, name)
		db.insertOrThrow(RouteGroupsTable.NAME, null, values)
	}

	def addRouteToGroup(groupId: Long, routeId: Long): Long = {
		val values = new ContentValues
		values.put(RouteGroupItemsTable.GROUP_ID_COLUMN, groupId.asInstanceOf[java.lang.Long])
		values.put(RouteGroupItemsTable.ROUTE_ID_COLUMN, routeId.asInstanceOf[java.lang.Long])
		db.insertOrThrow(RouteGroupItemsTable.NAME, null, values)
	}

	def deleteGroup(groupId: Long) {
		db.delete(RouteGroupsTable.NAME, "%s=?" format RouteGroupsTable.ID_COLUMN, Array(groupId.toString))
	}

	def updateGroup(groupId: Long, name: String, routes: Set[Long]) {
		val values = new ContentValues
		values.put(RouteGroupsTable.NAME_COLUMN, name)
		db.update(
			RouteGroupsTable.NAME, values,
			RouteGroupsTable.ID_COLUMN formatted "%s=?", Array(groupId.toString)
		)
		db.delete(
			RouteGroupItemsTable.NAME,
			RouteGroupItemsTable.GROUP_ID_COLUMN formatted "%s=?", Array(groupId.toString)
		)
		routes foreach { r => addRouteToGroup(groupId, r) }
	}

	def saveGroupMapPosition(groupId: Long, serializedPosition: String) {
		val values = new ContentValues
		values.put(RouteGroupsTable.CAMERA_POSITION_COLUMN, serializedPosition)
		db.update(RouteGroupsTable.NAME, values, "%s=?" format RouteGroupsTable.ID_COLUMN, Array(groupId.toString))
	}

	def loadGroupMapPosition(groupId: Long): Option[String] = {
		fetchOne(db.query(
			RouteGroupsTable.NAME, Array(RouteGroupsTable.CAMERA_POSITION_COLUMN),
			"%s=?" format RouteGroupsTable.ID_COLUMN, Array(groupId.toString), null, null, null
		)) { c => !c.isNull(0) ? c.getString(0) }
	}

	def loadNews(): NewsTable.Cursor = {
		new NewsTable.Cursor(db.query(
			NewsTable.NAME, NewsTable.ALL_COLUMNS,
			null, null, null, null, NewsTable.ID_COLUMN formatted "%s DESC"
		))
	}

	def loadLatestNewsStoryExternalId(): Option[String] = {
		val cursor = db.query(
			NewsTable.NAME, Array(NewsTable.EXTERNAL_ID_COLUMN),
			null, null, null, null,
			"%s DESC" format NewsTable.ID_COLUMN, "1"
		)
		closing(cursor) { _ =>
			if (cursor.moveToNext())
				Some(cursor.getString(0))
			else
				None
		}
	}

	def addNews(story: NewsStory, loadedAt: util.Date) {
		val values = new ContentValues
		values.put(NewsTable.EXTERNAL_ID_COLUMN, story.id)
		values.put(NewsTable.TITLE_COLUMN, story.title)
		values.put(NewsTable.CONTENT_COLUMN, story.content)
		values.put(NewsTable.READ_MORE_LINK_COLUMN, story.readMoreLink.map(_.toString).orNull)
		values.put(NewsTable.LOADED_AT_COLUMN, loadedAt.getTime: java.lang.Long)

		db.insert(NewsTable.NAME, null, values)
	}

	def loadNewsLoadedSince(time: util.Date): NewsTable.Cursor = {
		new NewsTable.Cursor(db.query(
			NewsTable.NAME, NewsTable.ALL_COLUMNS,
			NewsTable.LOADED_AT_COLUMN formatted "%s>?", Array(time.getTime.toString),
			null, null, NewsTable.ID_COLUMN formatted "%s DESC"
		))
	}

	def fetchOne[C <: Cursor, T](cursor: C)(f: C => T): T = {
		closing(cursor) { _ =>
			if (cursor.moveToNext())
				f(cursor)
			else
				throw new Exception("Row not found")
		}
	}

	def findOne[C <: Cursor, T](cursor: C)(f: C => T): Option[T] = {
		closing(cursor) { _ =>
			cursor.moveToNext() ? f(cursor)
		}
	}

	def close() { db.close() }

	def transaction[T](f: => T): T = {
		db.beginTransaction()
		try {
			val result = f
			db.setTransactionSuccessful()
			result
		} finally {
			db.endTransaction()
		}
	}
}
