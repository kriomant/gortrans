package net.kriomant.gortrans

import android.app.Activity
import android.content.{Context, Intent}
import android.view.View
import android.widget.AdapterView.OnItemClickListener
import android.widget._

object Sidebar {

  trait SidebarListener {
    def onItemSelected(intent: Intent)

    def onSettingsSelected()
  }

  case class Entry(nameRes: Int, intent: Intent)

  class Adapter(val context: Context, val items: Seq[Entry]) extends SeqAdapter with EasyAdapter {
    type SubViews = TextView
    val itemLayout: Int = R.layout.sidebar_item

    def findSubViews(view: View): TextView = view.asInstanceOf[TextView]

    def adjustItem(position: Int, view: TextView) {
      val item = items(position)
      view.setText(context.getString(item.nameRes))
    }
  }

}

class Sidebar(activity: Activity, navigationDrawer: View, listener: Sidebar.SidebarListener) {
  val list: ListView = navigationDrawer.findViewById(R.id.sidebar_content).asInstanceOf[ListView]

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
  list.setAdapter(new Sidebar.Adapter(activity, items))
  list.setOnItemClickListener(new OnItemClickListener {
    def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long) {
      listener.onItemSelected(items(position).intent)
    }
  })

  val settingsButton: ImageButton = navigationDrawer.findViewById(R.id.settings).asInstanceOf[ImageButton]
  settingsButton.setOnClickListener(new View.OnClickListener {
    def onClick(view: View) {
      listener.onSettingsSelected()
    }
  })
}
