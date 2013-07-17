package net.kriomant.gortrans

import android.app.{PendingIntent, AlarmManager, NotificationManager, IntentService}
import android.content.{Context, Intent}
import android.support.v4.app.NotificationCompat
import java.util
import android.util.Log

object Service {
	private final val TAG = classOf[Service].getName

	private final val ACTION_UPDATE_NEWS = "update-news"
	private final val ACTION_NEWS_SHOWN = "news-shown"

	private final val NOTIFICATION_ID_NEWS = 1

	private final val UPDATE_NEWS_ALARM = 1

	private final val REQUEST_NEWS = 1

	private final val NEWS_SOURCE_NAME = "news"
	private final val NEWS_UPDATE_PERIOD = AlarmManager.INTERVAL_DAY /* milliseconds */

	private def createUpdateNewsIntent(context: Context): Intent = {
		new Intent(ACTION_UPDATE_NEWS, null, context, classOf[Service])
	}

	def updateNews(context: Context) {
		context.startService(createUpdateNewsIntent(context))
	}

	/** Notify service about user opened news activity. */
	def notifyNewsAreShown(context: Context) {
		context.startService(new Intent(ACTION_NEWS_SHOWN, null, context, classOf[Service]))
	}

	def init(app: CustomApplication) {
		Log.d(TAG, "Service initialization")

		val db = app.database
		val alarmManager = app.getSystemService(Context.ALARM_SERVICE).asInstanceOf[AlarmManager]

		val now = new java.util.Date
		val nextUpdateTime = db.getLastUpdateTime(NEWS_SOURCE_NAME) match {
			case Some(time) =>
				val next = new java.util.Date(time.getTime + NEWS_UPDATE_PERIOD)
				if (next after now) next else now
			case None => now
		}
		Log.d(TAG, "Next news check time: %s" format nextUpdateTime)

		val pendingIntent = PendingIntent.getService(app, UPDATE_NEWS_ALARM, createUpdateNewsIntent(app), 0)
		//alarmManager.setInexactRepeating(AlarmManager.RTC, nextUpdateTime.getTime, NEWS_UPDATE_PERIOD, pendingIntent)
		alarmManager.setRepeating(AlarmManager.RTC, nextUpdateTime.getTime, NEWS_UPDATE_PERIOD, pendingIntent)
	}
}

class Service extends IntentService("Service") {
	import Service._

	def onHandleIntent(intent: Intent) {
		intent.getAction match {
			case ACTION_UPDATE_NEWS => doUpdateNews()
			case ACTION_NEWS_SHOWN => doAknowledgeNewsAreShown()
		}
	}

	def doUpdateNews() {
		Log.d(TAG, "Check for news")

		val client = getApplication.asInstanceOf[CustomApplication].dataManager.client
		val db = getApplication.asInstanceOf[CustomApplication].database

		val news = parsing.parseNews(client.getNews())
		Log.d(TAG, "Loaded %d news" format news.size)
		val loadedAt = new java.util.Date
		val latestExternalId = db.loadLatestNewsStoryExternalId()
		Log.d(TAG, "Last known news story id: %s" format latestExternalId)

		val freshNews = latestExternalId match {
			case Some(latestId) => news.takeWhile(_.id != latestId)
			case None => news
		}

		Log.d(TAG, "Fresh news count: %d" format freshNews.size)
		if (freshNews.nonEmpty) {
			for (story <- freshNews.view.reverse) {
				db.addNews(story, loadedAt)
			}
		}

		//db.updateLastUpdateTime(NEWS_SOURCE_NAME, loadedAt)

		if (freshNews.nonEmpty) {
			val prefs = new UserActivityPreferences(this)
			val lastNewsReadingTime = prefs.lastNewsReadingTime

			utils.closing (db.loadNewsLoadedSince(lastNewsReadingTime)) { cursor =>
				showNewsNotification(cursor)
			}
		}
	}

	def doAknowledgeNewsAreShown() {
		Log.d(TAG, "Acknowledge news are shown")

		val now = new util.Date

		val prefs = new UserActivityPreferences(this)
		prefs.lastNewsReadingTime = now

		hideNewsNotification()
	}

	def showNewsNotification(freshNews: Database.NewsTable.Cursor) {
		Log.d(TAG, "Show news notification")

		val builder = new NotificationCompat.Builder(this)
		builder
			.setSmallIcon(R.drawable.news)
			.setAutoCancel(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(PendingIntent.getActivity(this, REQUEST_NEWS, NewsActivity.createIntent(this), 0))

		if (freshNews.getCount == 1) {
			freshNews.moveToFirst()
			builder
				.setContentTitle(freshNews.title)
				.setContentText(freshNews.content)
		} else {
			builder
				.setContentTitle(compatibility.plurals.getQuantityString(this, R.plurals.n_news, freshNews.getCount, freshNews.getCount))
		}

		val notification = builder.getNotification()
		val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE).asInstanceOf[NotificationManager]
		notificationManager.notify(NOTIFICATION_ID_NEWS, notification)
	}

	def hideNewsNotification() {
		Log.d(TAG, "Hide news notification")

		val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE).asInstanceOf[NotificationManager]
		notificationManager.cancel(NOTIFICATION_ID_NEWS)
	}
}
