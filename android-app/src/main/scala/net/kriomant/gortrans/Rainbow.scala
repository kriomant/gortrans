package net.kriomant.gortrans

import android.graphics.Color

object Rainbow {
  def apply(startingHue: Float, saturation: Float, value: Float, alpha: Int): Rainbow
  = new Rainbow(startingHue, saturation, value, alpha)

  def apply(baseColor: Int): Rainbow = {
    val hsv = Array.ofDim[Float](3)
    Color.RGBToHSV(Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor), hsv)
    apply(hsv(0), hsv(1), hsv(2), Color.alpha(baseColor))
  }

  private val coeff = 360.0f / (Int.MaxValue.toFloat - Int.MinValue.toFloat)
}

class Rainbow(startingHue: Float, saturation: Float, value: Float, alpha: Int) {

  import Rainbow._

  def apply(i: Int): Int = {
    require(i >= 0)

    // Use trick from http://stackoverflow.com/a/9579252/716390 to get
    // colors with gradually decreasing contrast.
    // It's easier to demonstrate trick on one-byte values. Take color indices
    // with range (0-255) and reverse their binary representation:
    // 0 = 00000000 -> 00000000 = 0
    // 1 = 00000001 -> 10000000 = 128 (half of range distance from nearest value)
    // 2 = 00000010 -> 01000000 = 64 (quoter of range distance)
    // 3 = 00000011 -> 11000000 = 192 (quoter of range distance)
    // and so on. Values fill target range quite evenly.
    // The same principle can be applied to `Int` values, but range will
    // be from Int.MinValue to Int.MaxValue and we need to fit it into [0, 360)
    // hue range.
    val hue = (startingHue + (java.lang.Integer.reverse(i).toLong & 0xFFFFFFFFL) * coeff) % 360
    Color.HSVToColor(alpha, Array(hue, saturation, value))
  }
}