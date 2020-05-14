package net.kriomant.gortrans

import java.util

import android.content.Context


object UserActivityPreferences {
  private final val PREF_LAST_NEWS_READING_TIME = "last-news-reading-time"
  val NAME = "user-activity"
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