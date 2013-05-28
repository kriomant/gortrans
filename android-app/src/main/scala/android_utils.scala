package net.kriomant.gortrans

import android.util.Log
import android.os.{Debug, SystemClock}

import scala.collection.mutable

object android_utils {

	def measure[T](tag: String, title: String)(f: => T): T = {
		try {
			Debug.startAllocCounting()
			val start = SystemClock.uptimeMillis()
			val result = f
			val end = SystemClock.uptimeMillis()
			val allocs = Debug.getThreadAllocCount
			Log.d(tag, "%s takes %d milliseconds and %d allocations" format (title, end - start, allocs))
			result
		} catch {
			case e: Throwable =>
				Log.d(tag, "%s failed" format title)
				throw e
		} finally {
			Debug.stopAllocCounting()
		}
	}

	class Observable[T] {
		def get(f: T => Unit) {
			value match {
				case Some(v) => f(v)
				case None => getRequests.enqueue(f)
			}
		}

		def subscribe(f: T => Unit) {
			subscribers.enqueue(f)
			value.map { v => f(v) }
		}

		protected def set(v: T) {
			value = Some(v)

			getRequests.foreach(_(v))
			getRequests.clear()

			subscribers.foreach(_(v))
		}

		private var value: Option[T] = None

		private val getRequests = mutable.Queue[T => Unit]()
		private val subscribers = mutable.Queue[T => Unit]()
	}
}
