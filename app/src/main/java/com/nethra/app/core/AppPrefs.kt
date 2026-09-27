package com.nethra.app.core

import android.content.Context
import com.nethra.app.config.CaptionScript
import com.nethra.app.config.PrompterScript
import com.nethra.app.config.ScriptLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide settings shared by every camera mode. */
class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("nethra_app", Context.MODE_PRIVATE)

    private val _autoCaptions = MutableStateFlow(p.getBoolean(KEY_CAPTIONS, true))
    /** After each recording, make a captioned copy automatically (needs internet). */
    val autoCaptions: StateFlow<Boolean> = _autoCaptions

    fun setAutoCaptions(on: Boolean) {
        p.edit().putBoolean(KEY_CAPTIONS, on).apply()
        _autoCaptions.value = on
    }

    private val _language = MutableStateFlow(ScriptLanguage.of(p.getString(KEY_LANGUAGE, null)))
    /** The language the creator speaks: sets the recogniser, the script, the pace and the captions. */
    val language: StateFlow<ScriptLanguage> = _language

    fun setLanguage(l: ScriptLanguage) {
        p.edit().putString(KEY_LANGUAGE, l.code).apply()
        _language.value = l
    }

    private val _prompterScript = MutableStateFlow(PrompterScript.of(p.getString(KEY_PROMPTER_SCRIPT, null)))
    /** Hindi/Telugu teleprompter script: Hinglish/Tinglish (English letters) or the native script. */
    val prompterScript: StateFlow<PrompterScript> = _prompterScript

    fun setPrompterScript(s: PrompterScript) {
        p.edit().putString(KEY_PROMPTER_SCRIPT, s.code).apply()
        _prompterScript.value = s
    }

    private val _captionScript = MutableStateFlow(CaptionScript.of(p.getString(KEY_CAPTION_SCRIPT, null)))
    /** Hindi/Telugu captions: original script, English translation, or Hinglish/Tinglish. */
    val captionScript: StateFlow<CaptionScript> = _captionScript

    fun setCaptionScript(s: CaptionScript) {
        p.edit().putString(KEY_CAPTION_SCRIPT, s.code).apply()
        _captionScript.value = s
    }

    private companion object {
        const val KEY_CAPTIONS = "auto_captions"
        const val KEY_LANGUAGE = "script_language"
        const val KEY_PROMPTER_SCRIPT = "prompter_script"
        const val KEY_CAPTION_SCRIPT = "caption_script"
    }
}
