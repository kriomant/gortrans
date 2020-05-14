package net.kriomant.gortrans.compatibility

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import net.kriomant.gortrans.CheckableToActivatedStateConversion

// `LinearLayout.<init>(Context, AttributeSet, Int)` constructor is available since
// API level 11 only, don't call it.
class CheckableLinearLayout(context: Context, attrs: AttributeSet)
  extends LinearLayout(context: Context, attrs)
    with CheckableToActivatedStateConversion {
  def this(context: Context) = this(context, null)
}

