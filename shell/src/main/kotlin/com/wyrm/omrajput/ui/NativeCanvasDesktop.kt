package com.wyrm.omrajput.ui

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

/** Desktop uses Skia directly and never Android HWUI's software workaround. */
internal fun WyrmMeshCanvas.draw(target: Canvas, width: Float, height: Float,
    vertices: Int, indices: Int, xy: FloatArray, colors: IntArray,
    order: ShortArray, paint: android.graphics.Paint) {
    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 ||
        vertices <= 0 || vertices > 30000 || vertices > xy.size / 2 || vertices > colors.size ||
        indices <= 0 || indices > order.size || indices % 3 != 0) return
    for (i in 0 until vertices * 2) if (!xy[i].isFinite()) return
    for (i in 0 until indices) if ((order[i].toInt() and 0xFFFF) >= vertices) return
    target.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, vertices * 2,
        xy, 0, null, 0, colors, 0, order, 0, indices, paint)
}

/*
 * Wyrm Desktop: Android's Canvas.drawVertices, as the shared live Wyrm skin
 * preview calls it (ui/WyrmSkins.kt WyrmSkinLive). On the desktop the native
 * canvas is Skia's, whose own drawVertices takes whole arrays, so this cuts the
 * buffers to the counts the engine filled and draws them in their vertex colours.
 */
internal fun Canvas.drawVertices(
    mode: android.graphics.Canvas.VertexMode,
    vertexCount: Int,
    verts: FloatArray,
    vertOffset: Int,
    texs: FloatArray?,
    texOffset: Int,
    colors: IntArray?,
    colorOffset: Int,
    indices: ShortArray?,
    indexOffset: Int,
    indexCount: Int,
    paint: android.graphics.Paint,
) {
    val points = vertexCount / 2
    if (points <= 0) return
    val skiaMode = when (mode) {
        android.graphics.Canvas.VertexMode.TRIANGLES -> VertexMode.TRIANGLES
        android.graphics.Canvas.VertexMode.TRIANGLE_STRIP -> VertexMode.TRIANGLE_STRIP
        android.graphics.Canvas.VertexMode.TRIANGLE_FAN -> VertexMode.TRIANGLE_FAN
    }
    val positions = verts.copyOfRange(vertOffset, vertOffset + points * 2)
    val vertexColours = colors?.copyOfRange(colorOffset, colorOffset + points)
    val tex = texs?.copyOfRange(texOffset, texOffset + points * 2)
    val order = indices?.copyOfRange(indexOffset, indexOffset + indexCount)
    // White paint under MODULATE: each vertex keeps exactly its own colour and alpha.
    val skia = Paint().apply {
        color = (paint.alpha shl 24) or 0x00FFFFFF
        isAntiAlias = paint.isAntiAlias
    }
    drawVertices(skiaMode, positions, vertexColours, tex, order, BlendMode.MODULATE, skia)
    skia.close()
}
