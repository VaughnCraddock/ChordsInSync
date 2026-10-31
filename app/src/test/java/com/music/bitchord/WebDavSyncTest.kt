package com.music.bitchord

import com.music.bitchord.data.sync.EffectiveDirection
import com.music.bitchord.data.sync.SyncDirection
import com.music.bitchord.data.sync.SyncEntry
import com.music.bitchord.data.sync.SyncFileStamp
import com.music.bitchord.data.sync.SyncIndexEntry
import com.music.bitchord.data.sync.SyncAction
import com.music.bitchord.data.sync.SyncPlanner
import com.music.bitchord.data.sync.webdav.WebDavPaths
import com.music.bitchord.data.sync.webdav.WebDavProvider
import com.music.bitchord.data.sync.webdav.WebDavWritabilityProbe
import com.music.bitchord.data.webdav.WebDavClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebDAV sync engine's testable surface: PROPFIND answers carrying the
 * sync stamps (size, last-modified, etag), the path<->URL mapping, the
 * provider against a mock server, the writability probe's verdicts, and
 * the planner reading a WebDAV-shaped listing (etag beats clock).
 */
class WebDavSyncTest {

    // The RFC's own example moment, in all three HTTP-date shapes.
    private val exampleMs = 784_111_777_000L

    @Test
    fun parseHttpDate_readsAllThreeShapes() {
        assertEquals(exampleMs, WebDavClient.parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertEquals(exampleMs, WebDavClient.parseHttpDate("Sunday, 06-Nov-94 08:49:37 GMT"))
        assertEquals(exampleMs, WebDavClient.parseHttpDate("Sun Nov  6 08:49:37 1994"))
        assertNull(WebDavClient.parseHttpDate("not a date"))
        assertNull(WebDavClient.parseHttpDate(""))
    }

    @Test
    fun parseMultistatus_carriesTheSyncStamps() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/</d:href>
    <d:propstat><d:prop>
      <d:displayname>dav</d:displayname>
      <d:resourcetype><d:collection/></d:resourcetype>
    </d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/song.mp3</d:href>
    <d:propstat><d:prop>
      <d:displayname>song.mp3</d:displayname>
      <d:resourcetype/>
      <d:getcontenttype>audio/mpeg</d:getcontenttype>
      <d:getcontentlength>12345</d:getcontentlength>
      <d:getlastmodified>Sun, 06 Nov 1994 08:49:37 GMT</d:getlastmodified>
      <d:getetag>"abc123"</d:getetag>
    </d:prop></d:propstat>
  </d:response>
</d:multistatus>
""".trimIndent()
        val entries = WebDavClient.parseMultistatus(xml, "https://dav.example.com/dav")
        val song = entries.single()
        assertEquals(12345L, song.size)
        assertEquals(exampleMs, song.lastModifiedMs)
        assertEquals("\"abc123\"", song.etag)
    }

    @Test
    fun parseStat_keepsTheAskedOfEntry() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/song.mp3</d:href>
    <d:propstat><d:prop>
      <d:displayname>song.mp3</d:displayname>
      <d:resourcetype/>
      <d:getcontentlength>777</d:getcontentlength>
      <d:getetag>"zzz"</d:getetag>
    </d:prop></d:propstat>
  </d:response>
</d:multistatus>
""".trimIndent()
        val entry = WebDavClient.parseStat(xml, "https://dav.example.com/dav/song.mp3")
        assertEquals(777L, entry?.size)
        assertEquals("\"zzz\"", entry?.etag)
        assertNull(WebDavClient.parseStat("", "https://dav.example.com/dav/song.mp3"))
    }

    @Test
    fun paths_roundTripThroughUrls() {
        val root = "https://dav.example.com/music"
        val path = "Rock/Live Sets/track #1.flac"
        val url = WebDavPaths.urlForPath(root, path)
        assertEquals("https://dav.example.com/music/Rock/Live%20Sets/track%20%231.flac", url)
        assertEquals(path, WebDavPaths.pathForUrl(root, url))
        assertEquals("song.mp3", WebDavPaths.pathForUrl(root, "$root/song.mp3"))
    }

    @Test
    fun pathForUrl_refusesUrlsOutsideTheRoot() {
        val root = "https://dav.example.com/music"
        assertNull(WebDavPaths.pathForUrl(root, "https://dav.example.com/other/song.mp3"))
        assertNull(WebDavPaths.pathForUrl(root, "https://elsewhere.example.com/music/song.mp3"))
        assertNull(WebDavPaths.pathForUrl(root, root))
    }

    @Test
    fun labelFor_namesFoldersFromTheServerRoot() {
        val root = "https://dav.example.com/music"
        assertEquals("music", WebDavPaths.labelFor(root, root))
        assertEquals("music/Live", WebDavPaths.labelFor(root, "$root/Live"))
        assertEquals("music/Live/2026", WebDavPaths.labelFor(root, "$root/Live/2026"))
    }

