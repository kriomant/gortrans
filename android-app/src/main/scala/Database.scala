package net.kriomant.gortrans

import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import android.content.{ContentValues, Context}

import utils.closing
import android.util.Log
import android.database.{Cursor, CursorWrapper}
import net.kriomant.gortrans.core.VehicleType
import java.util
import utils.booleanUtils

object Database {
	val TAG = getClass.getName

	val NAME = "gortrans"
	val VERSION = 1

	class Helper(context: Context) extends SQLiteOpenHelper(context, NAME, null, VERSION) {
		def onCreate(db: SQLiteDatabase) {
			Log.i(TAG, "Create database")

			val statements = closing(context.getAssets.open("db/create.sql")) { stream =>
				scala.io.Source.fromInputStream(stream).mkString.split("---x")
			}

			db.beginTransaction()
			try {
				for (sql <- statements)
					db.execSQL(sql)
				db.setTransactionSuccessful()
			} finally {
				db.endTransaction()
			}
		}

		def onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
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
}

class Database(db: SQLiteDatabase) {
	import Database._

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
		values.put(RoutesTable.VEHICLE_TYPE_COLUMN, vehicleType.id.asInstanceOf[java.lang.Integer])
		values.put(RoutesTable.EXTERNAL_ID_COLUMN, externalId)
		values.put(RoutesTable.NAME_COLUMN, name)
		values.put(RoutesTable.FIRST_STOP_NAME_COLUMN, firstStopName)
		values.put(RoutesTable.LAST_STOP_NAME_COLUMN, lastStopName)

		db.replaceOrThrow(RoutesTable.NAME, null, values)
	}

	def deleteRoute(vehicleType: VehicleType.Value, externalId: String) {
		db.delete(RoutesTable.NAME, "vehicleType=? AND externalId=?", Array(vehicleType.id.toString, externalId))
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

	def close() { db.close() }

	def transaction[T](f: => T) {
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
