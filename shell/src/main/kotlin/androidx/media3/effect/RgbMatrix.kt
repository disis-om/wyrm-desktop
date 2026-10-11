package androidx.media3.effect

/** Wyrm Desktop: Media3's colour-matrix video effect type, named by the shared TrailLooks (no video here). */
fun interface RgbMatrix : androidx.media3.common.Effect {
    fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray
}
