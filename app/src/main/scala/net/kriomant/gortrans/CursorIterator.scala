package net.kriomant.gortrans

import android.database.Cursor

class CursorIterator[C <: Cursor](cursor: C) extends Iterator[C] {
  // If cursor is empty then `isLast` returns `false`, so check size too.
  def hasNext: Boolean = cursor.getCount > 0 && !cursor.isLast

  def next(): C = {
    cursor.moveToNext()
    cursor
  }
}

object CursorIterator {
  implicit def cursorUtils[C <: Cursor](cursor: C): CursorIterator[C] = new CursorIterator(cursor)
}