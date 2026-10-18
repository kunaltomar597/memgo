package com.remu.app.feature.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.remu.app.MainActivity
import com.remu.app.R
import com.remu.app.core.format.DateWords
import com.remu.app.core.locale.LanguageManager
import com.remu.app.data.alerts.AlertItem
import com.remu.app.data.alerts.AlertNotifier
import java.time.Instant
import java.util.Locale

/**
 * Heads-up notification built on the phone from the fetched card. The lock screen shows only
 * "Member at the gate" (the public version); the name shows once the phone is unlocked.
 * OPEN item in docs/SECURITY.md 3.2 resolved here to the stricter option until the lawyer decides.
 */
class AndroidAlertNotifier(
    private val context: Context,
    private val language: LanguageManager,
    private val toneEnabled: () -> Boolean,
) : AlertNotifier {

    private fun localized(): Context {
        val locale = Locale.forLanguageTag(language.current().tag)
        val cfg = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(cfg)
    }

    fun ensureChannels() {
        val ctx = localized()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val vibrate = NotificationChannel(CHANNEL_VIBRATE, ctx.getString(R.string.notification_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.notification_channel_desc)
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        val tone = NotificationChannel(CHANNEL_TONE, ctx.getString(R.string.notification_channel_tone), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.notification_channel_desc)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(vibrate)
        nm.createNotificationChannel(tone)
    }

    override fun notify(item: AlertItem) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels()
        val ctx = localized()
        val labels = AlertLabels(
            lastVisit = ctx.getString(R.string.alert_last_visit),
            note = ctx.getString(R.string.alert_note),
            words = DateWords(
                today = ctx.getString(R.string.date_today), yesterday = ctx.getString(R.string.date_yesterday),
                justNow = ctx.getString(R.string.time_just_now), minutesAgo = ctx.getString(R.string.time_min_ago),
                hoursAgo = ctx.getString(R.string.time_hr_ago),
            ),
            locale = ctx.resources.configuration.locales[0],
        )
        val text = AlertCardFormatter.format(item.payload, Instant.now(), labels)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_VISIT_ID, item.visitId)
            putExtra(MainActivity.EXTRA_MEMBER_ID, item.payload.memberId)
        }
        val pending = PendingIntent.getActivity(
            context, item.visitId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = if (toneEnabled()) CHANNEL_TONE else CHANNEL_VIBRATE
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notification_title_public))
            .build()
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.name)
            .setContentText(text.details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text.details.ifEmpty { null }, text.note).joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(item.visitId.hashCode(), n)
        } catch (_: SecurityException) {
            // Notification permission withdrawn; the in-app card still shows.
        }
    }

    companion object {
        const val CHANNEL_VIBRATE = "alerts_vibrate"
        const val CHANNEL_TONE = "alerts_tone"
    }
}
