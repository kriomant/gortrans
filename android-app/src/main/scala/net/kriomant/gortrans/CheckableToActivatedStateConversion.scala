package net.kriomant.gortrans

import android.view.View
import android.widget.Checkable

/**
 * Since Honeycomb `ListView` supports 'activated' state for checked items
 * in single- or multi-choice mode.
 * `ListView` in earlier versions doesn't support 'activated' state.
 * However if list item view implements `Checkable` interface, then
 * `ListView` will use it to set item checked state. It works on all
 * (supported, i.e. >=8) platform versions.
 * On Honeycomb `Checkable` is used *instead* of setting 'activated' state.
 *
 * This trait implements `Checkable` interface and converts checked state
 * into 'activated' drawable state, so same drawable selector may be used
 * for all platform versions.
 */
trait CheckableToActivatedStateConversion extends View with Checkable {
  private[this] var checked: Boolean = false

  def isChecked: Boolean = checked

  def setChecked(value: Boolean) {
    if (checked != value) {
      checked = value
      refreshDrawableState()
    }
  }

  def toggle() {
    checked = !checked
    refreshDrawableState()
  }

  override def onCreateDrawableState(extraSpace: Int): Array[Int] = {
    if (checked)
      ViewStaticBridge.mergeDrawableStates(
        super.onCreateDrawableState(extraSpace + 1),
        Array(android.R.attr.state_activated)
      )
    else
      super.onCreateDrawableState(extraSpace)
  }
}