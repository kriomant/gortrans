package net.kriomant.gortrans

import java.io.Reader
import net.kriomant.gortrans.utils.BooleanUtils
import java.security.Key

object utils {

	class ReaderUtils(reader: Reader) {
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

	implicit def readerUtils(r: Reader) = new ReaderUtils(r)

	type Closable = {def close()}

	def closing[R <: Closable, T](resource: R)(block: R => T): T = {
		try {
			block(resource)
		} finally {
			if (resource != null)
				resource.close()
		}
	}

	class BooleanUtils(value: Boolean) {
		def ?[T](t: => T): Option[T] = {
			if (value) Some(t) else None
		}
	}

	implicit def booleanUtils(b: Boolean) = new BooleanUtils(b)

	class TraversableOnceUtils[T](traversable: TraversableOnce[T]) {
		def maxBy[K](f: T => K)(implicit cmp: Ordering[K]): T = {
			traversable.max(new Ordering[T] {
				def compare(x: T, y: T): Int = cmp.compare(f(x), f(y))
			})
		}

		def minBy[K](f: T => K)(implicit cmp: Ordering[K]): T = {
			traversable.min(new Ordering[T] {
				def compare(x: T, y: T): Int = cmp.compare(f(x), f(y))
			})
		}
	}

	implicit def traversableOnceUtils[T](traversable: TraversableOnce[T]) = new TraversableOnceUtils(traversable)

	implicit def functionAsRunnable(f: => Unit): Runnable = new Runnable {
		def run() { f }
	}
}