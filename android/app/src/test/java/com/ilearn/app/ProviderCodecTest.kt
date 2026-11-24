package com.ilearn.app

import com.ilearn.app.tutor.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderCodecTest {
    private fun profile(format: ApiFormat) = ProviderProfile(name = "Test", format = format, endpoint = "https://example.test/v1", model = "test-model")
    @Test fun formatsUseSeparateRequestsAndNeverPutCredentialsIntoPrompts() {
        for (format in ApiFormat.values()) {
            val (url, body) = ProviderCodec.request(profile(format), "Evidence only", listOf(TutorMessage("user", "Explain")))
            assertTrue(url.startsWith("https://example.test/v1/"))
            assertFalse(body.toString().contains("api_key"))
            assertEquals(format == ApiFormat.GEMINI, body.has("contents"))
            assertEquals(format == ApiFormat.OPENAI_RESPONSES, body.has("instructions"))
        }
    }
    @Test fun protocolResponseParsing() {
        assertEquals("Hello", ProviderCodec.text(ApiFormat.OPENAI_CHAT, JSONObject("""{"choices":[{"delta":{"content":"Hello"}}]}"""), true))
        assertEquals("Hi", ProviderCodec.text(ApiFormat.OPENAI_RESPONSES, JSONObject("""{"type":"response.output_text.delta","delta":"Hi"}"""), true))
        assertEquals("Yes", ProviderCodec.text(ApiFormat.ANTHROPIC, JSONObject("""{"delta":{"type":"text_delta","text":"Yes"}}"""), true))
        assertEquals("Answer", ProviderCodec.text(ApiFormat.GEMINI, JSONObject("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private"},{"text":"Answer"}]}}]}"""), true))
    }
    @Test fun rejectUnsafeEndpoints() {
        for (url in listOf("http://example.test", "https://user:secret@example.test", "https://example.test?key=secret", "https://example.test/#fragment")) {
            assertTrue(runCatching { profile(ApiFormat.OPENAI_CHAT).copy(endpoint = url).validate() }.isFailure)
        }
    }
    @Test fun truncatedAnswersAreNotAcceptedAsComplete() {
        assertTrue(runCatching { ProviderCodec.text(ApiFormat.OPENAI_CHAT, JSONObject("""{"choices":[{"finish_reason":"length","message":{"content":"unfinished"}}]}"""), false) }.isFailure)
        assertTrue(runCatching { ProviderCodec.text(ApiFormat.ANTHROPIC, JSONObject("""{"stop_reason":"max_tokens","content":[{"text":"unfinished"}]}"""), false) }.isFailure)
        assertTrue(runCatching { ProviderCodec.text(ApiFormat.GEMINI, JSONObject("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"unfinished"}]}}]}"""), false) }.isFailure)
    }
    @Test fun newerChatOutputParametersCanBeSelected() {
        val (_, body) = ProviderCodec.request(profile(ApiFormat.OPENAI_CHAT).copy(modernChatTokens = true), "Evidence", listOf(TutorMessage("user", "Explain")))
        assertTrue(body.has("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
    }
    @Test fun invalidCredentialsAreRejectedWithoutEchoingTheValue() {
        val credential = "fixture-private-value\ninvalid"
        val failure = runCatching { com.ilearn.app.net.requireApiKey(credential) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure!!.message.orEmpty().contains("fixture-private-value"))
    }
}
