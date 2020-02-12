package net.kriomant.gortrans.compatibility

import android.content.Context
import android.content.res.Resources

object plurals {

  /** Dirty hack to make pluralization work for russian in API<10. */
  def getQuantityString(context: Context, resourceId: Int, quantity: Int, formatArgs: Any*): String = {
    val raw = getQuantityText(context, resourceId, quantity).toString
    raw.format(formatArgs: _*)
  }

  def getQuantityString(context: Context, resourceId: Int, quantity: Int): String = {
    getQuantityText(context, resourceId, quantity).toString
  }

  def getQuantityText(context: Context, resourceId: Int, quantity: Int): CharSequence = {
    val quantityCode = getQuantityCode(quantity)

    val assets = context.getAssets
    val method = assets.getClass.getDeclaredMethod("getResourceBagText", classOf[Int], classOf[Int])
    method.setAccessible(true)
    val res = method.invoke(assets,
      resourceId.asInstanceOf[AnyRef],
      attrForQuantityCode(quantityCode).asInstanceOf[AnyRef]
    ).asInstanceOf[CharSequence]

    if (res == null)
      throw new Resources.NotFoundException(
        "Plural resource ID #0x%x quantity=%d item=%s"
          format(resourceId, quantity, stringForQuantityCode(quantityCode))
      )
    res
  }

  object QuantityCode extends Enumeration {
    val ONE, TWO, FEW = Value
  }

  def getQuantityCode(quantity: Int): QuantityCode.Value = {
    val lastDigit = quantity % 10
    val lastTwoDigits = quantity % 100

    if (lastDigit == 0 || (lastTwoDigits >= 11 && lastTwoDigits < 20)) {
      QuantityCode.FEW
    } else if (lastDigit == 1) {
      QuantityCode.ONE
    } else if (lastDigit <= 4) {
      QuantityCode.TWO
    } else {
      QuantityCode.FEW
    }
  }

  /** Borrowed from Resources.attrForQuantityCode */
  private def attrForQuantityCode(quantityCode: QuantityCode.Value): Int = {
    quantityCode match {
      //case NativePluralRules.ZERO: return 0x01000005;
      case QuantityCode.ONE => 0x01000006
      case QuantityCode.TWO => 0x01000007
      case QuantityCode.FEW => 0x01000008
      //case NativePluralRules.MANY: return 0x01000009;
      case _ => 0x01000004
    }
  }

  private def stringForQuantityCode(quantityCode: QuantityCode.Value): String = {
    quantityCode match {
      //case NativePluralRules.ZERO: return "zero";
      case QuantityCode.ONE => "one"
      case QuantityCode.TWO => "two"
      case QuantityCode.FEW => "few"
      //case NativePluralRules.MANY: return "many";
      case _ => "other"
    }
  }
}
