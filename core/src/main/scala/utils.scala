package net.kriomant.gortrans

import java.io.Reader
import net.kriomant.gortrans.utils.BooleanUtils
import java.security.Key

object utils {

	implicit class ReaderUtils(val reader: Reader) extends AnyVal {
		def readAll(bufferSize: Int = 1024): String = {
			val builder = new StringBuilder
			val buffer = new Array[Char](bufferSize)
			var count = 1
			do {
				count = reader.read(buffer)
				if (count > 0) {
					builder.appendAll(buffer, 0, count)
				}
			} while (count > 0)
			builder.toString
		}
	}

	type Closable = {def close()}

	def closing[R <: Closable, T](resource: R)(block: R => T): T = {
		try {
			block(resource)
		} finally {
			if (resource != null)
				resource.close()
		}
	}

	implicit class BooleanUtils(val value: Boolean) extends AnyVal {
		def ?[T](t: => T): Option[T] = {
			if (value) Some(t) else None
		}
	}

	// This class is not placed inside TraversableOnceUtils because of error:
	//   implementation restriction: nested class is not allowed in value class
	private class MappedOrdering[T, K](cmp: Ordering[K], f: T => K) extends Ordering[T] {
		def compare(x: T, y: T): Int = cmp.compare(f(x), f(y))
	}

	implicit class TraversableOnceUtils[T](val traversable: TraversableOnce[T]) extends AnyVal {
		def maxBy[K](f: T => K)(implicit cmp: Ordering[K]): T = {
			traversable.max(new MappedOrdering(cmp, f))
		}

		def minBy[K](f: T => K)(implicit cmp: Ordering[K]): T = {
			traversable.min(new MappedOrdering(cmp, f))
		}
	}

	/** Convert function without arguments into runnable.
	  *
	  * I have tried to use "=>Unit" argument instead of ()=>Unit, but then
	  * following code:
	  *
	  *   view.post { if (obj != null) { doSmth() } }
	  *
	  * is converted into
	  *
	  *   view.post( if (obj != null) new Runnable { def run() { doSmth() } } )
	  *
	  * and not
	  *
	  *   view.post(new Runnable { def run() { if (obj != null) doSmth() } })
	  *
	  * as expected.
	  */
	implicit def functionAsRunnable(f: () => Unit): Runnable = new Runnable {
		def run() { f() }
	}
}