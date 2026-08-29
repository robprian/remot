package com.robrion.remot.ui

import android.graphics.RectF
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Renders a remote [VideoTrack] via a WebRTC [SurfaceViewRenderer] and maps
 * touches into normalized (0..1) control events for the host.
 *
 * Aspect-correct mapping: the renderer uses SCALE_ASPECT_FIT, so the video is
 * letterboxed when aspect ratios differ. Touches are mapped only inside the
 * actual fitted video rect (computed from the reported frame resolution +
 * rotation), so normalized coordinates always hit the intended point on the
 * remote screen — taps on the letterbox bars are ignored.
 *
 * Lifecycle handled explicitly:
 *  - init() the renderer with the shared EGL context in the AndroidView factory;
 *  - add/remove the track sink in a DisposableEffect keyed on the track;
 *  - release() the renderer when the composable leaves composition.
 */
@Composable
fun RemoteVideoSurface(
    eglBase: EglBase,
    track: VideoTrack?,
    onTap: (nx: Float, ny: Float) -> Unit,
    onLongPress: (nx: Float, ny: Float) -> Unit,
    onSwipe: (x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var firstFrame by remember { mutableStateOf(false) }

    // Last reported frame geometry, for aspect-correct touch mapping.
    var frameW by remember { mutableStateOf(0) }
    var frameH by remember { mutableStateOf(0) }
    var frameRot by remember { mutableStateOf(0) }

    // Reset the loading state whenever the track identity changes (new/lost stream).
    LaunchedEffect(track) { firstFrame = false }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() { firstFrame = true }
                        override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                            frameW = w; frameH = h; frameRot = rotation
                        }
                    })
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    renderer = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Touch overlay. Normalized coords are computed against the FITTED video
        // rect (not the whole view), so different resolutions/aspect ratios map
        // correctly. Long-press = press and hold.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(frameW, frameH, frameRot) {
                    fun Offset.normalized(): Offset? {
                        val vw = size.width.toFloat().coerceAtLeast(1f)
                        val vh = size.height.toFloat().coerceAtLeast(1f)
                        val rect = fittedRect(vw, vh, frameW, frameH, frameRot)
                            ?: return Offset(x / vw, y / vh) // no frame info yet: whole view
                        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return null
                        return Offset(
                            ((x - rect.left) / rect.width()).coerceIn(0f, 1f),
                            ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
                        )
                    }
                    detectTapGestures(
                        onTap = { off -> off.normalized()?.let { onTap(it.x, it.y) } },
                        onLongPress = { off -> off.normalized()?.let { onLongPress(it.x, it.y) } }
                    )
                }
                .pointerInput(frameW, frameH, frameRot) {
                    fun Offset.normalized(): Offset? {
                        val vw = size.width.toFloat().coerceAtLeast(1f)
                        val vh = size.height.toFloat().coerceAtLeast(1f)
                        val rect = fittedRect(vw, vh, frameW, frameH, frameRot)
                            ?: return Offset(x / vw, y / vh)
                        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return null
                        return Offset(
                            ((x - rect.left) / rect.width()).coerceIn(0f, 1f),
                            ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
                        )
                    }
                    var start = Offset.Zero
                    var current = Offset.Zero
                    var startMs = 0L
                    detectDragGestures(
                        onDragStart = { start = it; current = it; startMs = System.currentTimeMillis() },
                        onDrag = { change, drag -> current += drag; change.consume() },
                        onDragEnd = {
                            val s = start.normalized() ?: return@detectDragGestures
                            val e = current.normalized() ?: return@detectDragGestures
                            val ms = (System.currentTimeMillis() - startMs).coerceIn(50, 2000)
                            onSwipe(s.x, s.y, e.x, e.y, ms)
                        }
                    )
                }
        )

        if (!firstFrame) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Box(Modifier.height(12.dp))
                Text(
                    if (track == null) "Waiting for remote screen…" else "Connecting video…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null && track != null) track.addSink(r)
        onDispose { if (r != null && track != null) track.removeSink(r) }
    }
    DisposableEffect(Unit) {
        onDispose { renderer?.release() }
    }
}

/** Fitted video rect within a view of [viewW]x[viewH], honoring frame rotation. */
private fun fittedRect(viewW: Float, viewH: Float, frameW: Int, frameH: Int, rotation: Int): RectF? {
    if (frameW <= 0 || frameH <= 0) return null
    val swapped = rotation == 90 || rotation == 270
    val fw = if (swapped) frameH.toFloat() else frameW.toFloat()
    val fh = if (swapped) frameW.toFloat() else frameH.toFloat()
    val scale = minOf(viewW / fw, viewH / fh)
    val w = fw * scale
    val h = fh * scale
    return RectF((viewW - w) / 2f, (viewH - h) / 2f, (viewW + w) / 2f, (viewH + h) / 2f)
}
