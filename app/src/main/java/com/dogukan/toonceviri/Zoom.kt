package com.dogukan.toonceviri

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import java.io.File
import android.content.Context
import coil.request.ImageRequest

/**
 * Okuyucu için yakınlaştırma:
 * - iki parmakla büyüt/küçült (1x – 4x)
 * - çift dokunuşla 2x ↔ 1x (dokunulan yere doğru)
 * - yakınken tek parmakla yana kaydır; aşağı-yukarı kaydırmayı liste yapmaya devam eder
 */
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }

    fun clamp() {
        val max = (scale - 1f) * width / 2f
        offsetX = offsetX.coerceIn(-max, max)
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offsetX = 0f
                        } else {
                            scale = 2f
                            // Dokunulan nokta yerinde kalsın
                            offsetX = (width / 2f - pos.x) * (scale - 1f)
                            clamp()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        // Initial geçişte bakılır: iki parmak hareketini listeden önce yakalamak için
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            offsetX += event.calculatePan().x
                            clamp()
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.01f && pressed == 1) {
                            // Yatay hareketi biz alırız, dikeyi liste kaydırır (olay tüketilmez)
                            offsetX += event.changes.first { it.pressed }.positionChange().x
                            clamp()
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.01f) {
                        scale = 1f
                        offsetX = 0f
                    }
                }
            },
    ) {
        content(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
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
