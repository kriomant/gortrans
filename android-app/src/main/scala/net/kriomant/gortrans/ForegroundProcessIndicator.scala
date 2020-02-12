package net.kriomant.gortrans

import android.app.{Activity, AlertDialog, ProgressDialog}
import android.content.DialogInterface

class ForegroundProcessIndicator(context: Activity, retry: () => Unit) extends DataManager.ProcessIndicator {
  val progressDialog: ProgressDialog = {
    val d = new ProgressDialog(context)
    d.setTitle(R.string.loading)
    d.setMessage(context.getString(R.string.wait_please))
    d
  }

  def startFetch() {
    progressDialog.show()
  }

  def stopFetch() {
    progressDialog.dismiss()
  }

  def onSuccess() {}

  def onError() {
    new AlertDialog.Builder(context)
      .setTitle(R.string.cant_load)
      .setMessage(R.string.loading_failure)

      .setPositiveButton(R.string.retry, new DialogInterface.OnClickListener {
        def onClick(p1: DialogInterface, p2: Int) {
          p1.dismiss()
          retry()
        }
      })

      .setNegativeButton(R.string.abort, new DialogInterface.OnClickListener {
        def onClick(p1: DialogInterface, p2: Int) {
          p1.dismiss()
          context.finish()
        }
      }).create().show()
  }
}
