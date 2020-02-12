package net.kriomant.gortrans

import android.app.SearchManager
import android.content.{ContentProvider, ContentValues}
import android.database.{Cursor, CursorWrapper}
import android.net.Uri
import android.provider.BaseColumns
import net.kriomant.gortrans.core.VehicleType

object RouteSearchSuggestionProvider {

  class SuggestionsCursor(cursor: Database.RoutesTable.Cursor, packageName: String) extends CursorWrapper(cursor) {
    val COLUMNS: Map[String, () => AnyRef] = Map[String, () => AnyRef](
      BaseColumns._ID -> { () => cursor.id.asInstanceOf[AnyRef] },
      SearchManager.SUGGEST_COLUMN_TEXT_1 -> { () => cursor.name },
      SearchManager.SUGGEST_COLUMN_ICON_1 -> { () =>
        val resource = cursor.vehicleType match {
          case VehicleType.Bus => R.drawable.tab_bus
          case VehicleType.TrolleyBus => R.drawable.tab_trolleybus
          case VehicleType.TramWay => R.drawable.tab_tram
          case VehicleType.MiniBus => R.drawable.tab_minibus
        }
        "android.resource://%s/%d" format(packageName, resource)
      },
      SearchManager.SUGGEST_COLUMN_INTENT_DATA -> { () => cursor.id.toString }
    )

    val columns: Seq[() => AnyRef] = COLUMNS.values.toSeq
    val indices: Map[String, Int] = COLUMNS.keys.zipWithIndex.toMap

    override def getColumnCount: Int = COLUMNS.size

    override def getColumnIndex(columnName: String): Int = indices.getOrElse(columnName, -1)

    override def getColumnNames: Array[String] = COLUMNS.keys.toArray

    override def getDouble(columnIndex: Int): Double = columns(columnIndex).apply().asInstanceOf[Double]

    override def getFloat(columnIndex: Int): Float = columns(columnIndex).apply().asInstanceOf[Float]

    override def getInt(columnIndex: Int): Int = columns(columnIndex).apply().asInstanceOf[Int]

    override def getLong(columnIndex: Int): Long = columns(columnIndex).apply().asInstanceOf[Long]

    override def getShort(columnIndex: Int): Short = columns(columnIndex).apply().asInstanceOf[Short]

    override def getString(columnIndex: Int): String = columns(columnIndex).apply().asInstanceOf[String]

    override def getBlob(columnIndex: Int): Array[Byte] = columns(columnIndex).apply().asInstanceOf[Array[Byte]]
  }

}

class RouteSearchSuggestionProvider extends ContentProvider {

  import RouteSearchSuggestionProvider._

  var db: Database = _

  def onCreate() = true

  def query(uri: Uri, projection: Array[String], selection: String, selectionArgs: Array[String], sortOrder: String): Cursor = {
    val q = uri.getLastPathSegment

    if (db == null)
      db = Database.getWritable(getContext)

    val packageName = getContext.getPackageManager.getPackageInfo(getContext.getPackageName, 0).packageName
    new SuggestionsCursor(db.searchRoutes(q), packageName)
  }

  def getType(uri: Uri): String = null

  def insert(uri: Uri, values: ContentValues): Uri = null

  def delete(uri: Uri, selection: String, selectionArgs: Array[String]) = 0

  def update(uri: Uri, values: ContentValues, selection: String, selectionArgs: Array[String]) = 0

  override def shutdown() {
    if (db != null)
      db.close()
  }
}
