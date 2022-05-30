package net.kriomant.gortrans.tests

import _root_.android.test.{ActivityInstrumentationTestCase2, AndroidTestCase}
import android.widget.TextView
import junit.framework.Assert._
import net.kriomant.gortrans._

class AndroidTests extends AndroidTestCase {
  def testPackageIsCorrect() {
    assertEquals("net.kriomant.gortrans", getContext.getPackageName)
  }
}

class ActivityTests extends ActivityInstrumentationTestCase2(classOf[MainActivity]) {
   def testHelloWorldIsShown() {
      val activity = getActivity
      val textview = activity.findViewById(R.id.textview).asInstanceOf[TextView]
      assertEquals(textview.getText, "hello, world!")
    }
}
