package net.kriomant.gortrans

import android.util.Log
import android.os.{Debug, SystemClock}

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
			case e =>
				Log.d(tag, "%s failed" format title)
				throw e
		} finally {
			Debug.stopAllocCounting()
		}
	}
}
