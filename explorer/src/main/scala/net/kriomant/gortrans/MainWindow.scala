package net.kriomant.gortrans

import org.eclipse.swt.widgets._
import org.eclipse.swt.{widgets, SWT}
import org.eclipse.swt.layout.{GridData, GridLayout}
import org.eclipse.swt.events.{SelectionEvent, SelectionAdapter}
import net.kriomant.gortrans.Client.RouteInfoRequest
import net.kriomant.gortrans.core.DirectionsEx

class MainWindow(display: Display) {
	val shell = new Shell(display)
	shell.setText("GorTrans explorer")
	shell.setLayout(new GridLayout(2, false))

	def withGui(block: => Unit) {
		display.asyncExec(new Runnable {
			def run() { block }
		})
	}

	val refreshButton = new Button(shell, SWT.PUSH | SWT.FLAT)
	refreshButton.setText("Refresh")
	refreshButton.addSelectionListener(new SelectionAdapter {
		override def widgetSelected(p1: SelectionEvent) {
			refresh()
		}
	})

	val spacer = new Label(shell, SWT.NONE)

	val routesList = new List(shell, SWT.V_SCROLL)
	routesList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true))

	val stopsList = new List(shell, SWT.V_SCROLL)
	stopsList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true))

	def refresh() {
		val thread = new Thread(new Runnable {
			def run() {
				try {
					withGui { refreshButton.setEnabled(false) }
					process()
				} finally {
					withGui { refreshButton.setEnabled(true) }
				}
			}
		})
		thread.start()
	}

	def process() {
		// This method is called in separate thread, wrap all SWT calls with `withGui`.

		object LoggerStub extends Logger {
			def debug(msg: String) {}
			def verbose(msg: String) {}
		}
		val client = new Client(LoggerStub)

		// Load routes.
		val rawRoutesByType = client.getRoutesList()
		val routesByType = parsing.parseRoutesJson(rawRoutesByType)
		val routes = routesByType.values.flatten
		withGui {
			routes foreach { route =>
				routesList.add("%s %s: %s — %s" format (route.vehicleType, route.name, route.begin, route.end))
			}
		}

		// Load stops.
		val rawStops = client.getStopsList("")
		val stops = parsing.parseStopsList(rawStops)
		withGui {
			stops.toSeq.sortBy(_._2) foreach { case (stopName, stopId) =>
				stopsList.add("%s: %s" format (stopId, stopName))
			}
		}

		// Load route points.
		val requests = routes map { r =>
			new RouteInfoRequest(r.vehicleType, r.id, r.name, DirectionsEx.Both)
		}
		val rawRoutesInfo = client.getRoutesInfo(requests)
		val routesInfo = parsing.parseRoutesPoints(rawRoutesInfo)

		assert(routesInfo.keySet == routes.map(_.id).toSet)
	}
}

