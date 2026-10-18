package com.remu.app.core.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.remu.app.R
import java.time.Instant
import java.util.Locale

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH

@Composable
fun dateWords(): DateWords = DateWords(
    today = stringResource(R.string.date_today),
    yesterday = stringResource(R.string.date_yesterday),
    justNow = stringResource(R.string.time_just_now),
    minutesAgo = stringResource(R.string.time_min_ago),
    hoursAgo = stringResource(R.string.time_hr_ago),
)

/** "18 Oct 2026" for a contract date or timestamp; empty when missing. */
@Composable
fun dateText(value: String?): String {
    val i = Formatters.parseInstant(value) ?: return ""
    return Formatters.date(i, currentLocale())
}

@Composable
fun friendlyDateText(value: String?, now: Instant = Instant.now()): String {
    val i = Formatters.parseInstant(value) ?: return ""
    return Formatters.friendlyDate(i, now, dateWords(), currentLocale())
}
