package net.kriomant.gortrans

import java.util.Date

import android.os.Handler

class PeriodicTimer(period: Long)(callback: => Unit) {
  private val handler = new Handler
  private val runnable = new Runnable {
    def run() {
      _lastCalled = Some(new Date)
      callback
      handler.postDelayed(this, period)
    }
  }

  private var _started = false
  private var _lastCalled: Option[Date] = None

  def started: Boolean = _started

  def lastCalled: Option[Date] = _lastCalled

  def start() {
    if (!_started) {
      handler.postDelayed(runnable, period)
      _started = true
    }
  }

  def stop() {
    if (_started) {
      handler.removeCallbacks(runnable)
      _started = false
    }
  }
}
