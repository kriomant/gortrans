package net.kriomant.gortrans

import android.widget._
import android.content.{Intent, Context}
import android.view.{ViewGroup, LayoutInflater, View}
import android.app.Activity
import android.widget.AdapterView.OnItemClickListener

object Sidebar {
	trait SidebarListener {
		def onItemSelected(intent: Intent)
	}

	case class Entry(nameRes: Int, intent: Intent)

	class Adapter(val context: Context, val items: Seq[Entry]) extends SeqAdapter with EasyAdapter {
		type SubViews = TextView
		val itemLayout = R.layout.sidebar_item

		def findSubViews(view: View) = view.asInstanceOf[TextView]

		def adjustItem(position: Int, view: TextView) {
			val item = items(position)
			view.setText(context.getString(item.nameRes))
		}
	}
}
class Sidebar(activity: Activity, parentView: ViewGroup, listener: Sidebar.SidebarListener) {
	def getView = sidebarView

	val sidebarView = {
		val inflater = activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater]
		inflater.inflate(R.layout.sidebar, parentView, false)
	}

	val sidebarList = sidebarView.findViewById(R.id.sidebar_content).asInstanceOf[ListView]

	val items = Seq(
		Sidebar.Entry(R.string.groups, {
			val intent = GroupsActivity.createIntent(activity)
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION)
			intent
		}),
		Sidebar.Entry(R.string.routes, {
			val intent = MainActivity.createIntent(activity)
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION)
			intent
		}),
		Sidebar.Entry(R.string.news, {
			val intent = NewsActivity.createIntent(activity)
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION)
			intent
		})
	)
	sidebarList.setAdapter(new Sidebar.Adapter(activity, items))
	sidebarList.setOnItemClickListener(new OnItemClickListener {
		def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long) {
			listener.onItemSelected(items(position).intent)
		}
	})

	val settingsButton = sidebarView.findViewById(R.id.settings).asInstanceOf[ImageButton]
	settingsButton.setOnClickListener(new View.OnClickListener {
		def onClick(view: View) {
			activity.startActivity(SettingsActivity.createIntent(activity))
		}
	})
}
