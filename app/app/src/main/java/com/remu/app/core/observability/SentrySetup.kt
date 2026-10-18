package com.remu.app.core.observability

import android.content.Context
import com.remu.app.BuildConfig
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Sentry error tracking (TASK-118, app half). DSN from BuildConfig; empty means disabled. No PII:
 * default PII off, no screenshots or view hierarchy, no user-interaction breadcrumbs, and every
 * event passes [SentryScrubber] before it leaves the phone.
 */
object SentrySetup {
    fun enabled(dsn: String = BuildConfig.SENTRY_DSN): Boolean = dsn.isNotBlank()

    fun init(context: Context, dsn: String = BuildConfig.SENTRY_DSN) {
        if (!enabled(dsn)) return
        SentryAndroid.init(context) { o -> configure(o, dsn) }
    }

    fun configure(o: SentryOptions, dsn: String) {
        o.dsn = dsn
        o.release = "remu-app@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        o.environment = BuildConfig.BUILD_TYPE
        o.isSendDefaultPii = false
        o.maxRequestBodySize = SentryOptions.RequestSize.NONE
        o.isAttachStacktrace = true
        o.setDiagnosticLevel(SentryLevel.ERROR)
        if (o is io.sentry.android.core.SentryAndroidOptions) {
            o.isAttachScreenshot = false
            o.isAttachViewHierarchy = false
            o.isEnableUserInteractionBreadcrumbs = false
            o.isEnableUserInteractionTracing = false
            o.isEnableNetworkEventBreadcrumbs = false
        }
        o.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> SentryScrubber.scrub(event) }
        o.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { b, _ -> SentryScrubber.scrubBreadcrumb(b) }
    }
}
