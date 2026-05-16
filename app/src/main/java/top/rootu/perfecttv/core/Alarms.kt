package top.rootu.perfecttv.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import top.rootu.perfecttv.App
import top.rootu.perfecttv.R
import top.rootu.perfecttv.ui.TvActivity

/**
 * Напоминания, когда приложение закрыто: системный будильник на ближайший показ и
 * уведомление «Смотреть». Пока приложение на экране, показывает его собственное окно
 * (TvActivity), а будильник только перепланируется.
 */
object Alarms {
    private const val CHANNEL = "reminders"
    const val EXTRA_REMINDER = "reminder"

    /** Поставить будильник на ближайшее напоминание (или снять, если их нет) */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, Receiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = App.repo.reminders.nextDue()
        am.cancel(pi)
        if (at <= 0) return
        // часы приставки могут уплывать: будильник — по системным часам с поправкой
        val wall = at * 1000 - (Clock.nowMs() - System.currentTimeMillis())
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
    }

    /** Показ уведомлений о тех, кого пора (приложение не на экране) */
    private fun notifyDue(context: Context) {
        val rem = App.repo.reminders
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания о передачах", NotificationManager.IMPORTANCE_HIGH))
        }
        rem.onDue = { r ->
            val open = Intent(context, TvActivity::class.java).putExtra(EXTRA_REMINDER, r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(context, r.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val started = r.start <= Clock.now()
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, CHANNEL) else android.app.Notification.Builder(context)
            val n = b.setSmallIcon(R.drawable.ic_remind)
                .setContentTitle(r.title)
                .setContentText(r.chName + " · " + if (started) "уже идёт" else "в " + top.rootu.perfecttv.ui.Fmt.hhmm(r.start))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_REMINDER)
                .build()
            try {
                nm.notify(r.id.hashCode(), n)
            } catch (_: SecurityException) {
                // нет разрешения на уведомления — покажем при запуске приложения
            }
        }
        rem.check()
        rem.onDue = null
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!App.foreground) notifyDue(context)
            schedule(context)
        }
    }
}
