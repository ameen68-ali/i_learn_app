package com.ilearn.app.tutor

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.ilearn.app.net.requireApiKey

data class TutorMessage(val role: String, val text: String, val imageBase64: String? = null)
data class TutorReply(val text: String, val usage: String = "")

private fun JSONObject?.optNonNullString(key: String): String {
    if (this == null || isNull(key)) return ""
    return optString(key, "")
}

object ProviderCodec {
    private fun encodeChatOrAnthropicMessage(format: ApiFormat, m: TutorMessage): JSONObject {
        val obj = JSONObject().put("role", m.role)
        val img = m.imageBase64?.takeIf { it.isNotBlank() }
        if (img == null) {
            return obj.put("content", m.text)
        }
        val parts = JSONArray()
        if (format == ApiFormat.ANTHROPIC) {
            parts.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", img)
                )
            )
            parts.put(JSONObject().put("type", "text").put("text", m.text))
        } else {
            parts.put(JSONObject().put("type", "text").put("text", m.text))
            parts.put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$img")
                )
            )
        }
        return obj.put("content", parts)
    }

    private fun encodeResponsesMessage(m: TutorMessage): JSONObject {
        val obj = JSONObject().put("role", m.role)
        val img = m.imageBase64?.takeIf { it.isNotBlank() }
        if (img == null) {
            return obj.put("content", m.text)
        }
        val parts = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", m.text))
            .put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$img"))
        return obj.put("content", parts)
    }

    private fun encodeGeminiMessage(m: TutorMessage): JSONObject {
        val parts = JSONArray()
        m.imageBase64?.takeIf { it.isNotBlank() }?.let { img ->
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", img)
                )
            )
        }
        parts.put(JSONObject().put("text", m.text))
        return JSONObject()
            .put("role", if (m.role == "assistant") "model" else "user")
            .put("parts", parts)
    }

    fun request(p: ProviderProfile, system: String, messages: List<TutorMessage>): Pair<String, JSONObject> {
        p.validate()
        val body = JSONObject().put("model", p.model)
        val path = when (p.format) {
            ApiFormat.OPENAI_CHAT -> {
                body.put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).apply {
                    messages.forEach { put(encodeChatOrAnthropicMessage(ApiFormat.OPENAI_CHAT, it)) }
                }).put(if (p.modernChatTokens) "max_completion_tokens" else "max_tokens", p.maxOutputTokens).put("stream", p.stream)
                if (p.stream && java.net.URI(p.endpoint).host == "api.openai.com") body.put("stream_options", JSONObject().put("include_usage", true))
                "chat/completions"
            }
            ApiFormat.OPENAI_RESPONSES -> {
                body.put("instructions", system).put("input", JSONArray().apply { messages.forEach { put(encodeResponsesMessage(it)) } })
                    .put("max_output_tokens", p.maxOutputTokens).put("stream", p.stream).put("store", false)
                "responses"
            }
            ApiFormat.ANTHROPIC -> {
                body.put("system", system).put("messages", JSONArray().apply { messages.forEach { put(encodeChatOrAnthropicMessage(ApiFormat.ANTHROPIC, it)) } })
                    .put("max_tokens", p.maxOutputTokens).put("stream", p.stream)
                "messages"
            }
            ApiFormat.GEMINI -> {
                body.remove("model")
                body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().apply { messages.forEach { put(encodeGeminiMessage(it)) } })
                    .put("generationConfig", JSONObject().put("maxOutputTokens", p.maxOutputTokens))
                "models/${p.model.removePrefix("models/")}:${if (p.stream) "streamGenerateContent?alt=sse" else "generateContent"}"
            }
        }
        return "${p.endpoint.trimEnd('/')}/$path" to body
    }

    private fun extractOpenAiContent(message: JSONObject?): String {
        if (message == null || message.isNull("content")) return ""
        val raw = message.opt("content") ?: return ""
        return when (raw) {
            is String -> raw
            is JSONArray -> (0 until raw.length()).joinToString("") { i ->
                raw.optJSONObject(i).optNonNullString("text")
            }
            else -> ""
        }
    }

    fun text(format: ApiFormat, json: JSONObject, streaming: Boolean): String {
        val finish = when (format) {
            ApiFormat.OPENAI_CHAT -> json.optJSONArray("choices")?.optJSONObject(0).optNonNullString("finish_reason")
            ApiFormat.OPENAI_RESPONSES -> json.optNonNullString("status").ifBlank { json.optJSONObject("response").optNonNullString("status") }
            ApiFormat.ANTHROPIC -> json.optNonNullString("stop_reason").ifBlank { json.optJSONObject("delta").optNonNullString("stop_reason") }
            ApiFormat.GEMINI -> json.optJSONArray("candidates")?.optJSONObject(0).optNonNullString("finishReason")
        }
        check(finish !in setOf("length", "content_filter", "max_tokens", "MAX_TOKENS", "SAFETY", "RECITATION", "incomplete", "failed")) {
            "Provider stopped before completing the answer. Increase the output limit or ask a narrower question."
        }
        return when (format) {
            ApiFormat.OPENAI_CHAT -> json.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
                val message = choice.optJSONObject(if (streaming) "delta" else "message")
                if (message.optNonNullString("refusal").isNotBlank()) throw IllegalStateException("The provider declined this request")
                extractOpenAiContent(message)
            } ?: ""
            ApiFormat.OPENAI_RESPONSES -> if (streaming) {
                when (json.optNonNullString("type")) {
                    "response.output_text.delta" -> json.optNonNullString("delta")
                    "response.refusal.delta" -> throw IllegalStateException("The provider declined this request")
                    else -> ""
                }
            } else json.optJSONArray("output")?.let { output -> (0 until output.length()).joinToString("") { i ->
                output.optJSONObject(i)?.optJSONArray("content")?.let { content -> (0 until content.length()).joinToString("") { j -> content.optJSONObject(j).optNonNullString("text") } } ?: ""
            } } ?: ""
            ApiFormat.ANTHROPIC -> if (streaming) json.optJSONObject("delta").optNonNullString("text")
            else json.optJSONArray("content")?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it).optNonNullString("text") } } ?: ""
            ApiFormat.GEMINI -> json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.let { a ->
                (0 until a.length()).filter { a.optJSONObject(it)?.optBoolean("thought") != true }.joinToString("") { a.optJSONObject(it).optNonNullString("text") }
            } ?: ""
        }
    }
}

