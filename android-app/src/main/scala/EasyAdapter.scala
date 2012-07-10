package net.kriomant.gortrans

import android.content.Context
import android.view.{LayoutInflater, ViewGroup, View}
import android.widget.ListAdapter

trait EasyAdapter extends ListAdapter {
	type SubViews
	val context: Context
	val itemLayout: Int

	def findSubViews(view: View): SubViews
	def adjustItem(position: Int, views: SubViews)


	override def getItem(position: Int): AnyRef = null

	override def getItemId(position: Int): Long = 0L

	override def hasStableIds: Boolean = false

	override def getView(position: Int, convertView: View, parent: ViewGroup): View = {
		val (view, subViews) = if (convertView == null) {
			val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater]
			val view = inflater.inflate(itemLayout, parent, false)
			val subViews = findSubViews(view)
			view.setTag(subViews)
			(view, subViews)
		} else {
			(convertView, convertView.getTag.asInstanceOf[SubViews])
		}

		adjustItem(position, subViews)

		view
	}

	override def getItemViewType(p1: Int): Int = 0

	override def getViewTypeCount: Int = 1

	override def areAllItemsEnabled(): Boolean = true

	override def isEnabled(position: Int): Boolean = true
}
