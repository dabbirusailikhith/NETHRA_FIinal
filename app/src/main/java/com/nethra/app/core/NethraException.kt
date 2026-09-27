package com.nethra.app.core

/** What went wrong, so screens can decide what to offer next (retry, Gemma, settings...). */
enum class ErrorKind {
    MISSING_KEY, OFFLINE, NETWORK, AUTH, CREDITS, RATE_LIMIT, MODEL_NOT_FOUND,
    SERVER, BAD_RESPONSE, LOCAL_MODEL, MEDIA, UNSUPPORTED_FILE, NO_AUDIO, NO_SPEECH, CANCELLED
}

/** An error whose [message] is written for the creator, not the developer. */
class NethraException(
    val kind: ErrorKind,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** Cloud-only failures where an on-device model could still help. */
    val isCloudUnavailable: Boolean
        get() = kind in setOf(
            ErrorKind.MISSING_KEY, ErrorKind.OFFLINE, ErrorKind.NETWORK,
            ErrorKind.AUTH, ErrorKind.CREDITS, ErrorKind.RATE_LIMIT, ErrorKind.SERVER
        )
}

fun Throwable.userMessage(): String = when (this) {
    is NethraException -> message
    is kotlinx.coroutines.CancellationException -> "Cancelled."
    else -> "Something went wrong: ${this.message ?: this::class.java.simpleName}"
}
