package com.nethra.app.ai

import android.util.Log
import com.nethra.app.config.AiConfig
import com.nethra.app.config.ApiKeyProvider
import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import com.nethra.app.core.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A cited web page returned by OpenRouter's web plugin. */
data class Citation(val url: String, val title: String)

data class ChatResult(
    val text: String,
    val finishReason: String?,
    val citations: List<Citation>
)

/**
 * Minimal OpenRouter chat-completions client.
 *
 * Nothing here logs request bodies or headers: the Authorization header holds
 * the API key and must never reach logcat.
 */
class OpenRouterClient(private val network: NetworkMonitor) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * @param messages OpenAI-style message array.
     * @param plugins  optional OpenRouter plugins (e.g. web search).
     * @param retries  extra attempts for transient failures (timeouts, 429, 5xx).
     */
    suspend fun chat(
        model: String,
        messages: JSONArray,
        maxTokens: Int? = null,
        temperature: Double? = null,
        plugins: JSONArray? = null,
        retries: Int = 2
    ): ChatResult {
        val key = ApiKeyProvider.openRouterKey ?: throw NethraException(
            ErrorKind.MISSING_KEY,
            "No OpenRouter API key in this build. Add OPENROUTER_API_KEY to local.properties, rebuild and reinstall."
        )
        if (!network.isOnlineNow()) throw NethraException(
            ErrorKind.OFFLINE, "No internet connection. This step needs cloud access (OpenRouter)."
        )

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .apply {
                maxTokens?.let { put("max_tokens", it) }
                temperature?.let { put("temperature", it) }
                plugins?.let { put("plugins", it) }
            }
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url(AiConfig.OPENROUTER_CHAT_URL)
            .header("Authorization", "Bearer $key")
            .header("X-Title", AiConfig.APP_TITLE)
            .post(body)
            .build()

        var attempt = 0
        while (true) {
            try {
                return execute(request, model)
            } catch (e: NethraException) {
                val transient = e.kind == ErrorKind.RATE_LIMIT || e.kind == ErrorKind.SERVER ||
                    e.kind == ErrorKind.NETWORK
                if (!transient || attempt >= retries || !network.isOnlineNow()) throw e
                attempt++
                delay(1500L * attempt * attempt)
            }
        }
    }

    /**
     * Runs entirely on [Dispatchers.IO]: the OkHttp callback resumes the caller's
     * dispatcher (the main thread for ViewModels), and reading a long model reply
     * there froze the UI and crashed with NetworkOnMainThreadException.
     */
    private suspend fun execute(request: Request, model: String): ChatResult = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            throw NethraException(ErrorKind.OFFLINE, "Can't reach OpenRouter — check your internet connection.", e)
        } catch (e: SocketTimeoutException) {
            throw NethraException(ErrorKind.NETWORK, "OpenRouter took too long to respond. Try again.", e)
        } catch (e: IOException) {
            throw NethraException(ErrorKind.NETWORK, "Network error talking to OpenRouter (${e.javaClass.simpleName}).", e)
        }

        val raw = response.use { it.body?.string().orEmpty() }
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val apiError = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }

        if (!response.isSuccessful || (apiError != null && json.optJSONArray("choices") == null)) {
            val code = response.code
            Log.w(TAG, "OpenRouter HTTP $code for $model")
            val detail = apiError?.let { ": ${it.take(200)}" } ?: ""
            throw when (code) {
                401 -> NethraException(ErrorKind.AUTH, "OpenRouter rejected the API key (401). Check OPENROUTER_API_KEY in local.properties, then rebuild.")
                402 -> NethraException(ErrorKind.CREDITS, "Your OpenRouter account is out of credits (402). Add credits and try again.")
                403 -> NethraException(ErrorKind.AUTH, "OpenRouter refused the request (403)$detail")
                404 -> NethraException(ErrorKind.MODEL_NOT_FOUND, "Model '$model' was not found on OpenRouter. Change it in AiConfig.kt.$detail")
                408 -> NethraException(ErrorKind.NETWORK, "OpenRouter timed out (408). Try again.")
                429 -> NethraException(ErrorKind.RATE_LIMIT, "OpenRouter rate limit reached (429). Wait a moment and try again.")
                in 500..599 -> NethraException(ErrorKind.SERVER, "OpenRouter or the model provider had an error ($code). Try again.$detail")
                else -> NethraException(ErrorKind.BAD_RESPONSE, "OpenRouter error ($code)$detail")
            }
        }
        if (json == null) throw NethraException(ErrorKind.BAD_RESPONSE, "OpenRouter returned a response NETHRA could not read.")
        parse(json)
    }

    private fun parse(json: JSONObject): ChatResult {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw NethraException(ErrorKind.BAD_RESPONSE, "The model returned no answer.")
        choice.optJSONObject("error")?.let {
            throw NethraException(ErrorKind.SERVER, "The model failed mid-answer: ${it.optString("message").take(200)}")
        }
        val message = choice.optJSONObject("message")
            ?: throw NethraException(ErrorKind.BAD_RESPONSE, "The model returned no message.")

        val text = when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    content.optJSONObject(i)?.optString("text")?.let { append(it) }
                }
            }
            else -> ""
        }

        val citations = mutableListOf<Citation>()
        message.optJSONArray("annotations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i)?.optJSONObject("url_citation") ?: continue
                val url = c.optString("url")
                if (url.isNotBlank() && citations.none { it.url == url }) {
                    citations += Citation(url, c.optString("title").ifBlank { url })
                }
            }
        }
        val finish = choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
        return ChatResult(text.trim(), finish, citations)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    companion object {
        private const val TAG = "OpenRouter"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun userText(text: String) = JSONObject().put("role", "user").put("content", text)
        fun systemText(text: String) = JSONObject().put("role", "system").put("content", text)
        fun assistantText(text: String) = JSONObject().put("role", "assistant").put("content", text)
    }
}
