package com.armaanlohana38.mehakwhatsapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Hosts the WhatsApp-clone web UI and bridges the browser speech APIs to
 * Android's own engines.
 *
 * Speech recognizer rules implemented here:
 *  - created on the main thread, destroyed after every session (never reused)
 *  - every RecognitionListener callback handled
 *  - ERROR_SERVER_DISCONNECTED / SPEECH_TIMEOUT / NO_MATCH / RECOGNIZER_BUSY
 *    retried with a 300 / 600 / 1200 ms backoff, max 3 tries
 *  - ERROR_RECOGNIZER_BUSY: cancel() first, wait, then start
 *  - ERROR_CLIENT: destroy and rebuild the recognizer
 *  - every error surfaced to the page as a readable message, never a bare number
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var activeSessionId: String? = null
    private var retryAttempt = 0
    private var consecutiveDisconnects = 0
    private var pendingPermissionAction: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTts: Triple<String, String, Float>? = null

    private val diagnostics = ArrayDeque<String>()

    companion object {
        private const val TAG = "MehakSpeech"
        private const val REQ_AUDIO = 1001
        private const val REQ_AUDIO_FOR_SPEECH = 1002
        private const val MAX_DIAGNOSTICS = 20
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString MehakAndroid/1.0"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // getUserMedia (voice notes) needs the page's request granted.
                runOnUiThread { request.grant(request.resources) }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Keep the app inside the WebView for our own content.
                return !(url.startsWith("file://") || url.startsWith("about:"))
            }
        }

        webView.addJavascriptInterface(SpeechBridge(), "AndroidSpeech")
        webView.addJavascriptInterface(TtsBridge(), "AndroidTTS")

        initTts()
        webView.loadUrl("file:///android_asset/index.html")
    }

    /* ============================================================
       TTS
       ============================================================ */
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { evalJs("window.__nativeTTSDone && window.__nativeTTSDone();") }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { evalJs("window.__nativeTTSError && window.__nativeTTSError();") }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        runOnUiThread { evalJs("window.__nativeTTSError && window.__nativeTTSError();") }
                    }
                })
                pendingTts?.let { (text, lang, rate) -> speakNow(text, lang, rate) }
                pendingTts = null
            }
        }
    }

    private fun speakNow(text: String, lang: String, rate: Float) {
        val engine = tts ?: return
        val locale = try {
            Locale.forLanguageTag(lang)
        } catch (e: Exception) {
            Locale.US
        }
        val available = engine.isLanguageAvailable(locale)
        if (available == TextToSpeech.LANG_MISSING_DATA ||
            available == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            // Fall back through the app's preferred order.
            val fallbacks = listOf("hi-IN", "en-IN", "en-GB", "en-US")
            var picked = false
            for (f in fallbacks) {
                val l = Locale.forLanguageTag(f)
                val a = engine.isLanguageAvailable(l)
                if (a != TextToSpeech.LANG_MISSING_DATA && a != TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = l
                    picked = true
                    break
                }
            }
            if (!picked) engine.language = Locale.US
        } else {
            engine.language = locale
        }
        engine.setSpeechRate(if (rate > 0f) rate else 1.0f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mehak-${System.currentTimeMillis()}")
    }

    inner class TtsBridge {
        @JavascriptInterface
        fun speak(text: String, lang: String, rate: Float, pitch: Float) {
            runOnUiThread {
                if (!ttsReady) {
                    pendingTts = Triple(text, lang, rate)
                    return@runOnUiThread
                }
                speakNow(text, lang, rate)
            }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread { tts?.stop() }
        }

        @JavascriptInterface
        fun getVoices(): String {
            val engine = tts ?: return "[]"
            val arr = JSONArray()
            try {
                for (v in engine.voices ?: emptySet()) {
                    val o = JSONObject()
                    o.put("name", v.name)
                    o.put("lang", v.locale.toLanguageTag())
                    o.put("localService", v.isNetworkConnectionRequired.not())
                    arr.put(o)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getVoices failed", e)
            }
            return arr.toString()
        }
    }

    /* ============================================================
       SPEECH RECOGNITION
       ============================================================ */
    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAudioThen(action: () -> Unit) {
        if (hasAudioPermission()) {
            action()
            return
        }
        pendingPermissionAction = action
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_AUDIO_FOR_SPEECH
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_FOR_SPEECH) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (granted) {
                action?.invoke()
            } else {
                val id = activeSessionId
                if (id != null) {
                    emitError(id, SpeechErrorPolicy.ERROR_INSUFFICIENT_PERMISSIONS)
                    emitEnd(id)
                }
            }
        }
    }

    /** Always build a fresh recognizer on the main thread. */
    private fun createRecognizer(): SpeechRecognizer? {
        destroyRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return null
        }
        return try {
            SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createSpeechRecognizer failed", e)
            null
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.setRecognitionListener(null)
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed", e)
        }
        recognizer = null
    }

    private fun buildIntent(lang: String): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        return intent
    }

    private fun startSession(sessionId: String, lang: String) {
        activeSessionId = sessionId
        retryAttempt = 0
        startListening(lang)
    }

    private fun startListening(lang: String) {
        val id = activeSessionId ?: return
        val rec = createRecognizer()
        if (rec == null) {
            emitError(id, SpeechErrorPolicy.ERROR_NO_SERVICE)
            emitEnd(id)
            return
        }
        recognizer = rec
        try {
            rec.startListening(buildIntent(lang))
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            emitError(id, SpeechErrorPolicy.ERROR_CLIENT)
            emitEnd(id)
        }
    }

    private fun retryOrFail(code: Int, lang: String) {
        val id = activeSessionId ?: return
        if (SpeechErrorPolicy.shouldRetry(code) && retryAttempt < SpeechErrorPolicy.MAX_RETRIES) {
            val delay = SpeechErrorPolicy.backoffFor(retryAttempt)
            retryAttempt++
            if (SpeechErrorPolicy.needsCancelFirst(code)) {
                try {
                    recognizer?.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "cancel failed", e)
                }
            }
            if (SpeechErrorPolicy.needsRecreate(code)) {
                destroyRecognizer()
            }
            main.postDelayed({
                if (activeSessionId == id) startListening(lang)
            }, delay)
        } else {
            emitError(id, code)
            emitEnd(id)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            logDiag("ready")
        }

        override fun onBeginningOfSpeech() {
            logDiag("speech-start")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            logDiag("speech-end")
        }

        override fun onError(error: Int) {
            val id = activeSessionId ?: return
            logDiag("error $error")
            if (SpeechErrorPolicy.isDisconnect(error)) {
                consecutiveDisconnects++
            } else if (error != SpeechErrorPolicy.ERROR_NO_MATCH &&
                error != SpeechErrorPolicy.ERROR_SPEECH_TIMEOUT
            ) {
                consecutiveDisconnects = 0
            }

            if (SpeechErrorPolicy.needsPermission(error)) {
                emitError(id, error)
                emitEnd(id)
                return
            }

            if (SpeechErrorPolicy.shouldRetry(error) &&
                retryAttempt < SpeechErrorPolicy.MAX_RETRIES
            ) {
                // Tell the page we are retrying, with a readable message.
                emitError(id, error)
                retryOrFail(error, currentLang)
            } else {
                emitError(id, error)
                if (consecutiveDisconnects >= 3) {
                    emitEscalation(id)
                }
                emitEnd(id)
            }
        }

        override fun onResults(results: Bundle?) {
            val id = activeSessionId ?: return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim().orEmpty()
            logDiag("result")
            if (text.isNotEmpty()) {
                consecutiveDisconnects = 0
                emitResult(id, text, true)
            } else {
                emitError(id, SpeechErrorPolicy.ERROR_NO_MATCH)
            }
            emitEnd(id)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val id = activeSessionId ?: return
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) emitResult(id, text, false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            logDiag("event $eventType")
        }
    }

    private var currentLang: String = "hi-IN"

    /* ============================================================
       JS bridge helpers
       ============================================================ */
    private fun evalJs(js: String) {
        runOnUiThread {
            try {
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                Log.w(TAG, "evaluateJavascript failed", e)
            }
        }
    }

    private fun jsString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(" ") else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun emitResult(id: String, text: String, isFinal: Boolean) {
        evalJs(
            "window.__nativeSpeechResult && window.__nativeSpeechResult(" +
                jsString(id) + "," + jsString(text) + "," + isFinal + ");"
        )
    }

    private fun emitError(id: String, code: Int) {
        evalJs(
            "window.__nativeSpeechError && window.__nativeSpeechError(" +
                jsString(id) + "," + code + ");"
        )
    }

    private fun emitEnd(id: String) {
        evalJs("window.__nativeSpeechEnd && window.__nativeSpeechEnd(" + jsString(id) + ");")
        if (activeSessionId == id) {
            activeSessionId = null
            retryAttempt = 0
            destroyRecognizer()
        }
    }

    private fun emitEscalation(id: String) {
        evalJs(
            "window.__nativeSpeechEscalation && window.__nativeSpeechEscalation(" +
                jsString(id) + "," + jsString(SpeechErrorPolicy.escalationMessage()) + ");"
        )
    }

    private fun logDiag(what: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
        diagnostics.addLast("$stamp  $what")
        while (diagnostics.size > MAX_DIAGNOSTICS) diagnostics.removeFirst()
    }

    inner class SpeechBridge {
        @JavascriptInterface
        fun start(sessionId: String, lang: String) {
            runOnUiThread {
                currentLang = lang.ifBlank { "hi-IN" }
                requestAudioThen {
                    startSession(sessionId, currentLang)
                }
            }
        }

        @JavascriptInterface
        fun stop(sessionId: String) {
            runOnUiThread {
                try {
                    recognizer?.stopListening()
                } catch (e: Exception) {
                    Log.w(TAG, "stopListening failed", e)
                }
            }
        }

        @JavascriptInterface
        fun abort(sessionId: String) {
            runOnUiThread {
                try {
                    recognizer?.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "cancel failed", e)
                }
                if (activeSessionId == sessionId) {
                    activeSessionId = null
                    retryAttempt = 0
                }
                destroyRecognizer()
            }
        }

        @JavascriptInterface
        fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface
        fun diagnostics(): String {
            val arr = JSONArray()
            for (d in diagnostics) arr.put(d)
            return arr.toString()
        }

        @JavascriptInterface
        fun openSpeechSettings() {
            runOnUiThread {
                try {
                    val intent = Intent("com.android.settings.TTS_SETTINGS")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val fallback = Intent(android.provider.Settings.ACTION_SETTINGS)
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(fallback)
                    } catch (e2: Exception) {
                        Log.w(TAG, "openSpeechSettings failed", e2)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        destroyRecognizer()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "tts shutdown failed", e)
        }
        tts = null
        super.onDestroy()
    }
}
