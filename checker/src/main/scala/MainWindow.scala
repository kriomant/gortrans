package net.kriomant.gortrans
package checker

import org.eclipse.swt.widgets.{Display, Shell}
import org.eclipse.swt.SWT
import org.eclipse.swt.layout.FillLayout

class MainWindow(display: Display) {
	val shell = new Shell(display, SWT.SHELL_TRIM)
	shell.setText("GorTrans checker")
	shell.setLayout(new FillLayout)
}

