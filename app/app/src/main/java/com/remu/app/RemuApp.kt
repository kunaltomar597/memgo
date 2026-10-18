package com.remu.app

import android.app.Application
import com.remu.app.core.locale.LanguageManager
import com.remu.app.core.observability.SentrySetup
import com.remu.app.data.session.DataStoreSessionStore
import com.remu.app.data.session.KeystoreBlobCipher
import com.remu.app.data.state.SystemInternetState
import com.remu.app.feature.alerts.AndroidAlertNotifier
import com.remu.app.feature.enrol.CameraXCounterCamera
import com.remu.app.feature.enrol.CounterCameraFactory
import com.remu.app.feature.export.AndroidExportShare
import com.remu.app.feature.export.ExportFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class RemuApp : Application() {
    /** Built on first use, so tests that never touch it never need the Android Keystore. */
    val container: AppContainer by lazy { build() }

    override fun onCreate() {
        super.onCreate()
        SentrySetup.init(this)
        // Consent export files left by a share sheet the app never saw return (process killed).
        Thread { ExportFiles.deleteOlderThan(ExportFiles.dir(this)) }.start()
    }

    private fun build(): AppContainer {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val language = LanguageManager()
        lateinit var c: AppContainer
        val notifier = AndroidAlertNotifier(this, language) { c.session.current.alertTone }
        c = AppContainer(
            appContext = this,
            scope = scope,
            sessionStore = DataStoreSessionStore(File(filesDir, "datastore/session.enc"), KeystoreBlobCipher(), scope),
            cloudBaseUrl = BuildConfig.REMU_CLOUD_URL,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
            internet = SystemInternetState(this),
            cameraFactory = CounterCameraFactory { _, front -> CameraXCounterCamera(front) },
            notifier = notifier,
            language = language,
            exportShare = AndroidExportShare(this),
            firebaseEnabled = BuildConfig.FIREBASE_CONFIGURED,
        )
        notifier.ensureChannels()
        return c
    }
}
