package net.kriomant.gortrans

import android.database.Cursor

class CursorIterator[C <: Cursor](cursor: C) extends Iterator[C] {
	def hasNext: Boolean = !cursor.isLast

	def next(): C = {
		cursor.moveToNext()
		cursor
	}
}

object CursorIterator {
	implicit def cursorUtils[C <: Cursor](cursor: C) = new CursorIterator(cursor)
}