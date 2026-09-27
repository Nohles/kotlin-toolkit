@file:OptIn(ExperimentalReadiumApi::class, InternalReadiumApi::class)

package org.readium.r2.navigator.epub

import android.net.Uri
import android.webkit.WebResourceRequest
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.css.ReadiumCss
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpContainer
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpTry
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WebViewServerTest {

    @Test
    fun `serves a remote jpeg with its manifest media type and bytes`() {
        val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x00, 0x01, 0x02)
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/OPS/images/title.jpg") { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/jpeg")
                exchange.sendResponseHeaders(200, image.size.toLong())
                if (exchange.requestMethod != "HEAD") {
                    exchange.responseBody.use { it.write(image) }
                } else {
                    exchange.responseBody.close()
                }
            }
            start()
        }

        try {
            val baseUrl = AbsoluteUrl("http://127.0.0.1:${server.address.port}/manifest.json")!!
            val manifest = Manifest(
                metadata = Metadata(localizedTitle = LocalizedString("test")),
                links = listOf(Link(href = baseUrl, rels = setOf("self"))),
                readingOrder = listOf(
                    Link(href = Href("OPS/title.xhtml")!!, mediaType = MediaType.XHTML)
                ),
                resources = listOf(
                    Link(href = Href("OPS/images/title.jpg")!!, mediaType = MediaType("image/jpeg")!!)
                ),
            )
            val container = HttpContainer(
                baseUrl = baseUrl,
                entries = (manifest.readingOrder + manifest.resources).map { it.url() }.toSet(),
                client = DefaultHttpClient(
                    callback = object : DefaultHttpClient.Callback {
                        override suspend fun onStartRequest(request: HttpRequest): HttpTry<HttpRequest> =
                            Try.success(
                                if (request.method == HttpRequest.Method.HEAD) {
                                    request.buildUpon().apply { method = HttpRequest.Method.GET }.build()
                                } else {
                                    request
                                }
                            )
                    }
                ),
            )
            val publication = Publication.Builder(manifest, container).build()
            val loadErrors = mutableListOf<String>()
            val webViewServer = WebViewServer(
                application = RuntimeEnvironment.getApplication(),
                publication = publication,
                servedAssets = emptyList(),
                disableSelectionWhenProtected = false,
                onResourceLoadFailed = { _, error -> loadErrors += error.toString() },
            )

            val response = webViewServer.shouldInterceptRequest(
                TestWebResourceRequest(Uri.parse("https://readium_package/OPS/images/title.jpg")),
                ReadiumCss(assetsBaseHref = WebViewServer.assetsBaseHref),
            )

            assertEquals("image/jpeg", response?.mimeType)
            assertEquals(image.toList(), response?.data?.readBytes()?.toList(), loadErrors.joinToString())

            val rangedResponse = webViewServer.shouldInterceptRequest(
                TestWebResourceRequest(
                    uri = Uri.parse("https://readium_package/OPS/images/title.jpg"),
                    headers = mapOf("Range" to "bytes=0-2"),
                ),
                ReadiumCss(assetsBaseHref = WebViewServer.assetsBaseHref),
            )

            assertEquals(206, rangedResponse?.statusCode)
            assertEquals("bytes 0-2/5", rangedResponse?.responseHeaders?.get("Content-Range"))
            assertEquals(image.take(3).toList(), rangedResponse?.data?.readBytes()?.toList(), loadErrors.joinToString())
        } finally {
            server.stop(0)
        }
    }

    private class TestWebResourceRequest(
        private val uri: Uri,
        private val headers: Map<String, String> = emptyMap(),
    ) : WebResourceRequest {
        override fun getUrl(): Uri = uri
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = headers
    }
}
