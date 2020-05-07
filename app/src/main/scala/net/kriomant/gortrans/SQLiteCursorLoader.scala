package net.kriomant.gortrans

import android.content.Context
import android.database.{ContentObserver, Cursor}
import android.support.v4.content.AsyncTaskLoader

class SQLiteCursorLoader[CustomCursor >: Null <: Cursor](context: Context, fetch: => CustomCursor) extends AsyncTaskLoader[CustomCursor](context) {
  final val mObserver = new ForceLoadContentObserver

  var mCursor: CustomCursor = _

  /* Runs on a worker thread */
  override def loadInBackground(): CustomCursor = {
    val cursor = fetch
    if (cursor != null) {
      // Ensure the cursor window is filled
      cursor.getCount
      registerContentObserver(cursor, mObserver)
    }
    cursor
  }

  /**
    * Registers an observer to get notifications from the content provider
    * when the cursor needs to be refreshed.
    */
  def registerContentObserver(cursor: CustomCursor, observer: ContentObserver) {
    cursor.registerContentObserver(mObserver)
  }

  /**
    * Starts an asynchronous load of the contacts list data. When the result is ready the callbacks
    * will be called on the UI thread. If a previous load has been completed and is still valid
    * the result may be passed to the callbacks immediately.
    *
    * Must be called from the UI thread
    */
  override def onStartLoading() {
    if (mCursor != null) {
      deliverResult(mCursor)
    }
    if (takeContentChanged() || mCursor == null) {
      forceLoad()
    }
  }

  /* Runs on the UI thread */
  override def deliverResult(cursor: CustomCursor) {
    if (isReset) {
      // An async query came in while the loader is stopped
      if (cursor != null) {
        cursor.close()
      }
      return
    }
    val oldCursor = mCursor
    mCursor = cursor

    if (isStarted) {
      super.deliverResult(cursor)
    }

    if (oldCursor != null && oldCursor != cursor && !oldCursor.isClosed) {
      oldCursor.close()
    }
  }

  override def onCanceled(cursor: CustomCursor) {
    if (cursor != null && !cursor.isClosed) {
      cursor.close()
    }
  }

  override protected def onReset() {
    super.onReset()

    // Ensure the loader is stopped
    onStopLoading()

    if (mCursor != null && !mCursor.isClosed) {
      mCursor.close()
    }
    mCursor = null
  }

  /**
    * Must be called from the UI thread
    */
  override protected def onStopLoading() {
    // Attempt to cancel the current load task if possible.
    cancelLoad()
  }
}