class ProviderTransport(client: OkHttpClient = sharedClient) {
    companion object { private val sharedClient = OkHttpClient() }
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    suspend fun generate(p: ProviderProfile, key: String, system: String, messages: List<TutorMessage>, onText: suspend (String) -> Unit): TutorReply = withContext(Dispatchers.IO) {
        requireApiKey(key)
        val (url, body) = ProviderCodec.request(p, system, messages)
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        when (p.format) {
            ApiFormat.GEMINI -> request.header("x-goog-api-key", key)
            ApiFormat.ANTHROPIC -> request.header("x-api-key", key).header("anthropic-version", p.apiVersion)
            else -> request.header("Authorization", "Bearer $key")
        }
        val call = client.newCall(request.build())
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { when (response.code) {
                        401, 403 -> "API key rejected or model access denied"
                        429 -> "Provider quota or rate limit reached"
                        in 300..399 -> "Redirect rejected; verify the API endpoint"
                        else -> "Provider request failed (HTTP ${response.code})"
                    } }
                    val source = response.body?.source() ?: error("Empty provider response")
                    val result = StringBuilder(); val usage = JSONObject(); var eventData = StringBuilder(); var completed = !p.stream
                    suspend fun consume(data: String) {
                        if (data == "[DONE]") { completed = true; return }
                        if (data.isBlank()) return
                        val json = try { JSONObject(data) } catch (e: org.json.JSONException) {
                            throw IllegalStateException("Provider returned a malformed JSON event (${data.length} characters)", e)
                        }
                        check(json.isNull("error") && json.optNonNullString("type") !in setOf("error", "response.failed", "response.incomplete")) { "Provider could not complete the answer" }
                        val delta = ProviderCodec.text(p.format, json, p.stream)
                        completed = completed || when (p.format) {
                            ApiFormat.OPENAI_CHAT -> json.optJSONArray("choices")?.optJSONObject(0).optNonNullString("finish_reason") == "stop"
                            ApiFormat.OPENAI_RESPONSES -> json.optNonNullString("type") == "response.completed"
                            ApiFormat.ANTHROPIC -> json.optNonNullString("type") == "message_stop"
                            ApiFormat.GEMINI -> json.optJSONArray("candidates")?.optJSONObject(0).optNonNullString("finishReason") == "STOP"
                        }
                        result.append(delta); require(result.length <= 120000) { "Provider answer exceeds the safety limit" }
                        val u = json.optJSONObject("usage") ?: json.optJSONObject("usageMetadata") ?: json.optJSONObject("response")?.optJSONObject("usage") ?: json.optJSONObject("message")?.optJSONObject("usage")
                        if (u != null) listOf("prompt_tokens", "completion_tokens", "total_tokens", "input_tokens", "output_tokens", "promptTokenCount", "candidatesTokenCount", "totalTokenCount", "thoughtsTokenCount", "cachedContentTokenCount").forEach { field ->
                            if (u.opt(field) is Number) usage.put(field, u.getLong(field).coerceAtLeast(0))
                        }
                        if (delta.isNotEmpty()) onText(result.toString())
                    }
                    if (p.stream) {
                        require(response.header("Content-Type").orEmpty().contains("text/event-stream")) { "Endpoint did not return SSE; disable streaming in its profile" }
                        while (!source.exhausted()) {
                            ensureActive()
                            val line = source.readUtf8LineStrict(200000)
                            if (line.isEmpty()) { consume(eventData.toString().trimEnd()); eventData = StringBuilder() }
                            else if (line.startsWith("data:")) { eventData.append(line.removePrefix("data:").trimStart()).append('\n'); require(eventData.length <= 200000) }
                        }
                        consume(eventData.toString().trimEnd())
                    } else {
                        val buffer = okio.Buffer(); var count = 0L
                        while (!source.exhausted()) { count += source.read(buffer, 8192); require(count <= 200000) { "Provider response too large" }; ensureActive() }
                        consume(buffer.readUtf8())
                    }
                    check(completed) { "The answer stream ended before completion. Partial text was not saved; retry manually." }
                    check(result.isNotBlank()) { "Provider returned no answer; check model support and output limits" }
                    TutorReply(result.toString(), if (usage.length() == 0) "" else usage.toString())
                }
            } catch (e: java.io.IOException) {
                ensureActive(); throw IllegalStateException("Connection interrupted or timed out; retry manually", e)
            } finally { cancellation.cancelAndJoin() }
        }
    }
}
