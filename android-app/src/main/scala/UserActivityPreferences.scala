package net.kriomant.gortrans

import android.content.Context
import java.util


object UserActivityPreferences {
	val NAME = "user-activity"

	private final val PREF_LAST_NEWS_READING_TIME = "last-news-reading-time"
}

class UserActivityPreferences(context: Context) {
	import UserActivityPreferences._

	private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

	def lastNewsReadingTime: util.Date = {
		new util.Date(prefs.getLong(PREF_LAST_NEWS_READING_TIME, 0))
	}

	def lastNewsReadingTime_=(time: util.Date) {
		prefs.edit().putLong(PREF_LAST_NEWS_READING_TIME, time.getTime).commit()
	}
}