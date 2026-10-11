package app.heartpact

import app.heartpact.album.AlbumQueue
import app.heartpact.album.LocalPhoto
import app.heartpact.data.MemoryUsage
import app.heartpact.ui.album.AlbumText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlbumQueueTest {
    @get:Rule val folder = TemporaryFolder()

    private suspend fun AlbumQueue.put(id: String, size: Long = 100): LocalPhoto {
        photosDir.resolve("$id.jpg").writeBytes(ByteArray(size.toInt()))
        thumbsDir.resolve("$id.jpg").writeBytes(ByteArray(8))
        return LocalPhoto(id, "$id.jpg", "$id.jpg", "image/jpeg", size, null, 0).also { add(it) }
    }

    @Test fun `the queue survives a restart and refused photos stop waiting`() = runTest {
        val root = folder.newFolder("album")
        val queue = AlbumQueue(root)
        queue.put("a", 100); queue.put("b", 250); queue.put("c", 50)
        queue.rejected("b", "memories.too_large", "Too large")

        val reopened = AlbumQueue(root)
        assertEquals(listOf("a", "b", "c"), reopened.photos.value.map { it.clientId })
        assertEquals(listOf("a", "c"), reopened.waiting.map { it.clientId })
        assertEquals(150, reopened.waitingBytes)

        reopened.retry("b")
        assertEquals(400, reopened.waitingBytes)
    }

    @Test fun `synced and deleted photos free their files, sign-out clears everything`() = runTest {
        val queue = AlbumQueue(folder.newFolder("album"))
        val a = queue.put("a"); queue.put("b"); queue.put("c")
        queue.synced("a")
        assertFalse(queue.file(a).exists())
        assertFalse(queue.thumb(a)!!.exists())
        queue.delete("b")
        assertEquals(listOf("c"), queue.photos.value.map { it.clientId })

        queue.clear()
        assertTrue(queue.photos.value.isEmpty())
        assertTrue(queue.photosDir.listFiles()!!.isEmpty())
        assertTrue(queue.thumbsDir.listFiles()!!.isEmpty())
    }

    @Test fun `album wording`() {
        assertEquals("812 B", AlbumText.size(812))
        assertEquals("4.5 MB", AlbumText.size(4_718_592))
        assertEquals("812 MB", AlbumText.size(812L * 1024 * 1024))
        assertEquals("5 GB", AlbumText.size(5L * 1024 * 1024 * 1024))
        assertEquals("1 photo", AlbumText.photos(1))

        val waiting = (1..3).map { LocalPhoto("$it", "", null, "image/jpeg", 40, null, 0) }
        assertEquals(AlbumText.After(180, 200, 2), AlbumText.after(MemoryUsage(1, 100, 200), waiting))
        assertEquals(3, AlbumText.after(null, waiting).fits)

        assertEquals("3 photos added to your shared album.", AlbumText.outcome(3, 0, 0, false, null))
        assertEquals("2 photos added to your shared album. Your shared album is full, so some stayed on this phone.", AlbumText.outcome(2, 1, 1, true, null))
        assertEquals("1 photo still waiting: offline", AlbumText.outcome(0, 0, 1, false, "offline"))
    }
}
