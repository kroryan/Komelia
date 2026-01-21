package snd.komelia.ui.reader.balloon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import snd.komga.client.book.KomgaBookId
import java.io.File

private const val INDEX_DIR_NAME = "balloon_index"

actual fun createBalloonIndexStore(cacheDir: Path?): BalloonIndexStore? {
    val baseDir = cacheDir?.toString() ?: return null
    return DiskBalloonIndexStore(File(baseDir, INDEX_DIR_NAME))
}

private class DiskBalloonIndexStore(
    private val baseDir: File,
) : BalloonIndexStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(bookId: KomgaBookId): StoredBalloonIndex? = withContext(Dispatchers.IO) {
        val file = fileFor(bookId)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString<StoredBalloonIndex>(file.readText()) }.getOrNull()
    }

    override suspend fun save(bookId: KomgaBookId, index: StoredBalloonIndex) = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        val file = fileFor(bookId)
        file.writeText(json.encodeToString(index))
    }

    override suspend fun clear(bookId: KomgaBookId) = withContext(Dispatchers.IO) {
        fileFor(bookId).delete()
        Unit
    }

    private fun fileFor(bookId: KomgaBookId): File {
        return File(baseDir, "${bookId.value}.json")
    }
}
