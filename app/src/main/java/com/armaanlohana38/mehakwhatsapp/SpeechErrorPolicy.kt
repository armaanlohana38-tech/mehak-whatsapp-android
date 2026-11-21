package com.armaanlohana38.mehakwhatsapp

/**
 * Pure-Kotlin speech error policy.
 *
 * Deliberately free of Android framework types so it can be unit tested on a
 * plain JVM. The numeric codes mirror android.speech.SpeechRecognizer.
 */
object SpeechErrorPolicy {

    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    const val ERROR_NO_SERVICE = 14

    const val MAX_RETRIES = 3

    private val BACKOFF = longArrayOf(300L, 600L, 1200L)

    /** 300ms, 600ms, 1200ms — clamped at both ends. */
    fun backoffFor(attempt: Int): Long {
        if (attempt < 0) return BACKOFF[0]
        if (attempt >= BACKOFF.size) return BACKOFF[BACKOFF.size - 1]
        return BACKOFF[attempt]
    }

    /** Codes worth an automatic retry. */
    fun shouldRetry(code: Int): Boolean = when (code) {
        ERROR_SERVER_DISCONNECTED,
        ERROR_SPEECH_TIMEOUT,
        ERROR_NO_MATCH,
        ERROR_RECOGNIZER_BUSY,
        ERROR_NETWORK,
        ERROR_NETWORK_TIMEOUT,
        ERROR_SERVER -> true
        else -> false
    }

    /** ERROR_RECOGNIZER_BUSY: cancel() first, then wait, then start. */
    fun needsCancelFirst(code: Int): Boolean = code == ERROR_RECOGNIZER_BUSY

    /** ERROR_CLIENT: destroy the recognizer and build a fresh one. */
    fun needsRecreate(code: Int): Boolean = code == ERROR_CLIENT

    fun needsPermission(code: Int): Boolean = code == ERROR_INSUFFICIENT_PERMISSIONS

    fun isDisconnect(code: Int): Boolean = code == ERROR_SERVER_DISCONNECTED

    /** Human readable, never just a number. */
    fun messageFor(code: Int): String = when (code) {
        ERROR_NETWORK_TIMEOUT ->
            "Speech service ne time out kar diya, phir se sun raha hoon…"
        ERROR_NETWORK ->
            "Internet ya speech service se connection nahi bana, phir se koshish kar raha hoon…"
        ERROR_AUDIO ->
            "Microphone se awaaz nahi aa rahi — mic check karo."
        ERROR_SERVER ->
            "Speech service se error aaya, phir se koshish kar raha hoon…"
        ERROR_CLIENT ->
            "Speech recognizer restart kar raha hoon…"
        ERROR_SPEECH_TIMEOUT ->
            "Kuch sunai nahi diya, phir se bolo…"
        ERROR_NO_MATCH ->
            "Samajh nahi aaya, phir se bolo…"
        ERROR_RECOGNIZER_BUSY ->
            "Speech service busy hai, thoda ruk ke phir se sun raha hoon…"
        ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission chahiye — Settings mein allow karo."
        ERROR_SERVER_DISCONNECTED ->
            "Speech service se connection toot gaya, phir se koshish kar raha hoon…"
        ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Ye bhasha speech service support nahi karti, default bhasha par ja raha hoon…"
        ERROR_LANGUAGE_UNAVAILABLE ->
            "Chuni hui bhasha abhi available nahi hai, default par ja raha hoon…"
        ERROR_NO_SERVICE ->
            "Is phone par koi speech recognition service nahi hai. Google app ki 'Speech services by Google' enable ya update karo."
        else ->
            "Speech recognition error ($code) — phir se koshish kar raha hoon…"
    }

    /** Shown after three disconnects in a row. */
    fun escalationMessage(): String =
        "Lagataar 3 baar speech service disconnect hui. Google app kholo aur 'Speech services by Google' update ya enable karo."
}
