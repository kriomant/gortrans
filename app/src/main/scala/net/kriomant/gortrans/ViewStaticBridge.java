package net.kriomant.gortrans;

import android.content.Context;
import android.view.View;

/**
 * Utility class to get access to protected static members of `View` from Scala.
 */
abstract public class ViewStaticBridge extends View {
    public ViewStaticBridge(Context context) {
        super(context);
    }

    public static int[] mergeDrawableStates(int[] baseState, int[] additionalState) {
        return View.mergeDrawableStates(baseState, additionalState);
    }
}
