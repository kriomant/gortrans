package net.kriomant.gortrans

import android.content.res.Resources
import android.graphics._
import android.graphics.drawable.Drawable

object VehicleMarker {

  case class ConstantState(
                            back: Drawable, front: Drawable, arrow: Drawable,
                            angle: Option[Float], color: Int
                          ) extends Drawable.ConstantState {
    val frontInsets: Rect = {
      val diff = back.getIntrinsicWidth - front.getIntrinsicWidth
      val offset = diff / 2
      new Rect(offset, offset, diff - offset, back.getIntrinsicHeight - front.getIntrinsicHeight - offset)
    }

    back.setColorFilter(new LightingColorFilter(Color.BLACK, color))
    arrow.setColorFilter(new LightingColorFilter(Color.BLACK, color))

    // VehicleMarker uses color filters to colorize some drawables and
    // color filter is part of constant state and is shared between
    // drawables, so they are needed to be mutated.
    def this(resources: Resources, angle: Option[Float], color: Int) = this(
      resources.getDrawable(R.drawable.vehicle_marker_back).mutate(),
      resources.getDrawable(R.drawable.vehicle_marker_front),
      resources.getDrawable(R.drawable.vehicle_marker_arrow).mutate(),
      angle, color
    )

    def newDrawable(): Drawable = new VehicleMarker(ConstantState(
      back.getConstantState.newDrawable(),
      front.getConstantState.newDrawable(),
      arrow.getConstantState.newDrawable(),
      angle, color
    ))

    override def newDrawable(res: Resources): Drawable = new VehicleMarker(ConstantState(
      back.getConstantState.newDrawable(res),
      front.getConstantState.newDrawable(res),
      arrow.getConstantState.newDrawable(res),
      angle, color
    ))

    def getChangingConfigurations: Int =
      back.getChangingConfigurations |
        front.getChangingConfigurations |
        arrow.getChangingConfigurations
  }

}

class VehicleMarker private(state: VehicleMarker.ConstantState) extends Drawable {
  var _alpha: Int = 255

  def this(resources: Resources, angle: Option[Float], color: Int) =
    this(new VehicleMarker.ConstantState(resources, angle, color))

  def draw(canvas: Canvas) {
    val bounds = getBounds
    canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, _alpha, Canvas.HAS_ALPHA_LAYER_SAVE_FLAG)
    state.back.draw(canvas)
    if (state.front.isVisible)
      state.front.draw(canvas)

    if (state.arrow.isVisible) {
      state.angle foreach { a =>
        canvas.save(Canvas.MATRIX_SAVE_FLAG)
        val arrowBounds = state.arrow.getBounds
        canvas.rotate(-a, (arrowBounds.left + arrowBounds.right) / 2.0f, (arrowBounds.top + arrowBounds.bottom) / 2.0f)
        state.arrow.draw(canvas)
        canvas.restore()
      }
    }

    canvas.restore()
  }

  def setAlpha(alpha: Int) {
    _alpha = alpha
  }

  /** Set color filter.
    *
    * This method should apply color filter to whole drawable, but it doesn't
    * do it exactly. This drawable is composite and uses color filter on inner drawables
    * to colorize them. Ideally I should compose inner and provided color filters,
    * but Android platform doesn't provide such capability.
    *
    * So this method is designed to work with ItemizedOverlay which uses color
    * filter to draw shadows.
    */
  def setColorFilter(cf: ColorFilter) {
    if (cf != null) {
      state.back.setColorFilter(cf)
      state.front.setVisible(false, false)
      state.arrow.setVisible(false, false)
    } else {
      state.back.setColorFilter(new LightingColorFilter(Color.BLACK, state.color))
      state.front.setVisible(true, false)
      state.arrow.setVisible(true, false)
    }
  }

  def getOpacity: Int = PixelFormat.TRANSLUCENT

  override def onBoundsChange(bounds: Rect) {
    super.onBoundsChange(bounds)
    state.back.setBounds(bounds)

    val frontRect = new Rect(
      bounds.left + state.frontInsets.left,
      bounds.top + state.frontInsets.top,
      bounds.right - state.frontInsets.right,
      bounds.bottom - state.frontInsets.bottom
    )
    state.front.setBounds(frontRect)
    state.arrow.setBounds(frontRect)
  }

  override def getIntrinsicWidth: Int = state.back.getIntrinsicWidth

  override def getIntrinsicHeight: Int = state.back.getIntrinsicHeight

  override def getConstantState: Drawable.ConstantState = state

  override def mutate(): Drawable = throw new Exception("not mutable")
}