    private fun multistatus(vararg rows: String): String = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
${rows.joinToString("\n")}
</d:multistatus>
""".trimIndent()

    private fun collection(href: String): String = """
  <d:response>
    <d:href>$href</d:href>
    <d:propstat><d:prop>
      <d:displayname>${href.trimEnd('/').substringAfterLast('/')}</d:displayname>
      <d:resourcetype><d:collection/></d:resourcetype>
    </d:prop></d:propstat>
  </d:response>""".trimIndent()

    private fun file(href: String, size: Long, etag: String): String = """
  <d:response>
    <d:href>$href</d:href>
    <d:propstat><d:prop>
      <d:displayname>${href.substringAfterLast('/')}</d:displayname>
      <d:resourcetype/>
      <d:getcontentlength>$size</d:getcontentlength>
      <d:getlastmodified>Sun, 06 Nov 1994 08:49:37 GMT</d:getlastmodified>
      <d:getetag>"$etag"</d:getetag>
    </d:prop></d:propstat>
  </d:response>""".trimIndent()

    @Test
    fun `provider lists audio recursively with stamps, and reads, writes, deletes`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val rootUrl = server.url("/dav").toString().trimEnd('/')
            val rootXml = multistatus(
                collection("/dav/"),
                file("/dav/song.mp3", 12345, "e1"),
                file("/dav/notes.txt", 10, "e9"),
                collection("/dav/Live/"),
            )
            val liveXml = multistatus(
                collection("/dav/Live/"),
                file("/dav/Live/take.flac", 999, "e2"),
            )
            val statXml = multistatus(file("/dav/Live/new.mp3", 5, "e3"))
            val methods = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    methods += "${request.method} ${request.path}"
                    return when {
                        request.method == "PUT" -> MockResponse().setResponseCode(201)
                        request.method == "DELETE" -> MockResponse().setResponseCode(204)
                        request.method == "GET" -> MockResponse().setResponseCode(200).setBody("hello")
                        request.method == "MKCOL" -> MockResponse().setResponseCode(201)
                        request.getHeader("Depth") == "0" -> MockResponse().setResponseCode(207).setBody(statXml)
                        request.path?.contains("Live") == true ->
                            MockResponse().setResponseCode(207).setBody(liveXml)
                        else -> MockResponse().setResponseCode(207).setBody(rootXml)
                    }
                }
            }
            val provider = WebDavProvider(rootUrl, "user", "pass")

            val listing = provider.list()
            // notes.txt is not audio: the local end could never see it, so
            // the remote end does not list it either.
            assertEquals(listOf("Live/take.flac", "song.mp3"), listing.map { it.path }.sorted())
            val song = listing.first { it.path == "song.mp3" }
            assertEquals(12345L, song.stamp.size)
            assertEquals(exampleMs, song.stamp.mtimeMs)
            assertEquals("\"e1\"", song.stamp.tag)

            val bytes = provider.read("song.mp3").use { it.readBytes() }
            assertEquals("hello", bytes.toString(Charsets.UTF_8))

            val written = provider.write("Live/new.mp3", "hello".byteInputStream(), 5, null)
            assertEquals(5L, written.stamp.size)
            assertEquals("\"e3\"", written.stamp.tag)
            assertTrue(methods.any { it.startsWith("PUT") && it.contains("new.mp3") })

            provider.delete("song.mp3")
            assertTrue(methods.any { it.startsWith("DELETE") && it.contains("song.mp3") })
        }
    }

    @Test
    fun `probe reads a refused write as read-only`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val root = server.url("/dav").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(false, WebDavWritabilityProbe.probe(root, "u", "p"))
        }
        MockWebServer().use { server ->
            server.start()
            val root = server.url("/dav").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(true, WebDavWritabilityProbe.probe(root, "u", "p"))
            server.takeRequest() // the probe PUT
            assertEquals("DELETE", server.takeRequest().method)
        }
        MockWebServer().use { server ->
            server.start()
            val root = server.url("/dav").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(500))
            assertNull(WebDavWritabilityProbe.probe(root, "u", "p"))
        }
    }

    @Test
    fun `planner follows the etag when size and clock stand still`() {
        // A re-tagged file on the server: same bytes' length, same mtime,
        // a new etag. Only the tag can see it — that is what it is for.
        val agreed = SyncIndexEntry(
            path = "a.mp3", localSize = 100, localMtimeMs = 1_000,
            remoteSize = 100, remoteMtimeMs = 1_000, remoteTag = "\"v1\"",
        )
        val local = listOf(SyncEntry("a.mp3", SyncFileStamp(100, 1_000)))
        val remote = listOf(SyncEntry("a.mp3", SyncFileStamp(100, 1_000, "\"v2\"")))
        val plan = SyncPlanner.plan(
            configured = SyncDirection.BIDIRECTIONAL,
            effective = EffectiveDirection.BIDIRECTIONAL,
            mirrorDeletes = false,
            local = local,
            remote = remote,
            index = mapOf("a.mp3" to agreed),
            conflictDate = "2026-10-30",
        )
        assertEquals(
            listOf(SyncAction(SyncAction.Kind.DOWNLOAD, "a.mp3")),
            plan.actions,
        )
    }
}
