package com.ilearn.app.tutor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.ilearn.app.net.requireApiKey

enum class ApiFormat { OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI }

data class ProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val format: ApiFormat,
    val endpoint: String,
    val model: String,
    val maxOutputTokens: Int = 2048,
    val contextChars: Int = 24000,
    val stream: Boolean = true,
    val apiVersion: String = "2023-06-01",
    val modernChatTokens: Boolean = false
) {
    fun validate() {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) { "Use an HTTPS API base URL without credentials, query or fragment" }
        require(name.isNotBlank() && name.length <= 100 && model.matches(Regex("[A-Za-z0-9_./:-]{1,150}"))) { "Enter a profile name and valid model ID" }
        require(maxOutputTokens in 128..8192 && contextChars in 1000..120000)
        require(apiVersion.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
    }
}

/** Stored only in noBackupFilesDir. Session credentials are never serialized. */
class ProviderProfileStore(context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "api_profiles.json")
    private val sessions = mutableMapOf<String, String>()
    var loadWarning: String? = null
        private set
    private var root = if (file.exists() || java.io.File(file.path + ".bak").exists()) {
        runCatching { JSONObject(android.util.AtomicFile(file).readFully().toString(Charsets.UTF_8)).also { it.getJSONArray("profiles") } }
            .getOrElse { loadWarning = "Saved API profiles could not be read. Re-create the profiles before using the tutor."; JSONObject().put("profiles", JSONArray()) }
    } else JSONObject().put("profiles", JSONArray())
    var activeId: String?
        get() = root.optString("active").takeIf { it.isNotBlank() }
        set(value) { root.put("active", value); persist() }

    @Synchronized fun profiles(): List<ProviderProfile> = (0 until root.getJSONArray("profiles").length()).map { i ->
        root.getJSONArray("profiles").getJSONObject(i).let { p ->
            ProviderProfile(p.getString("id"), p.getString("name"), ApiFormat.valueOf(p.getString("format")), p.getString("endpoint"), p.getString("model"), p.optInt("output", 2048), p.optInt("context", 24000), p.optBoolean("stream", true), p.optString("version", "2023-06-01"), p.optBoolean("modernChatTokens", false))
        }
    }
    fun active(): ProviderProfile? = profiles().firstOrNull { it.id == activeId }
    @Synchronized fun save(profile: ProviderProfile, key: String, remember: Boolean) {
        profile.validate(); requireApiKey(key)
        val entries = root.getJSONArray("profiles")
        val next = JSONArray()
        for (i in 0 until entries.length()) if (entries.getJSONObject(i).getString("id") != profile.id) next.put(entries.getJSONObject(i))
        require(next.length() < 50) { "Maximum of 50 API profiles" }
        val p = JSONObject().put("id", profile.id).put("name", profile.name).put("format", profile.format.name)
            .put("endpoint", profile.endpoint.trimEnd('/')).put("model", profile.model).put("output", profile.maxOutputTokens)
            .put("context", profile.contextChars).put("stream", profile.stream).put("version", profile.apiVersion).put("modernChatTokens", profile.modernChatTokens)
        if (remember) p.put("secret", encrypt(key, profile))
        next.put(p); sessions[profile.id] = key; root.put("profiles", next).put("active", profile.id); persist(); loadWarning = null
    }
    @Synchronized fun key(profile: ProviderProfile): String? {
        // Match the complete saved destination: editing an endpoint must never reuse an old key.
        val saved = profiles().firstOrNull { it.id == profile.id && it.endpoint == profile.endpoint && it.format == profile.format } ?: return null
        sessions[saved.id]?.let { return it }
        val entries = root.getJSONArray("profiles")
        val encrypted = (0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getString("id") == saved.id }.optString("secret")
        if (encrypted.isBlank()) return null
        return runCatching { decrypt(encrypted, saved) }.getOrNull()
    }
    @Synchronized fun delete(id: String) {
        val entries = root.getJSONArray("profiles"); val next = JSONArray()
        for (i in 0 until entries.length()) if (entries.getJSONObject(i).getString("id") != id) next.put(entries.getJSONObject(i))
        root.put("profiles", next); sessions.remove(id)
        if (activeId == id) root.remove("active")
        persist()
    }
    private fun persist() {
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(root.toString().toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    private fun encryptionKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("ilearn-api-profiles", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ilearn-api-profiles", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun aad(p: ProviderProfile) = "${p.id}|${p.format}|${p.endpoint.trimEnd('/')}".toByteArray()
    private fun encrypt(value: String, p: ProviderProfile): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()); updateAAD(aad(p)) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(value: String, p: ProviderProfile): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(aad(p))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
}
