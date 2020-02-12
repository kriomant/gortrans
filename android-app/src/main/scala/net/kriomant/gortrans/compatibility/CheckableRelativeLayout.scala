package net.kriomant.gortrans.compatibility

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import net.kriomant.gortrans.CheckableToActivatedStateConversion

class CheckableRelativeLayout(context: Context, attrs: AttributeSet, defStyle: Int)
  extends RelativeLayout(context: Context, attrs, defStyle)
    with CheckableToActivatedStateConversion {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)

  def this(context: Context) = this(context, null, 0)
}

