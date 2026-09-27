package com.nethra.app.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

enum class Platform(val label: String, val packageName: String) {
    YOUTUBE("YouTube", "com.google.android.youtube"),
    INSTAGRAM("Instagram", "com.instagram.android")
}

/**
 * Hands the video (and text on the clipboard) to the YouTube or Instagram app.
 * This is a handoff, not an upload: the creator finishes posting in that app.
 */
object PlatformHandoff {

    fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun isInstalled(context: Context, platform: Platform): Boolean = runCatching {
        context.packageManager.getPackageInfo(platform.packageName, 0); true
    }.getOrDefault(false)

    /**
     * @return a message for the creator describing what happened.
     */
    fun handOff(context: Context, platform: Platform, video: Uri, text: String, title: String? = null): String {
        copy(context, "${platform.label} text", text)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, video)
            putExtra(Intent.EXTRA_TEXT, text)
            // Offered to apps that read a title from shares; the YouTube app currently ignores them.
            if (!title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            clipData = ClipData.newRawUri("video", video)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val direct = Intent(base).setPackage(platform.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(direct)
            "Opened ${platform.label}. The text is on your clipboard — paste it into the ${platform.label} post. NETHRA doesn't upload anything itself."
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(base, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "${platform.label} app not found, so the share sheet was opened instead. The text is on your clipboard."
        } catch (e: SecurityException) {
            "Android blocked sharing this video (${e.message}). Try picking the video again."
        }
    }
}
