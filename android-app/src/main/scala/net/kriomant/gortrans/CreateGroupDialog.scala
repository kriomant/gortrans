package net.kriomant.gortrans

import android.app.{AlertDialog, Dialog}
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.KeyEvent
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.EditorInfo
import android.widget.TextView.OnEditorActionListener
import android.widget.{EditText, TextView, Toast}

object CreateGroupDialog {

  trait Listener {
    def onCreateGroup(dialog: CreateGroupDialog, groupId: Long)
  }

  private val ARG_ROUTE_IDS = "route-ids"
}

class CreateGroupDialog extends DialogFragment {
  def this(routeIds: Set[Long]) {
    this()

    val args = new Bundle
    args.putLongArray(CreateGroupDialog.ARG_ROUTE_IDS, routeIds.toArray)
    setArguments(args)
  }

  override def onCreateDialog(savedInstanceState: Bundle): Dialog = {
    val layoutInflater = getActivity.getLayoutInflater
    val view = layoutInflater.inflate(R.layout.create_group_dialog, null)

    val nameEdit = view.findViewById(R.id.group_name_edit).asInstanceOf[EditText]

    nameEdit.setOnEditorActionListener(new OnEditorActionListener {
      def onEditorAction(v: TextView, actionId: Int, event: KeyEvent): Boolean = actionId match {
        case EditorInfo.IME_ACTION_DONE =>
          doCreate(nameEdit.getText.toString)
          true

        case _ => false
      }
    })

    val dialog = new AlertDialog.Builder(getActivity)

      .setTitle(R.string.create_group)

      .setView(view)

      .setPositiveButton(R.string.create, new DialogInterface.OnClickListener {
        def onClick(dialog: DialogInterface, which: Int) {
          doCreate(nameEdit.getText.toString)
        }
      })

      .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener {
        def onClick(dialog: DialogInterface, which: Int) {
          dialog.cancel()
        }
      })

      .create()

    dialog.setOnShowListener(new OnShowListener {
      def onShow(d: DialogInterface) {
        // Show soft keyboard automatically
        dialog.getWindow.setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE)
      }
    })

    dialog
  }

  def createGroup(groupName: String): Long = {
    val db = getActivity.getApplication.asInstanceOf[CustomApplication].database
    val routeIds = getArguments.getLongArray(CreateGroupDialog.ARG_ROUTE_IDS)

    val groupId = db.transaction {
      val groupId = db.createGroup(groupName)
      routeIds.foreach { routeId => db.addRouteToGroup(groupId, routeId) }
      groupId
    }

    Toast.makeText(getActivity, getString(R.string.group_created, groupName), Toast.LENGTH_SHORT).show()
    groupId
  }

  def doCreate(name: String) {
    val groupId = createGroup(name)
    val listener = getActivity.asInstanceOf[CreateGroupDialog.Listener]
    dismiss()
    listener.onCreateGroup(CreateGroupDialog.this, groupId)
  }
}
