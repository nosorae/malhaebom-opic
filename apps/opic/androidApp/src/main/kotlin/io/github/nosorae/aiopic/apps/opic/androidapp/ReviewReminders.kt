package io.github.nosorae.aiopic.apps.opic.androidapp

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.nosorae.aiopic.apps.opic.shared.REVIEW_LINK
import io.github.nosorae.aiopic.apps.opic.shared.ReviewReminder
import io.github.nosorae.aiopic.apps.opic.shared.nextReviewReminderTime
import io.github.nosorae.aiopic.apps.opic.shared.reviewReminderNow
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * 복습 알림(F19.3). Android 는 알림 시각에 앱 코드를 돌릴 수 있어서 그때 DB 를 읽어 보낼지 정하고 다음 날 같은 시각을 다시 건다.
 * 정확한 시각 알람 권한을 받지 않고 기기가 쉬는 중에도 울리는 알람을 쓴다. 몇 분 늦을 수 있다.
 */
internal object ReviewReminders {
    private const val CHANNEL = "review_reminder"
    private const val NOTIFICATION_ID = 1903
    const val FIRE = "io.github.nosorae.aiopic.REVIEW_REMINDER"

    fun createChannel(context: Context) = context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "복습 알림", NotificationManager.IMPORTANCE_DEFAULT))

    /** [at] 에 알람을 건다. null 이면(알림 꺼짐) 걸어 둔 알람을 지운다. */
    fun arm(context: Context, at: Instant?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val fire = PendingIntent.getBroadcast(context, 0, Intent(context, ReviewReminderReceiver::class.java).setAction(FIRE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(fire)
        at?.let { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.toEpochMilliseconds(), fire) }
    }

    /** 누르면 복습 링크로 앱을 새로 띄워 복습 화면을 연다(F19.5). 앱이 화면에 없을 때만 띄우는 알림이라 앞 화면을 잃지 않는다. */
    suspend fun notify(context: Context, reminder: ReviewReminder) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(REVIEW_LINK), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.title())
            .setContentText(reminder.text())
            .setContentIntent(PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/** 알림 시각, 기기 재시작, 앱 업데이트에 깨어난다. 재시작과 업데이트 뒤에는 시스템이 알람을 지워서 다시 건다. */
class ReviewReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as OpicApplication).graph
        val result = goAsync()
        MainScope().launch {
            try {
                // 앱을 보고 있으면 띄우지 않는다(F19.3).
                if (intent.action == ReviewReminders.FIRE && !AnalysisNotifications.appVisible()) {
                    graph.reviewReminderNow()?.let { ReviewReminders.notify(context, it) }
                }
                ReviewReminders.arm(context, graph.nextReviewReminderTime())
            } finally {
                result.finish()
            }
        }
    }
}
