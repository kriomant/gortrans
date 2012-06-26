package net.kriomant.gortrans

import android.widget.Adapter
import android.database.DataSetObserver

trait SeqAdapter extends Adapter {
	val items: Seq[_]
	
	def registerDataSetObserver(p1: DataSetObserver) {}

	def unregisterDataSetObserver(p1: DataSetObserver) {}

	def getCount: Int = items.length

	def isEmpty: Boolean = items.isEmpty
}
