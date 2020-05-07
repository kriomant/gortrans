package net.kriomant.gortrans

import android.widget.ListView

object Compatibility {
  def getCheckedItemCount(listView: ListView): Int = {
    if (android.os.Build.VERSION.SDK_INT >= 11) {
      listView.getCheckedItemCount
    } else {
      val positions = listView.getCheckedItemPositions
      (0 until positions.size).count(positions.valueAt)
    }
  }
}
