package app.grab.pill

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * OPTIONAL, OFF BY DEFAULT. When you turn it on with your own Anthropic API key, Grab asks Claude to
 * read the messages its local rules can't decide ("can you mail me the signed copy?", "send the thing
 * from yesterday"). Only that one message is sent (sender name, app, text), with long digit runs
 * (OTPs, account and phone numbers) masked first. OTPs, rides, tickets, calls and locations are never sent.
 */
object Brain {
    const val DEFAULT_MODEL = "claude-opus-5-5"
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var client: AnthropicClient? = null
    @Volatile private var clientKey: String? = null

    private fun secure(c: Context): SharedPreferences = EncryptedSharedPreferences.create(
        "grab_secure", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), c.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun enabled(c: Context) = Store.get(c, "ai_on") == "1" && !key(c).isNullOrBlank()
    fun key(c: Context): String? = try { secure(c).getString("anthropic_key", null) } catch (e: Exception) { null }
    fun model(c: Context) = Store.get(c, "ai_model")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    fun configure(c: Context, on: Boolean, key: String?, model: String?) {
        if (key != null) try { secure(c).edit().putString("anthropic_key", key.trim()).apply() } catch (e: Exception) { }
        Store.put(c, "ai_on", if (on) "1" else "0")
        if (model != null) Store.put(c, "ai_model", model.trim())
        client = null
    }

    private fun client(key: String): AnthropicClient {
        client?.takeIf { clientKey == key }?.let { return it }
        return AnthropicOkHttpClient.builder().apiKey(key).build().also { client = it; clientKey = key }
    }

    private val SYSTEM = """
        You help a phone assistant decide whether a single incoming chat or email message asks the phone's owner for something.
        Reply with ONLY one JSON object, no prose: {"intent": "document_request" | "reply_needed" | "none", "doc_type": <one of the given codes or null>, "summary": <max 8 words>}.
        document_request = the sender wants a file, document, photo or ID from the owner.
        reply_needed = a direct question or request the owner should answer soon (not newsletters, promotions, automated mail or group chatter).
        none = anything else. Digits shown as # were masked for privacy.
    """.trimIndent()

    data class Read(val intent: String, val docType: String?, val summary: String)

    private val EXTRACT = """
        You turn one phone notification into a card for a phone assistant, or decide it isn't worth one.
        Reply with ONLY one JSON object, no prose:
        {"kind": "RIDE" | "ORDER" | "TICKET" | "TRAVEL" | "OTP" | "NONE", "title": <max 5 words>, "detail": <max 10 words>, "value": <the code / seat / PNR the user will need, or "">, "time": <"HH:MM" 24h if an event time is given, else "">}
        RIDE = a cab/bike/auto driver coming or a trip status. ORDER = a delivery or order status. TICKET = an event/movie/metro booking.
        TRAVEL = a flight, train or bus booking. OTP = a one-time code. NONE = promotions, news, social, chat, anything else.
        Digits shown as # were masked for privacy.
    """.trimIndent()

    /** Ask Claude to turn a notification the rules couldn't read into a card. Never sees OTP digits (masked). */
    fun extract(c: Context, app: String, title: String, text: String, done: (Card?) -> Unit) {
        if (!enabled(c)) { done(null); return }
        val key = key(c) ?: run { done(null); return }
        val model = model(c)
        val masked = "$title\n$text".replace(Regex("\\d{4,}")) { "#".repeat(it.value.length) }.take(1200)
        pool.execute {
            val card = try {
                val params = MessageCreateParams.builder().model(model).maxTokens(2048L).system(EXTRACT)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .addUserMessage("App: $app\nNotification:\n$masked").build()
                val msg = client(key).messages().create(params)
                if (msg.stopReason().orElse(null) == StopReason.REFUSAL) null else {
                    val t = msg.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
                    val j = JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
                    val kind = j.optString("kind")
                    if (kind !in setOf("RIDE", "ORDER", "TICKET", "TRAVEL")) null // OTPs are read locally, unmasked
                    else Card(Card.newId(), kind, j.optString("title").take(40).ifBlank { app }, j.optString("detail").take(60),
                        value = j.optString("value").takeIf { !it.contains('#') } ?: "", whenMs = Parser.parseWhen(j.optString("time") + " hrs"))
                }
            } catch (e: Exception) { null }
            done(card)
        }
    }

    /** Ask Claude about one message, off the main thread. `done` gets null when unsure / failed / off. */
    fun classify(c: Context, app: String, sender: String, text: String, done: (Read?) -> Unit) {
        if (!enabled(c)) { done(null); return }
        val key = key(c) ?: run { done(null); return }
        val model = model(c)
        val masked = text.replace(Regex("\\d{4,}")) { "#".repeat(it.value.length) }.take(1500)
        val types = DocVault.TYPES.entries.joinToString(", ") { "${it.key} (${it.value.first})" }
        pool.execute {
            val r = try {
                val params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(2048L)
                    .system(SYSTEM)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    // If the model declines, let the API retry on a fallback model instead of failing
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .addUserMessage("Document codes: $types\nApp: $app\nFrom: $sender\nMessage:\n$masked")
                    .build()
                val msg = client(key).messages().create(params)
                if (msg.stopReason().orElse(null) == StopReason.REFUSAL) null
                else {
                    val text = msg.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
                    val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
                    val type = json.optString("doc_type").takeIf { it in DocVault.TYPES.keys }
                    Read(json.optString("intent", "none"), type, json.optString("summary"))
                }
            } catch (e: Exception) { null }
            done(r)
        }
    }
}
