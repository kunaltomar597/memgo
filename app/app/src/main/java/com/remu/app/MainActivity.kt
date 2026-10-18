package com.remu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.remu.app.core.nav.PendingAlert
import com.remu.app.core.nav.RemuNavHost
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : AppCompatActivity() {
    private val pendingAlert = MutableStateFlow<PendingAlert?>(null)

    private val container: AppContainer get() = (application as RemuApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent { RemuNavHost(container, pendingAlert) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.hubStatus.start()
    }

    override fun onResume() {
        super.onResume()
        container.appState.lockIfIdle()
    }

    override fun onStop() {
        container.hubStatus.stop()
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        container.appState.touch()
    }

    private fun readIntent(intent: Intent?) {
        val visit = intent?.getStringExtra(EXTRA_VISIT_ID) ?: return
        val member = intent.getStringExtra(EXTRA_MEMBER_ID) ?: return
        pendingAlert.value = PendingAlert(visit, member)
    }

    companion object {
        const val EXTRA_VISIT_ID = "visit_id"
        const val EXTRA_MEMBER_ID = "member_id"
    }
}
