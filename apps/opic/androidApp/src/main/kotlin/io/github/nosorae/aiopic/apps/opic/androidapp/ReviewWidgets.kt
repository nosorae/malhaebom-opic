package io.github.nosorae.aiopic.apps.opic.androidapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import io.github.nosorae.aiopic.apps.opic.shared.REVIEW_LINK
import io.github.nosorae.aiopic.apps.opic.shared.ReviewWidget
import io.github.nosorae.aiopic.apps.opic.shared.nextDayStart
import io.github.nosorae.aiopic.apps.opic.shared.reviewWidget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * 오늘 복습 위젯(F19.6, F19.7). 위젯은 앱 프로세스에서 돌아서 그릴 때 DB 를 바로 읽는다.
 * 앱이 복습이나 연습 정리로 카드가 바뀔 때 [draw] 를 부르고, 날이 바뀌는 새벽 4시에는 알람이 위젯을 깨워 다시 센다.
 */
class ReviewWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val graph = (context.applicationContext as OpicApplication).graph
        val result = goAsync()
        MainScope().launch {
            try {
                ReviewWidgets.draw(context, graph.reviewWidget().first(), graph.nextDayStart())
            } finally {
                result.finish()
            }
        }
    }

    /** 마지막 위젯을 지우면 날 바뀜 알람도 지운다. */
    override fun onDisabled(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(ReviewWidgets.dayChange(context))
}

internal object ReviewWidgets {
    /** 홈 화면에 둔 위젯을 [widget] 으로 다시 그리고 [redrawAt](다음 날 새벽 4시)에 다시 깨운다. 둔 위젯이 없으면 아무것도 하지 않는다. */
    suspend fun draw(context: Context, widget: ReviewWidget, redrawAt: Instant) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ReviewWidgetProvider::class.java))
        if (ids.isEmpty()) return
        // 알림처럼 복습 링크로 앱을 새로 띄운다(F19.5). 위젯을 누르는 때는 앱이 화면에 없다.
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(REVIEW_LINK), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val views = RemoteViews(context.packageName, R.layout.review_widget).apply {
            setTextViewText(R.id.review_widget_title, widget.title())
            setTextViewText(R.id.review_widget_text, widget.text())
            setOnClickPendingIntent(R.id.review_widget, PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        manager.updateAppWidget(ids, views)
        // 정확한 시각 알람 권한 없이 건다. 기기가 쉬는 중이면 몇 분 늦을 수 있다(ADR 0047 과 같다).
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, redrawAt.toEpochMilliseconds(), dayChange(context))
    }

    /** 위젯 갱신 방송. 시스템이 [ReviewWidgetProvider.onUpdate] 로 넘긴다. */
    fun dayChange(context: Context): PendingIntent {
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ReviewWidgetProvider::class.java))
        val update = Intent(context, ReviewWidgetProvider::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        return PendingIntent.getBroadcast(context, 0, update, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
