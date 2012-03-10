package net.kriomant.gortrans;

import android.os.AsyncTask;

/** Bridge class to work around Scala bug
 * https://issues.scala-lang.org/browse/SI-1459
 */
public abstract class AsyncTaskBridge<Params, ProgressBar, Result> extends AsyncTask<Params, ProgressBar, Result>{
    @Override
    protected Result doInBackground(Params... params) {
        return doInBackgroundBridge(params[0]);
    }
    
    protected abstract Result doInBackgroundBridge(Params params);
}
