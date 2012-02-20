package net.kriomant.gortrans

import java.io.Reader

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
}