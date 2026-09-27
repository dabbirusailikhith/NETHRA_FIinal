package com.nethra.app.config

import com.nethra.app.BuildConfig

/**
 * Supplies the OpenRouter key that Gradle read from `local.properties`.
 *
 * The key is XOR-masked at build time (see app/build.gradle.kts) so it is not a
 * plain string in the APK. Never log the value returned here.
 */
object ApiKeyProvider {

    val openRouterKey: String? by lazy { unmask(BuildConfig.OR_KEY_DATA, BuildConfig.OR_KEY_MASK) }

    val hasOpenRouterKey: Boolean get() = openRouterKey != null

    private fun unmask(data: String, mask: String): String? {
        if (data.isEmpty() || data.length != mask.length || data.length % 2 != 0) return null
        val d = hex(data)
        val m = hex(mask)
        val out = ByteArray(d.size) { (d[it].toInt() xor m[it].toInt()).toByte() }
        return String(out, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i ->
        s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
