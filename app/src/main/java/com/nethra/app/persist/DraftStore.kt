package com.nethra.app.persist

import com.nethra.app.config.ScriptLanguage
import android.content.Context
import android.util.Log
import com.nethra.app.ai.Citation
import com.nethra.app.ai.GeneratedScript
import com.nethra.app.ai.InstagramKit
import com.nethra.app.ai.PublishKit
import com.nethra.app.ai.ScriptBrief
import com.nethra.app.ai.YouTubeKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the creator's work (brief, script, transcript, kit) in app-private
 * storage, so a failed cloud call, a crash or leaving the screen never loses it.
 */
class DraftStore(context: Context) {

    private val dir = File(context.filesDir, "drafts").apply { mkdirs() }

    data class PrompterDraft(val brief: ScriptBrief, val script: GeneratedScript?, val scrollLine: Int)

    data class PublishDraft(
        val videoUri: String,
        val videoName: String,
        val transcript: String,
        val transcriptComplete: Boolean,
        val kit: PublishKit?
    )

    suspend fun savePrompter(d: PrompterDraft) = write("prompter.json", JSONObject().apply {
        put("brief", briefJson(d.brief))
        d.script?.let { put("script", scriptJson(it)) }
        put("scrollLine", d.scrollLine)
    })

    suspend fun loadPrompter(): PrompterDraft? = read("prompter.json")?.let { j ->
        PrompterDraft(
            brief = briefFrom(j.optJSONObject("brief") ?: JSONObject()),
            script = j.optJSONObject("script")?.let(::scriptFrom),
            scrollLine = j.optInt("scrollLine")
        )
    }

    suspend fun savePublish(d: PublishDraft) = write("publish.json", JSONObject().apply {
        put("videoUri", d.videoUri); put("videoName", d.videoName)
        put("transcript", d.transcript); put("transcriptComplete", d.transcriptComplete)
        d.kit?.let { put("kit", kitJson(it)) }
    })

    suspend fun loadPublish(): PublishDraft? = read("publish.json")?.let { j ->
        PublishDraft(
            j.optString("videoUri"), j.optString("videoName"), j.optString("transcript"),
            j.optBoolean("transcriptComplete"), j.optJSONObject("kit")?.let(::kitFrom)
        )
    }

    private suspend fun write(name: String, json: JSONObject) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
        }.onFailure { Log.w(TAG, "Draft save failed: ${it.javaClass.simpleName}") }
        Unit
    }

    private suspend fun read(name: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching { File(dir, name).takeIf { it.exists() }?.readText()?.let(::JSONObject) }.getOrNull()
    }

    private fun briefJson(b: ScriptBrief) = JSONObject().apply {
        put("topic", b.topic); put("audience", b.audience); put("platform", b.platform); put("tone", b.tone)
        b.durationSeconds?.let { put("durationSeconds", it) }
        put("mustInclude", b.mustInclude); put("exclude", b.exclude); put("cta", b.callToAction); put("spoken", b.spokenBrief)
    }

    private fun briefFrom(j: JSONObject) = ScriptBrief(
        topic = j.optString("topic"), audience = j.optString("audience"), platform = j.optString("platform"),
        tone = j.optString("tone"), durationSeconds = if (j.has("durationSeconds")) j.optInt("durationSeconds") else null,
        mustInclude = j.optString("mustInclude"), exclude = j.optString("exclude"),
        callToAction = j.optString("cta"), spokenBrief = j.optString("spoken")
    )

    private fun scriptJson(s: GeneratedScript) = JSONObject().apply {
        put("title", s.title); put("body", s.body); put("notes", s.notes); put("targetWords", s.targetWords)
        put("researched", s.researched); put("incomplete", s.possiblyIncomplete); put("model", s.model); put("language", s.language.code)
        put("sources", JSONArray().apply { s.sources.forEach { put(JSONObject().put("url", it.url).put("title", it.title)) } })
    }

    private fun scriptFrom(j: JSONObject): GeneratedScript {
        val src = j.optJSONArray("sources") ?: JSONArray()
        return GeneratedScript(
            title = j.optString("title"), body = j.optString("body"),
            sources = (0 until src.length()).mapNotNull { i -> src.optJSONObject(i)?.let { Citation(it.optString("url"), it.optString("title")) } },
            notes = j.optString("notes"), targetWords = j.optInt("targetWords"), researched = j.optBoolean("researched"),
            possiblyIncomplete = j.optBoolean("incomplete"), model = j.optString("model"),
            language = ScriptLanguage.of(j.optString("language", null))
        )
    }

    private fun kitJson(k: PublishKit) = JSONObject().apply {
        put("youtube", JSONObject().apply {
            put("title", k.youtube.title); put("description", k.youtube.description)
            put("tags", JSONArray(k.youtube.tags)); put("hashtags", JSONArray(k.youtube.hashtags))
        })
        put("instagram", JSONObject().apply {
            put("caption", k.instagram.caption); put("hashtags", JSONArray(k.instagram.hashtags))
        })
        put("source", k.source)
    }

    private fun kitFrom(j: JSONObject): PublishKit {
        fun arr(o: JSONObject?, key: String) = o?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val yt = j.optJSONObject("youtube"); val ig = j.optJSONObject("instagram")
        return PublishKit(
            YouTubeKit(yt?.optString("title").orEmpty(), yt?.optString("description").orEmpty(), arr(yt, "tags"), arr(yt, "hashtags")),
            InstagramKit(ig?.optString("caption").orEmpty(), arr(ig, "hashtags")),
            j.optString("source")
        )
    }

    companion object { private const val TAG = "DraftStore" }
}
