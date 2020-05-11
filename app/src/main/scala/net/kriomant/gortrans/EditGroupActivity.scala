package net.kriomant.gortrans

import android.app.Activity
import android.content.{Context, Intent}
import android.os.Bundle
import android.support.v7.app.ActionBarActivity
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.View
import android.view.View.OnClickListener
import android.widget.{EditText, TextView}
import net.kriomant.gortrans.android_utils.SpannableStringBuilderUtils

object EditGroupActivity {
  private final val EXTRA_GROUP_ID = "group-id"

  private final val REQUEST_EDIT_ROUTES = 1

  def createIntent(context: Context, groupId: Long): Intent = {
    val intent = new Intent(context, classOf[EditGroupActivity])
    intent.putExtra(EXTRA_GROUP_ID, groupId)
    intent
  }
}

class EditGroupActivity extends ActionBarActivity with BaseActivity {
  self =>

  import EditGroupActivity._

  var groupId: Long = -1
  var routeIds: Set[Long] = _

  var groupNameEdit: EditText = _
  var routesText: TextView = _

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    setContentView(R.layout.edit_group_activity)

    groupNameEdit = findViewById(R.id.group_name_edit).asInstanceOf[EditText]
    routesText = findViewById(R.id.routes_list).asInstanceOf[TextView]
    val editRoutesButton = findViewById(R.id.edit_routes)

    editRoutesButton.setOnClickListener(new OnClickListener {
      def onClick(v: View) {
        startActivityForResult(RouteChooseActivity.createIntent(self, self.routeIds), REQUEST_EDIT_ROUTES)
      }
    })

    groupId = getIntent.getLongExtra(EXTRA_GROUP_ID, -1)

    val db = getApplication.asInstanceOf[CustomApplication].database
    val (groupName, routeIds) = db.transaction {
      (
        db.getGroupName(groupId),
        db.getGroupItems(groupId)
      )
    }
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
