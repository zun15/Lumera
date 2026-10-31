package io.github.assrender

import android.content.Context
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory

/*
 * No-op stand-ins for the external ASS renderer, matching only the API Lumera calls
 * (see ExoPlayerBackend). Nothing is drawn; ASS/SSA tracks are handled by ExoPlayer's defaults.
 */

/** Empty overlay: the real one draws styled ASS subtitles on top of the video. */
class SubtitleOverlayView(context: Context) : FrameLayout(context)

class AssHandler(context: Context, val overlayView: SubtitleOverlayView) {
    var player: Player? = null

    /** Never fired by the stub, because no ASS track is ever detected. */
    var onAssTrackDetected: (() -> Unit)? = null

    fun selectTrack(index: Int) {}

    fun clearOverlay() {}

    fun release() {
        player = null
        onAssTrackDetected = null
    }
}

class AssRenderersFactory(
    context: Context,
    @Suppress("UNUSED_PARAMETER") handler: AssHandler
) : DefaultRenderersFactory(context)

class AssExtractorsFactory(
    @Suppress("UNUSED_PARAMETER") handler: AssHandler
) : ExtractorsFactory by DefaultExtractorsFactory()
