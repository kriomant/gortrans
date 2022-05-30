package net.kriomant.gortrans;

import android.os.AsyncTask;

/** Bridge class to work around Scala bugs
 * https://issues.scala-lang.org/browse/SI-1459
 * https://issues.scala-lang.org/browse/SI-5703
 */
public abstract class AsyncTaskBridge<Progress, Result> extends AsyncTask<Void, Progress, Result> {
	@Override
	public Result doInBackground(Void... params) {
		return doInBackgroundBridge();
	}

	@Override
	public void onProgressUpdate(Progress... values) {
		onProgressUpdateBridge(values[0]);
	}

	protected abstract Result doInBackgroundBridge();
	protected void onProgressUpdateBridge(Progress progress) {}
}
