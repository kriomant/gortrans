package net.kriomant.gortrans

import android.content.Intent
import android.widget.Toast
import com.actionbarsherlock.view.{MenuItem, Menu}
import com.actionbarsherlock.app.{SherlockMapActivity, SherlockActivity}

trait ShortcutTarget extends SherlockActivity {
	def getShortcutNameAndIcon: (String, Int)

	override def onCreateOptionsMenu(menu: Menu): Boolean = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.shortcut_menu, menu)
		true
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = {
		item.getItemId match {
			case R.id.create_shortcut => createShortcut(); true
			case _ => super.onOptionsItemSelected(item)
		}
	}

	def createShortcut() {
		val selfIntent = new Intent(getIntent)
		val (shortcutName, shortcutIcon) = getShortcutNameAndIcon

		val createShortcutIntent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT")
		createShortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, selfIntent)
		createShortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcutName)
		createShortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
			Intent.ShortcutIconResource.fromContext(this, shortcutIcon)
		)
		sendBroadcast(createShortcutIntent)

		Toast.makeText(this, getString(R.string.shortcut_is_created, shortcutName), Toast.LENGTH_SHORT)
	}
}

trait MapShortcutTarget extends SherlockMapActivity {
	def getShortcutNameAndIcon: (String, Int)

	override def onCreateOptionsMenu(menu: Menu): Boolean = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.shortcut_menu, menu)
		true
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = {
		item.getItemId match {
			case R.id.create_shortcut => createShortcut(); true
			case _ => super.onOptionsItemSelected(item)
		}
	}

	def createShortcut() {
		val selfIntent = new Intent(getIntent)
		val (shortcutName, shortcutIcon) = getShortcutNameAndIcon

		val createShortcutIntent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT")
		createShortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, selfIntent)
		createShortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcutName)
		createShortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
			Intent.ShortcutIconResource.fromContext(this, shortcutIcon)
		)
		sendBroadcast(createShortcutIntent)

		Toast.makeText(this, getString(R.string.shortcut_is_created, shortcutName), Toast.LENGTH_SHORT)
	}
}

