package com.dogukan.toonceviri

import android.content.Context
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Okuyucu için yakınlaştırma:
 * - iki parmakla büyüt/küçült (1x – 4x), parmakların ortası sabit kalır
 * - çift dokunuşla 2x ↔ 1x (dokunulan nokta yerinde kalır)
 * - yakınken tek parmakla her yöne kaydır; içerik parmağı birebir takip eder
 * - liste en başta/sondayken ilk sayfanın üstü ve son sayfanın altı da görülebilir
 *
 * Dönüşüm sol üst köşeden yapılır: ekran = içerik × ölçek + kayma.
 * Dikey harekette önce liste kaydırılır, liste ucuna gelince görünüm kayar.
 */
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    resetKey: Any? = null,
    onTap: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    var fling by remember { mutableStateOf<Job?>(null) }

    // Orijinal/çeviri arasında geçince yakınlaştırma sıfırlanır
    LaunchedEffect(resetKey) {
        scale = 1f
        tx = 0f
        ty = 0f
    }

    fun clamp() {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        tx = tx.coerceIn(w - w * scale, 0f)
        ty = ty.coerceIn(h - h * scale, 0f)
    }

    /** Ekrandaki (cx, cy) noktası yerinde kalacak şekilde ölçeği değiştirir. */
    fun zoomAround(cx: Float, cy: Float, target: Float) {
        val s2 = target.coerceIn(1f, 4f)
        tx = cx - (cx - tx) * s2 / scale
        ty = cy - (cy - ty) * s2 / scale
        scale = s2
        clamp()
    }

    /** Dikey hareket: önce liste parmak hızında kaydırılır, liste ucundaysa görünüm kayar. */
    fun panY(dy: Float) {
        val want = -dy / scale
        val used = listState.dispatchRawDelta(want)
        val left = want - used
        ty += -left * scale
        clamp()
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { pos ->
                        if (scale > 1.01f) {
                            scale = 1f
                            tx = 0f
                            ty = 0f
                        } else {
                            zoomAround(pos.x, pos.y, 2f)
                        }
                    },
                )
            }
            .pointerInput(listState) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // Initial geçişte bakılır: hareketi listeden önce yakalamak için
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    fling?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragging = false
                    var moved = 0f
                    var multi = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            multi = true
                            val c = event.calculateCentroid(useCurrent = true)
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (c.isSpecified && zoom != 1f) zoomAround(c.x, c.y, scale * zoom)
                            tx += pan.x
                            clamp()
                            panY(pan.y)
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && scale > 1.01f && !multi) {
                            // Yakınken tek parmak: kaymayı biz yaparız (liste tek başına 2 kat hızlı kaydırırdı)
                            val ch = pressed[0]
                            val d: Offset = ch.positionChange()
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if (!dragging) {
                                moved += d.getDistance()
                                if (moved > slop) dragging = true
                            }
                            if (dragging) {
                                tx += d.x
                                clamp()
                                panY(d.y)
                                ch.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // Yakınken parmağı hızlı bırakınca liste kaymaya devam etsin
                    if (dragging && scale > 1.01f) {
                        val vy = tracker.calculateVelocity().y
                        if (abs(vy) > 300f) {
                            val s = scale
                            fling = scope.launch {
                                listState.animateScrollBy(
                                    -vy / s * 0.3f,
                                    tween(durationMillis = 500, easing = LinearOutSlowInEasing),
                                )
                            }
                        }
                    }
                    if (scale < 1.01f) {
                        scale = 1f
                        tx = 0f
                        ty = 0f
                    }
                }
            },
    ) {
        content(
            Modifier.graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationX = tx
                translationY = ty
            }
        )
    }
}

/**
 * Sayfa resmi için Coil isteği. Dosya düzenlenince (aynı adla yeniden yazılınca)
 * eski görüntü önbellekten gelmesin diye anahtara değişiklik zamanı ve boyutu eklenir.
 */
fun pageRequest(ctx: Context, f: File): ImageRequest {
    val key = "${f.path}#${f.lastModified()}#${f.length()}"
    return ImageRequest.Builder(ctx)
        .data(f)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .build()
}
