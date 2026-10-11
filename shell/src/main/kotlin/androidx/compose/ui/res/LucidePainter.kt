package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import org.xml.sax.InputSource

/**
 * Wyrm Desktop: Android's painterResource(id) for the shared files. The ids are
 * com.composables.icons.lucide.R.drawable, generated at build time from the
 * same icons-lucide-android AAR Wyrm Android uses; the vector XML is parsed
 * as it is, so every icon is identical.
 */
@Composable
fun painterResource(id: Int): Painter {
    val density = LocalDensity.current
    val vector = remember(id, density) { LucideVectors.load(id, density) }
    return rememberVectorPainter(vector)
}

internal object LucideVectors {
    private val names: List<String> by lazy {
        javaClass.getResourceAsStream("/lucide/index.txt")!!.bufferedReader().readLines()
    }
    private val cache = HashMap<Int, ImageVector>()

    fun load(id: Int, density: androidx.compose.ui.unit.Density): ImageVector = synchronized(cache) {
        cache.getOrPut(id) {
            val name = names[id]
            javaClass.getResourceAsStream("/lucide/$name.xml")!!.use {
                loadXmlImageVector(InputSource(it), density)
            }
        }
    }
}
