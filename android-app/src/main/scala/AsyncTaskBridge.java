package net.kriomant.gortrans;

import android.os.AsyncTask;

/** Bridge class to work around Scala bug
 * https://issues.scala-lang.org/browse/SI-1459
 */
public abstract class AsyncTaskBridge<Params, Progress, Result> extends AsyncTask<Params, Progress, Result> {
	@Override
	public Result doInBackground(Params... params) {
		return doInBackgroundBridge(params);
	}

	@Override
	public void onProgressUpdate(Progress... values) {
		onProgressUpdateBridge(values);
	}

	protected abstract Result doInBackgroundBridge(Params[] params);
	protected void onProgressUpdateBridge(Progress[] progresses) {}
}
