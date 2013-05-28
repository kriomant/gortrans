package net.kriomant.gortrans

import android.content.{Context, Intent}
import com.actionbarsherlock.app.SherlockActivity
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan

import SpannableStringBuilderUtils.conversion
import android.widget.{TextView, EditText, Button}
import android.view.View.OnClickListener
import android.view.View
import android.app.Activity

object EditGroupActivity {
	private final val EXTRA_GROUP_ID = "group-id"

	private final val REQUEST_EDIT_ROUTES = 1

	def createIntent(context: Context, groupId: Long): Intent = {
		val intent = new Intent(context, classOf[EditGroupActivity])
		intent.putExtra(EXTRA_GROUP_ID, groupId)
		intent
	}
}

class EditGroupActivity extends SherlockActivity with TypedActivity { self =>
	import EditGroupActivity._

	var groupId: Long = -1
	var routeIds: Set[Long] = null

	var groupNameEdit: EditText = null
	var routesText: TextView = null

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.edit_group_activity)

		groupNameEdit = findView(TR.group_name)
		routesText = findView(TR.routes_list)
		val editRoutesButton = findView(TR.edit_routes)

		editRoutesButton.setOnClickListener(new OnClickListener {
			def onClick(v: View) {
				startActivityForResult(RouteChooseActivity.createIntent(self, self.routeIds), REQUEST_EDIT_ROUTES)
			}
		})

		groupId = getIntent.getLongExtra(EXTRA_GROUP_ID, -1)

		val db = getApplication.asInstanceOf[CustomApplication].database
		val (groupName, routeIds) = db.transaction {(
			db.getGroupName(groupId),
			db.getGroupItems(groupId)
		)}
		this.routeIds = routeIds

		groupNameEdit.setText(groupName)
	}

	override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
		requestCode match {
			case REQUEST_EDIT_ROUTES => if (resultCode == Activity.RESULT_OK) {
				routeIds = RouteChooseActivity.intentToResult(data)
			}

			case _ => super.onActivityResult(requestCode, resultCode, data)
		}
	}

	override def onResume() {
		super.onResume()

		val db = getApplication.asInstanceOf[CustomApplication].database
		val routes = routeIds map { rid =>
			utils.closing(db.fetchRoute(rid)) { c =>
				core.Route(c.vehicleType, c.externalId, c.name, c.firstStopName, c.lastStopName)
			}
		}

		val builder = new SpannableStringBuilder
		routes.foreach { r =>
			builder.appendWithSpan(" ", new ImageSpan(this, RouteGroupsAdapter.vehicleTypeDrawables(r.vehicleType)))
			builder.append(r.name)
			builder.append(" ")
		}
		routesText.setText(builder)
	}

	protected override def onPause() {
		val db = getApplication.asInstanceOf[CustomApplication].database
		db.updateGroup(groupId, groupNameEdit.getText.toString, routeIds)

		super.onPause()
	}
}
