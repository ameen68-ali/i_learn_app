package com.ilearn.app

import com.ilearn.app.tutor.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProviderTransportTest {
    private val certificate = run {
        // okhttp-tls 4.12's test certificate encoder uses the process locale for DER time.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        } finally { java.util.Locale.setDefault(original) }
    }
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private fun server(http2: Boolean = false) = MockWebServer().apply {
        // setChunkedBody models HTTP/1.1 wire chunks, not HTTP/2 DATA frames.
        protocols = if (http2) listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1) else listOf(okhttp3.Protocol.HTTP_1_1)
        useHttps(serverTls.sslSocketFactory(), false); start()
    }
    private fun transport() = ProviderTransport(OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
    private fun profile(server: MockWebServer) = ProviderProfile(name = "Fixture", format = ApiFormat.OPENAI_CHAT, endpoint = server.url("/v1").toString(), model = "fixture")

    @Test fun fragmentedSseAndArabicTextAreReassembled() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\",\"content\":null,\"refusal\":null}}],\"error\":null}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"العربية\"}}]}\n\ndata: [DONE]\n\n", 7))
            val partials = mutableListOf<String>()
            val result = transport().generate(profile(server), "fixture-key", "Evidence", listOf(TutorMessage("user", "Explain"))) { partials.add(it) }
            assertEquals("Hello العربية", result.text)
            assertEquals(2, partials.size)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            assertFalse(request.body.readUtf8().contains("fixture-key"))
        } finally { server.shutdown() }
    }

    @Test fun redirectsDoNotForwardTheCredential() = runBlocking {
        val first = server(); val second = server()
        try {
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/steal")))
            val failed = runCatching { transport().generate(profile(first), "fixture-key", "Evidence", listOf(TutorMessage("user", "Explain"))) {} }
            assertTrue(failed.isFailure)
            assertEquals(1, first.requestCount)
            assertEquals(0, second.requestCount)
        } finally { first.shutdown(); second.shutdown() }
    }

    @Test fun httpTwoEventStreamUsesDataFramesWithoutChunkMarkers() = runBlocking {
        val server = server(http2 = true)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"HTTP2 answer\"}}]}\n\ndata: [DONE]\n\n"))
            assertEquals("HTTP2 answer", transport().generate(profile(server), "fixture-key", "Evidence", listOf(TutorMessage("user", "Explain"))) {}.text)
        } finally { server.shutdown() }
    }

    @Test fun aStreamWithoutACompletionEventIsRejected() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Unfinished answer\"}}]}\n\n"))
            assertTrue(runCatching { transport().generate(profile(server), "fixture-key", "Evidence", listOf(TutorMessage("user", "Explain"))) {} }.isFailure)
        } finally { server.shutdown() }
    }

    @Test fun cancellationClosesAnUnansweredRequest() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            withTimeout(5000) {
                val job = launch { transport().generate(profile(server), "fixture-key", "Evidence", listOf(TutorMessage("user", "Explain"))) {} }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        } finally { server.shutdown() }
    }
}
