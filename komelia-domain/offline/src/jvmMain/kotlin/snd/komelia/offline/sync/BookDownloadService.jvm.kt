package snd.komelia.offline.sync

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.delete
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import snd.jni.DesktopPlatform
import kotlin.io.path.createDirectories

internal actual suspend fun prepareOutput(
    downloadRoot: PlatformFile,
    serverName: String,
    libraryName: String,
    seriesName: String,
    bookFileName: String,
): Pair<PlatformFile, Sink> {

    val filePath = when (DesktopPlatform.Current) {
        DesktopPlatform.Windows -> downloadRoot.file.toPath()
            .resolve(serverName.removeIllegalWindowsPathChars("server"))
            .resolve(libraryName.removeIllegalWindowsPathChars("library"))
            .resolve(seriesName.removeIllegalWindowsPathChars("series"))
            .resolve(bookFileName.removeIllegalWindowsPathChars("book"))

        else -> downloadRoot.file.toPath()
            .resolve(serverName.sanitizePathSegment("server"))
            .resolve(libraryName.sanitizePathSegment("library"))
            .resolve(seriesName.sanitizePathSegment("series"))
            .resolve(bookFileName.sanitizePathSegment("book"))
    }

    filePath.parent.createDirectories()

    val kotlinxIoPath = kotlinx.io.files.Path(filePath.toString())
    return PlatformFile(filePath.toFile()) to SystemFileSystem.sink(kotlinxIoPath).buffered()
}


private val windowsReservedChars = "[<>:\"/|?*\u0000-\u001F]|[. ]$".toRegex()
private fun String.removeIllegalWindowsPathChars(fallback: String): String {
    val cleaned = this.replace(windowsReservedChars, "").trim()
    return cleaned.ifEmpty { fallback }
}

private val invalidPathChars = "[\\\\/:*?\"<>|]".toRegex()
private fun String.sanitizePathSegment(fallback: String): String {
    val trimmed = trim().replace(invalidPathChars, "_").replace(Regex("\\s+"), " ")
    return trimmed.ifEmpty { fallback }
}


internal actual suspend fun deleteFile(file: PlatformFile) {
    file.delete(false)
}
