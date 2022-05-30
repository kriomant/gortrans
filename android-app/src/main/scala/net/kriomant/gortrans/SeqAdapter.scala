package net.kriomant.gortrans

import android.widget.BaseAdapter

abstract class SeqAdapter extends BaseAdapter {
	def items: Seq[_]

	def getItem(position: Int) = items(position).asInstanceOf[AnyRef]
	def getCount: Int = items.length
	override def isEmpty: Boolean = items.isEmpty
}
