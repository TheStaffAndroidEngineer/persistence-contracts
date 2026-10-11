package staff.android.persistence

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageLifetimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsWriteIsVisibleThroughTheSamePerFileOwner() = runBlocking {
        val first = SortPreference(context)
        first.set(SavedSort.Title)
        assertEquals(SavedSort.Title, SortPreference(context).sort.first())
        first.set(SavedSort.Latest)
    }

    @Test fun cacheMissAndCleanupCannotDeleteAnUnrelatedFile() = runBlocking {
        val cache = ThumbnailFiles(context)
        cache.removeUnreferenced(emptySet())
        val first = cache.write(byteArrayOf(1,2,3))
        assertEquals(first,cache.write(byteArrayOf(1,2,3)))
        val second = cache.write(byteArrayOf(4,5,6))
        val directory = File(context.cacheDir,"chapter12-thumbnails")
        val unrelated = File(directory,"leave-this.txt").apply { writeText("other content") }
        val abandoned = File(directory,"staging-interrupted.tmp").apply { writeText("partial") }
        assertArrayEquals(byteArrayOf(1,2,3),cache.read(first))
        assertEquals(2,cache.removeUnreferenced(setOf(first)))
        assertTrue(unrelated.exists());assertFalse(abandoned.exists())
        assertNull(cache.read(second))
        File(directory,first).delete() // Simulate cache eviction by the environment.
        assertNull(cache.read(first))
        try { cache.read("../leave-this.txt");fail("path must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals("other content",unrelated.readText())
        unrelated.delete()
        Unit
    }
}
