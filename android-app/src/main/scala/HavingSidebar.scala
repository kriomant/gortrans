package net.kriomant.gortrans

import android.os.Bundle
import android.view.ViewGroup
import com.actionbarsherlock.app.SherlockFragmentActivity
import com.actionbarsherlock.view.MenuItem
import android.util.TypedValue
import net.kriomant.gortrans.Sidebar.SidebarListener
import android.content.Intent
import android.util.Log

trait HavingSidebar extends SherlockFragmentActivity {
	var decorView: ViewGroup = null
	var sidebarContainer: SidebarContainer = null

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		val actionBar = getSupportActionBar
		actionBar.setDisplayHomeAsUpEnabled(true)

		decorView = getWindow.getDecorView.asInstanceOf[ViewGroup]

		// Detach content view.
		assert(decorView.getChildCount == 1)
		val contentView = decorView.getChildAt(0)
		decorView.removeView(contentView)

		// Make content view non-transparent so that menu is not visible
		// under content.
		val typedValue = new TypedValue
		getTheme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
		contentView.setBackgroundResource(typedValue.resourceId)

		val sidebar = new Sidebar(this, decorView, new SidebarListener {
			def onItemSelected(intent: Intent) {
				sidebarContainer.animateClose(() => {
					if (! intent.filterEquals(getIntent)) {
						startActivity(intent)
						finish()
						overridePendingTransition(0, 0)
					}
				})
			}
		})

		// Add sidebar container.
		sidebarContainer = new SidebarContainer(this, sidebar.getView, contentView)

		decorView.addView(sidebarContainer)
	}

	override def onOptionsItemSelected(item: MenuItem) = item.getItemId match {
		case android.R.id.home => sidebarContainer.animateToggle(); true
		case _ => super.onOptionsItemSelected(item)
	}

	override def onBackPressed() {
		if (sidebarContainer.opened)
			sidebarContainer.animateClose()
		else
			super.onBackPressed()
	}
}
