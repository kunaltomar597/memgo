package com.remu.app.feature.timeline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

/**
 * Plays a 5-second match clip from bytes held in memory (fetched over the LAN from
 * `GET /clips/{id}`). Nothing is written to storage or cache, and there is no share or save.
 */
@OptIn(UnstableApi::class)
@Composable
fun ClipPlayer(bytes: ByteArray, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(bytes) {
        ExoPlayer.Builder(context).build().apply {
            val factory = DataSource.Factory { ByteArrayDataSource(bytes) }
            val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse("remu-clip://memory")))
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("clip_player"),
    )
}
