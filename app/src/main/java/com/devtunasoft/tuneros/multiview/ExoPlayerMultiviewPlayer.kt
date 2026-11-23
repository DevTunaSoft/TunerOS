package com.devtunasoft.tuneros.multiview

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Adapts exactly one ExoPlayer + one PlayerView to [MultiviewPlayer]. Instances are never shared
 * between tiles; the controller owns creation and release. Volume is always set by the controller so
 * the secondary tile cannot accidentally become audible.
 */
internal class ExoPlayerMultiviewPlayer(
    private val player: ExoPlayer,
    private val view: PlayerView,
) : MultiviewPlayer {
    init {
        view.player = player
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override fun release() {
        view.player = null
        player.release()
    }

    companion object {
        /** Builds and starts one tile player. The controller sets audible/muted volume immediately after. */
        fun create(
            context: Context,
            view: PlayerView,
            mediaItem: MediaItem,
        ): ExoPlayerMultiviewPlayer = ExoPlayerMultiviewPlayer(
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            },
            view,
        )
    }
}
