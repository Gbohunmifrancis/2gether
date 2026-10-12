package app.heartpact

import app.heartpact.album.AlbumQueue
import app.heartpact.album.LocalPhoto
import app.heartpact.album.SyncEngine
import app.heartpact.data.HeartpactApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList

/** The sync engine against a scripted API and storage: batching, statuses, finishing, retries. */
class SyncEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val completes = CopyOnWriteArrayList<JsonObject>()
    private val uploads = CopyOnWriteArrayList<RecordedRequest>()
    private val pending = CopyOnWriteArrayList<JsonObject>()
    private lateinit var queue: AlbumQueue
    private lateinit var engine: SyncEngine

    private val doneId = "00000000-0000-0000-0000-00000000d0e0"
    private val fullId = "00000000-0000-0000-0000-0000000f0110"
    private val brokenId = "00000000-0000-0000-0000-00000b40ce00"

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path == "/api/memories/sync" -> {
                        val items = json.parseToJsonElement(request.body.readUtf8()).jsonObject["items"]!!.jsonArray.map { it.jsonObject["clientId"]!!.jsonPrimitive.content }
                        val results = items.joinToString(",") { id ->
                            when (id) {
                                doneId -> """{"clientId":"$id","status":"done","memoryId":"m-$id"}"""
                                fullId -> """{"clientId":"$id","status":"rejected","error":{"code":"memories.quota_exceeded","message":"Your shared album is full."}}"""
                                else -> """{"clientId":"$id","status":"upload","memoryId":"m-$id","upload":{"memoryId":"m-$id","uploadUrl":"${server.url("/upload/$id")}","method":"PUT","headers":{"Content-Type":"image/jpeg"},"thumbnail":{"uploadUrl":"${server.url("/thumb/$id")}","headers":{"Content-Type":"image/jpeg"},"maxBytes":262144}}}"""
                            }
                        }
                        MockResponse().setBody("""{"items":[$results],"usage":{"count":1,"usedBytes":1,"limitBytes":2}}""")
                    }
                    path == "/api/memories/sync/complete" -> {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        completes += body
                        val items = body["memoryIds"]!!.jsonArray.joinToString(",") { """{"memoryId":"${it.jsonPrimitive.content}","status":"ready"}""" }
                        MockResponse().setBody("""{"items":[$items]}""")
                    }
                    path == "/api/memories/sync/pending" -> { pending += json.parseToJsonElement(request.body.readUtf8()).jsonObject; MockResponse().setResponseCode(204) }
                    path.startsWith("/upload/") -> { uploads += request; if (path.endsWith(brokenId)) MockResponse().setResponseCode(500) else MockResponse() }
                    path.startsWith("/thumb/") -> { uploads += request; MockResponse() }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        queue = AlbumQueue(folder.newFolder("album"))
        // The API client carries a token, as in the app; the upload client must not.
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor { it.proceed(it.request().newBuilder().header("Authorization", "Bearer secret").build()) }.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(HeartpactApi::class.java)
        engine = SyncEngine(api, OkHttpClient(), queue, json)
    }

    @After fun tearDown() = server.shutdown()

    private suspend fun photo(id: String) {
        queue.photosDir.resolve("$id.jpg").writeBytes(ByteArray(64) { 7 })
        queue.thumbsDir.resolve("$id.jpg").writeBytes(ByteArray(16) { 3 })
        queue.add(LocalPhoto(id, "$id.jpg", "$id.jpg", "image/jpeg", 64, null, 0))
    }

    @Test fun `a sync of 55 photos goes in two batches and the partner hears once`() = runTest {
        val ids = (1..52).map { "00000000-0000-0000-0000-%012d".format(it) } + listOf(doneId, fullId, brokenId)
        ids.forEach { photo(it) }

        val outcome = engine.run()

        assertEquals("all but the refused and the broken upload", 53, outcome.synced)
        assertEquals(1, outcome.refused)
        assertTrue(outcome.albumFull)
        assertNull(outcome.stoppedBy)
        assertEquals("the broken upload waits for next time", listOf(brokenId), queue.waiting.map { it.clientId })
        assertEquals("the refused one stays, with its reason", "memories.quota_exceeded", queue.photos.value.single { it.clientId == fullId }.errorCode)
        assertTrue("synced photos free their space", queue.photosDir.listFiles()!!.map { it.nameWithoutExtension }.toSet() == setOf(brokenId, fullId))

        assertEquals(listOf(false, true), completes.map { it["finished"]!!.jsonPrimitive.content.toBoolean() })
        assertTrue("signed links never carry the sign-in token", uploads.none { it.getHeader("Authorization") != null })
        assertTrue(uploads.filter { it.path!!.startsWith("/upload/") }.all { it.getHeader("Content-Type") == "image/jpeg" })
        assertEquals("1", pending.last()["pendingCount"]!!.jsonPrimitive.content)
    }

    @Test fun `when the last batch has nothing new, an earlier photo carries the finished flag`() = runTest {
        photo(doneId)
        engine.run()
        assertEquals(1, completes.size)
        assertEquals("m-$doneId", completes.single()["memoryIds"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(true, completes.single()["finished"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `offline stops the sync and keeps every photo`() = runTest {
        photo("00000000-0000-0000-0000-000000000001")
        server.shutdown()
        val outcome = engine.run()
        assertEquals(1, outcome.left)
        assertTrue(outcome.stoppedBy != null)
    }
}
