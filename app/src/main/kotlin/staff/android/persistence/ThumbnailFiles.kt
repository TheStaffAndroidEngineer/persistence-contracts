package staff.android.persistence

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Only expendable bytes in this feature's private cache directory.
class ThumbnailFiles(context: Context) {
    private val directory = File(context.cacheDir, "chapter12-thumbnails")
    private companion object { val lock = Mutex() }
    private val keyPattern = Regex("[a-f0-9]{64}\\.thumb")

    suspend fun write(bytes: ByteArray): String = lock.withLock {
        require(bytes.size <= 256 * 1024)
        val owned = bytes.copyOf()
        withContext(Dispatchers.IO) {
            check(directory.isDirectory || directory.mkdirs())
            val hash = MessageDigest.getInstance("SHA-256").digest(owned)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val key = "$hash.thumb"
            val staging = File.createTempFile("staging-", ".tmp", directory)
            try {
                staging.outputStream().use { it.write(owned) }
                Files.move(staging.toPath(), File(directory,key).toPath(), ATOMIC_MOVE)
            } finally { staging.delete() }
            key
        }
    }

    suspend fun read(key: String): ByteArray? = lock.withLock {
        require(keyPattern.matches(key))
        withContext(Dispatchers.IO) {
            val file = File(directory,key)
            try { file.readBytes() }
            catch (failure: FileNotFoundException) {
                if (!file.exists()) null else throw failure
            }
        }
    }

    // The owner supplies keys still useful to its current cache policy.
    // Missing bytes are always a cache miss, never a missing user note.
    suspend fun removeUnreferenced(retain: Set<String>): Int = lock.withLock {
        require(retain.all(keyPattern::matches))
        withContext(Dispatchers.IO) {
            if (!directory.exists()) return@withContext 0
            val files = directory.listFiles() ?: throw IOException("Cannot enumerate cache")
            var removed = 0
            for (file in files) {
                val abandonedStaging = file.name.startsWith("staging-") &&
                    file.name.endsWith(".tmp")
                val obsolete = keyPattern.matches(file.name) && file.name !in retain
                if (file.isFile && (abandonedStaging || obsolete)) {
                    if (file.delete()) removed++
                    else if (file.exists()) throw IOException("Cannot delete cache entry")
                }
            }
            removed
        }
    }
}
