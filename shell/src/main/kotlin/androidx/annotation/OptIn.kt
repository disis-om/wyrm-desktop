package androidx.annotation

import kotlin.reflect.KClass

/** Wyrm Desktop: androidx.annotation's OptIn, as the shared TrailLooks file uses it. */
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class OptIn(vararg val markerClass: KClass<out Annotation>)
