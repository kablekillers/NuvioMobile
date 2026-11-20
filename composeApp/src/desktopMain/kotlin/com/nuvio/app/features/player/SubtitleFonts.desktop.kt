package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.nuvio.app.core.storage.DesktopPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

internal actual object SubtitleFontStorage {
    actual fun defaultFamily(): FontFamily = FontFamily.SansSerif

    private fun file(name: String): File {
        require(name == "fonts.json" || name.matches(Regex("custom-[a-f0-9-]{36}")))
        return File(DesktopPaths.dir("subtitle-fonts"), name)
    }

    actual fun read(name: String): ByteArray? = file(name).takeIf {
        it.isFile && it.length() <= MaxSubtitleFontBytes
    }?.readBytes()

    actual fun write(name: String, bytes: ByteArray) {
        val target = file(name)
        target.parentFile?.mkdirs()
        val temporary = File(target.path + ".tmp")
        temporary.writeBytes(bytes)
        if (target.exists()) target.delete()
        check(temporary.renameTo(target))
    }

    actual fun remove(name: String) {
        file(name).delete()
    }

    actual fun fontFamily(id: String): FontFamily? =
        file(id).takeIf { it.isFile }?.let { FontFamily(Font(it)) }
}

/** Opens a Windows file picker for a .ttf or .otf font. */
@Composable
internal actual fun rememberSubtitleFontImporter(onResult: (Result<SubtitleFont>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    return {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Choose a subtitle font", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.endsWith(".ttf", true) || name.endsWith(".otf", true) }
                    file = "*.ttf;*.otf"
                    isVisible = true
                }
                val name = dialog.file ?: return@withContext null
                File(dialog.directory, name)
            } ?: return@launch
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    require(picked.length() <= MaxSubtitleFontBytes)
                    SubtitleFonts.import(picked.name, picked.readBytes())
                }
            }
            latestResult.value(result)
        }
    }
}
