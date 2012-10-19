package net.kriomant.gortrans

import android.support.v4.app.DialogFragment
import android.widget.{Toast, TextView, EditText}
import android.view.{View, KeyEvent, ViewGroup, LayoutInflater}
import android.os.Bundle
import android.widget.TextView.OnEditorActionListener
import android.view.inputmethod.EditorInfo
import android.view.WindowManager.LayoutParams
import android.view.View.OnClickListener

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

	private[this] var nameEdit: EditText = _

	override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) = {
		val view = inflater.inflate(R.layout.create_group_dialog, container)
		nameEdit = view.findViewById(R.id.group_name).asInstanceOf[EditText]

		// Show soft keyboard automatically
		getDialog().getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE)

		nameEdit.setOnEditorActionListener(new OnEditorActionListener {
			def onEditorAction(v: TextView, actionId: Int, event: KeyEvent) = actionId match {
				case EditorInfo.IME_ACTION_DONE =>
					doCreate()
					true

				case _ => false
			}
		})

		val confirmButton = view.findViewById(R.id.confirm)
		confirmButton.setOnClickListener(new OnClickListener {
			def onClick(v: View) { doCreate() }
		})

		val cancelButton = view.findViewById(R.id.cancel)
		cancelButton.setOnClickListener(new OnClickListener {
			def onClick(v: View) { dismiss() }
		})

		view
	}

	override def onCreateDialog(savedInstanceState: Bundle) = {
		val dialog = super.onCreateDialog(savedInstanceState)
		dialog.setTitle(R.string.create_group)
		dialog
	}

	def createGroup() = {
		val db = getActivity.getApplication.asInstanceOf[CustomApplication].database
		val groupName = nameEdit.getText.toString
		val routeIds = getArguments.getLongArray(CreateGroupDialog.ARG_ROUTE_IDS)

		val groupId = db.transaction {
			val groupId = db.createGroup(groupName)
			routeIds.foreach { routeId => db.addRouteToGroup(groupId, routeId) }
			groupId
		}

		Toast.makeText(getActivity, getString(R.string.group_created, groupName), Toast.LENGTH_SHORT).show()
		groupId
	}

	def doCreate() {
		val groupId = createGroup()
		val listener = getActivity.asInstanceOf[CreateGroupDialog.Listener]
		dismiss()
		listener.onCreateGroup(CreateGroupDialog.this, groupId)
	}
}
