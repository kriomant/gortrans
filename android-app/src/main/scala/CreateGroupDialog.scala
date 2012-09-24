package net.kriomant.gortrans

import android.support.v4.app.DialogFragment
import android.widget.{TextView, EditText}
import android.view.{KeyEvent, ViewGroup, LayoutInflater}
import android.os.Bundle
import android.widget.TextView.OnEditorActionListener
import android.view.inputmethod.EditorInfo
import android.view.WindowManager.LayoutParams

object CreateGroupDialog {
	trait Listener {
		def onCreateGroup(name: String)
	}
}

class CreateGroupDialog extends DialogFragment {
	private[this] var nameEdit: EditText = _

	override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) = {
		val view = inflater.inflate(R.layout.create_group_dialog, container)
		nameEdit = view.findViewById(R.id.group_name).asInstanceOf[EditText]

		// Show soft keyboard automatically
		getDialog().getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE)

		nameEdit.setOnEditorActionListener(new OnEditorActionListener {
			def onEditorAction(v: TextView, actionId: Int, event: KeyEvent) = actionId match {
				case EditorInfo.IME_ACTION_DONE =>
					val listener = getActivity.asInstanceOf[CreateGroupDialog.Listener]
					listener.onCreateGroup(nameEdit.getText.toString)
					dismiss()
					true

				case _ => false
			}
		})

		view
	}

	override def onCreateDialog(savedInstanceState: Bundle) = {
		val dialog = super.onCreateDialog(savedInstanceState)
		dialog.setTitle(R.string.create_group)
		dialog
	}
}
