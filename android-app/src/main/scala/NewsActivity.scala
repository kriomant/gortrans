package net.kriomant.gortrans

import com.actionbarsherlock.app.SherlockFragmentActivity
import android.os.Bundle
import android.widget.{TextView, ListView}
import android.view.View
import android.content.{Intent, Context}
import android.support.v4.app.LoaderManager.LoaderCallbacks
import android.support.v4.content.Loader
import android.support.v4.widget.CursorAdapter
import com.actionbarsherlock.view.{Menu, MenuItem}
import android.text._
import java.net.URI
import android.text.style.URLSpan
import android.text.method.LinkMovementMethod
import scala.Some

object NewsActivity {
	final val LOADER_NEWS = 0

	def createIntent(context: Context): Intent = new Intent(context, classOf[NewsActivity])
}

class NewsActivity extends SherlockFragmentActivity with TypedActivity with HavingSidebar {
	import NewsActivity._

	var listView: ListView = null

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.news)

		listView = findView(TR.news_list)
		val noNewsText = findView(TR.no_news)

		val adapter = new NewsAdapter(this, null)
		listView.setAdapter(adapter)
		listView.setEmptyView(noNewsText)

		val db = getApplication.asInstanceOf[CustomApplication].database

		getSupportLoaderManager.initLoader(LOADER_NEWS, null, new LoaderCallbacks[Database.NewsTable.Cursor] {
			def onCreateLoader(p1: Int, p2: Bundle): Loader[Database.NewsTable.Cursor] = {
				new SQLiteCursorLoader(NewsActivity.this, { db.loadNews() })
			}

			def onLoadFinished(loader: Loader[Database.NewsTable.Cursor], cursor: Database.NewsTable.Cursor) {
				adapter.swapCursor(cursor)
			}

			def onLoaderReset(loader: Loader[Database.NewsTable.Cursor]) {
				adapter.swapCursor(null)
			}
		})
	}

	override def onCreateOptionsMenu(menu: Menu): Boolean = {
		super.onCreateOptionsMenu(menu)
		getSupportMenuInflater.inflate(R.menu.news_menu, menu)
		true
	}

	override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
		case R.id.refresh => refreshNews(); true
		case _ => super.onOptionsItemSelected(item)
	}

	private def refreshNews() {
		Service.updateNews(this)
	}
}

class NewsAdapter(val context: Context, cursor: Database.NewsTable.Cursor)
	extends CursorAdapter(context, cursor)
	with EasyCursorAdapter[Database.NewsTable.Cursor] {

	case class SubViews(title: TextView, content: TextView, readMore: TextView)
	val itemLayout: Int = R.layout.news_item

	def findSubViews(view: View): SubViews = SubViews(
		view.findViewById(R.id.news_title).asInstanceOf[TextView],
		view.findViewById(R.id.news_content).asInstanceOf[TextView],
		{
			val textView = view.findViewById(R.id.read_more).asInstanceOf[TextView]
			textView.setMovementMethod(LinkMovementMethod.getInstance)
			textView
		}
	)

	def adjustItem(cursor: Database.NewsTable.Cursor, views: SubViews) {
		views.title.setText(cursor.title)
		views.content.setText(cursor.content)
		cursor.readMoreLink match {
			case Some(link) =>
				views.readMore.setVisibility(View.VISIBLE)
				views.readMore.setText(linkSpannable(context.getString(R.string.read_more), link))
			case None =>
				views.readMore.setVisibility(View.GONE)
		}
	}

	private def linkSpannable(title: String, href: String): CharSequence = {
		val text = new SpannableString(title)
		text.setSpan(new URLSpan(href), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		text
	}
}