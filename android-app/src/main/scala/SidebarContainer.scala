package net.kriomant.gortrans

import android.view.View
import android.util.AttributeSet
import android.content.Context
import android.widget.FrameLayout
import android.view.animation.{Animation, TranslateAnimation, DecelerateInterpolator}
import android.animation.{Animator, AnimatorListenerAdapter}
import android.view.animation.Animation.AnimationListener
import android.graphics.Rect
import android.view.View.MeasureSpec

class SidebarContainer(context: Context, attrs: AttributeSet, defStyle: Int) extends FrameLayout(context, attrs, defStyle) {
	def this(context: Context) = this(context, null, 0)
	def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)

	def this(context: Context, sidebarView: View, contentView: View) {
		this(context)
		this.sidebarView = sidebarView
		this.contentView = contentView

		addView(sidebarView)
		addView(contentView)
	}

	def toggle() {
		if (opened)
			close()
		else
			open()
	}

	def open() {
		if (!opened) {
			if (contentView.getAnimation != null) {
				contentView.getAnimation.cancel()
			}

			opened = true
			requestLayout()
		}
	}

	def close() {
		if (opened) {
			if (contentView.getAnimation != null) {
				contentView.getAnimation.cancel()
			}

			opened = false
			requestLayout()
		}
	}

	def animateOpen() {
		if (!opened && contentView.getAnimation == null) {
			if (openAnimation == null) {
				val endPosition = getPaddingLeft + sidebarView.getMeasuredWidth

				openAnimation = new TranslateAnimation(0, sidebarView.getMeasuredWidth, 0, 0)
				openAnimation.setDuration(500)
				openAnimation.setAnimationListener(new AnimationListener {
					def onAnimationEnd(animation: Animation) {
						contentView.clearAnimation() // To avoid flickering.
						contentView.offsetLeftAndRight(endPosition - contentView.getLeft)
						opened = true
						invalidate()
					}

					def onAnimationStart(animation: Animation) {}
					def onAnimationRepeat(animation: Animation) {}
				})
			}

			contentView.startAnimation(openAnimation)
		}
	}

	def animateClose(onClosed: () => Unit = null) {
		if (opened && contentView.getAnimation == null) {
			if (closeAnimation == null) {
				val endPosition = getPaddingLeft

				closeAnimation = new TranslateAnimation(0, -sidebarView.getMeasuredWidth, 0, 0)
				closeAnimation.setDuration(500)
				closeAnimation.setAnimationListener(new AnimationListener {
					def onAnimationEnd(animation: Animation) {
						contentView.clearAnimation() // To avoid flickering.
						contentView.offsetLeftAndRight(endPosition - contentView.getLeft)
						opened = false
						invalidate()

						if (onClosed != null) onClosed()
					}

					def onAnimationStart(animation: Animation) {}
					def onAnimationRepeat(animation: Animation) {}
				})
			}

			contentView.startAnimation(closeAnimation)
		}
	}

	def animateToggle() {
		if (opened)
			animateClose()
		else
			animateOpen()
	}

	override def onFinishInflate() {
		super.onFinishInflate()

		if (getChildCount != 2)
			throw new IllegalArgumentException("SidebarContainer must have two children")

		sidebarView = getChildAt(0)
		contentView = getChildAt(1)
	}

	override def onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		assert(MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY)
		assert(MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)

		measureChildWithMargins(
			sidebarView,
			MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.AT_MOST),
			getPaddingLeft+getPaddingRight,
			heightMeasureSpec,
			getPaddingTop+getPaddingBottom
		)
		measureChildWithMargins(
			contentView,
			widthMeasureSpec, getPaddingLeft+getPaddingRight,
			heightMeasureSpec, getPaddingTop+getPaddingBottom
		)

		setMeasuredDimension(widthMeasureSpec, heightMeasureSpec)
	}

	override def onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		val sidebarWidth = sidebarView.getMeasuredWidth
		sidebarView.layout(
			left + getPaddingLeft,
			top + getPaddingTop,
			math.min(left + getPaddingLeft + sidebarWidth, right - getPaddingRight),
			bottom - getPaddingBottom
		)

		val contentOffset = if (opened) sidebarWidth else 0
		contentView.layout(
			left + getPaddingLeft + contentOffset,
			top + getPaddingTop,
			right - getPaddingRight + contentOffset,
			bottom - getPaddingBottom
		)
	}


	override def onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)

		if (w != oldw) {
			openAnimation = null
			closeAnimation = null
		}
	}

	var sidebarView: View = null
	var contentView: View = null

	// State of 'opened' is changed after animation is finished.
	var opened: Boolean = false
	var openAnimation: Animation = null
	var closeAnimation: Animation = null

	override def fitSystemWindows(insets: Rect) = {
		setPadding(insets.left, insets.top, insets.right, insets.bottom)
		true
	}
}
