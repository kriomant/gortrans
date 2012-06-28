package net.kriomant.gortrans
package explorer

import org.eclipse.swt.widgets.Display
import org.eclipse.swt.events.{ShellEvent, ShellAdapter}

object Application {
	def main(args: Array[String]) {
		Display.setAppName("Image Sorter")
		val display = new Display

		try {
			val window = new MainWindow(display)

			window.shell.open()

			while (!window.shell.isDisposed) {
				if (!display.readAndDispatch()) {
					display.sleep()
				}
			}
		} finally {
			display.dispose()
		}
	}
}

