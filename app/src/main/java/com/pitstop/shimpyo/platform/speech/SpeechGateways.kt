package com.pitstop.shimpyo.platform.speech

import ai.pleos.playground.stt.SpeechToText as PleosSpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener as SttResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.pitstop.shimpyo.core.Backend
import com.pitstop.shimpyo.core.SpeechGateway
import com.pitstop.shimpyo.platform.MainThread
import com.pitstop.shimpyo.platform.PleosServiceProbe
import java.util.Locale

/**
 * 말하기·듣기 게이트웨이.
 *
 * 1순위: Pleos Gleo AI SDK의 TextToSpeech·SpeechToText (v3.3.1, ON_DEVICE, ko-KR)
 *        ON_DEVICE 모드는 서버 등록(clientId/secret)이 필요 없다.
 * 2순위: 안드로이드 기본 TextToSpeech·SpeechRecognizer
 * 둘 다 없으면 말은 화면 자막으로만 보이고, 대답은 시연 패널 버튼으로 넣는다(canListen=false).
 *
 * 응답 지연 = 듣기를 시작한 순간부터 운전자 말이 처음 인식된 순간까지.
 * (Pleos STT: 첫 부분 인식 결과 / 안드로이드: onBeginningOfSpeech)
 */
class SpeechRouter(context: Context) : SpeechGateway {

    private val appContext = context.applicationContext
    private val pleos: PleosSpeech? = if (PleosServiceProbe.hasSpeech(appContext)) PleosSpeech(appContext) else null
    private val android = AndroidSpeech(appContext)

    override val backend: Backend
        get() = if (pleos?.ttsReady == true || pleos?.sttReady == true) Backend.PLEOS else Backend.ANDROID

    override val canListen: Boolean
        get() = AppVisibility.visible && micGranted() && (pleos?.sttReady == true || android.recognizerAvailable)

    override fun start() {
        pleos?.start()
        android.start()
    }

    override fun stop() {
        pleos?.stop()
        android.stop()
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val p = pleos
        if (p != null && p.ttsReady) p.speak(text, onDone) else android.speak(text, onDone)
    }

    override fun listen(timeoutMs: Long, onResult: (text: String, latencyMs: Long?) -> Unit) {
        val p = pleos
        when {
            !micGranted() || !AppVisibility.visible -> waitSilently(timeoutMs, onResult)
            p != null && p.sttReady -> p.listen(timeoutMs, onResult)
            android.recognizerAvailable -> android.listen(timeoutMs, onResult)
            else -> waitSilently(timeoutMs, onResult)
        }
    }

    override fun cancel() {
        pleos?.cancel()
        android.cancel()
        MainThread.cancel(silentWait)
        silentWait = null
    }

    private var silentWait: Runnable? = null

    /** 인식 수단이 없을 때: 시연 패널에서 대답을 넣을 시간만큼 기다렸다가 무응답으로 끝낸다. */
    private fun waitSilently(timeoutMs: Long, onResult: (String, Long?) -> Unit) {
        MainThread.cancel(silentWait)
        silentWait = MainThread.postDelayed(timeoutMs) { onResult("", null) }
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/**
 * 화면이 보이는지(MainActivity onStart/onStop). 앱이 뒤로 가면(내비 전체 화면 등) 마이크 입력이 들어오지 않아
 * 모든 질문이 무응답이 되는데, 이것을 졸음 신호로 세면 위험도가 잘못 오른다. 그래서 보이지 않는 동안은 ‘듣기 불가’로 본다.
 */
object AppVisibility {
    @Volatile var visible: Boolean = true
}

/** Pleos Gleo AI SDK 음성. 초기화가 끝나기 전에는 쓰지 않는다. */
private class PleosSpeech(private val context: Context) {
    @Volatile var ttsReady = false
        private set
    @Volatile var sttReady = false
        private set

    private var tts: PleosTextToSpeech? = null
    private var stt: PleosSpeechToText? = null
    private var pendingSpeak: (() -> Unit)? = null
    private var speakTimeout: Runnable? = null

    private var listenCallback: ((String, Long?) -> Unit)? = null
    private var listenStartedAt = 0L
    private var firstSpeechAt: Long? = null
    private var partial = ""
    private var listenTimeout: Runnable? = null

    private val ttsEvents = object : TtsEventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = MainThread.post {
            // 말하는 도중 새 문장으로 바꾸면 앞 문장의 완료 이벤트가 늦게 올 수 있다. 새 문장이 그렇게 빨리 끝날 수 없으면 무시한다.
            if (SystemClock.elapsedRealtime() < staleDoneUntil) Log.i(TAG, "stale TTS onDone ignored") else finishSpeak()
        }
        // stop()은 우리가 cancel()로 부를 때만 오고, cancel()이 이미 대기 콜백을 지운다.
        // 여기서 finishSpeak()를 부르면 바로 뒤에 시작한 새 문장의 콜백을 잘못 끝낼 수 있어 무시한다.
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: TtsErrorType) {
            Log.w(TAG, "TTS error $errorType")
            MainThread.post { finishSpeak() }
        }
    }

    private val sttEvents = object : SttResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            MainThread.post {
                if (stt.isNotBlank() && firstSpeechAt == null) firstSpeechAt = SystemClock.elapsedRealtime()
                partial = stt
                if (completed) deliver(stt)
            }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = MainThread.post { deliver(partial) }
        override fun onError(errorType: SttErrorType) {
            Log.w(TAG, "STT error $errorType")
            MainThread.post { deliver(partial) }
        }
    }

    fun start() {
        try {
            val engine = PleosTextToSpeech(context, TtsMode.ON_DEVICE)
            // initialize() 성공 전에 addEventListener()를 부르면 실패한다 → 성공 콜백에서 등록
            engine.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    runCatching { engine.addEventListener(ttsEvents) }
                    runCatching { engine.setLocale(Locale.KOREA) }
                    ttsReady = true
                }

                override fun onFailure(errorType: TtsErrorType) {
                    Log.i(TAG, "Pleos TTS init failed $errorType")
                }
            })
            tts = engine
        } catch (t: Throwable) {
            Log.i(TAG, "Pleos TTS unavailable", t)
        }
        try {
            val engine = PleosSpeechToText(context, SttMode.ON_DEVICE)
            engine.addListener(sttEvents)
            engine.initialize(object : SttInitListener {
                override fun onSuccess() {
                    runCatching { engine.setLocale(Locale.KOREA) }
                    sttReady = true
                }

                override fun onFailure(errorType: SttErrorType) {
                    Log.i(TAG, "Pleos STT init failed $errorType")
                }
            })
            stt = engine
        } catch (t: Throwable) {
            Log.i(TAG, "Pleos STT unavailable", t)
        }
    }

    /**
     * 인식 중단. Pleos 문서와 검증된 공개 코드에서 SpeechToText의 시작(request)·해제(release)는 확인했지만
     * 중단 메서드 이름은 확인하지 못해서, 있으면 부르고 없으면 넘어가도록 리플렉션으로 호출한다(빌드 오류 방지).
     */
    private fun stopRecognition() {
        val engine = stt ?: return
        runCatching { engine.javaClass.getMethod("stop").invoke(engine) }
    }

    fun stop() {
        cancel()
        release()
        runCatching { tts?.removeEventListener(ttsEvents) }
        runCatching { tts?.release() }
        runCatching { stt?.removeListener(sttEvents) }
        runCatching { stt?.release() }
        tts = null
        stt = null
        ttsReady = false
        sttReady = false
    }

    private val warmer = OutputWarmer()
    private var warmRunnable: Runnable? = null
    /** 마지막으로 말을 마친 시각. 출력이 아직 깨어 있으면 예열하지 않는다. */
    private var lastSpeechEndMs = 0L

    /** 이 시각 전에 오는 onDone은 앞 문장 것으로 본다. */
    private var staleDoneUntil = 0L

    fun speak(text: String, onDone: () -> Unit) {
        // 앞 문장이 아직 말하는 중이면 멈추고, 그 콜백은 지금 바로 부르지 않고 다음 차례에 부른다
        // (바로 부르면 그 안에서 다시 speak()가 불려 지금 문장의 상태를 덮어쓴다).
        val previous = pendingSpeak
        pendingSpeak = null
        MainThread.cancel(speakTimeout)
        speakTimeout = null
        MainThread.cancel(warmRunnable)
        if (previous != null) {
            runCatching { tts?.stop() }
            lastSpeechEndMs = SystemClock.elapsedRealtime()
        }
        pendingSpeak = onDone
        if (previous != null) MainThread.postDelayed(0L) { previous() }
        val idleMs = SystemClock.elapsedRealtime() - lastSpeechEndMs
        // Pleos TTS 서비스는 100ms 무음만 채우고 재생을 시작한 뒤 첫 음성이 100ms 남짓 뒤에 와서,
        // 출력이 잠들어 있던 상태면 첫 음절이 끊기거나 지직거린다. 무음을 잠깐 먼저 틀어 출력을 깨워 둔다.
        val warmMs = if (OutputWarmer.enabled() && idleMs > OutputWarmer.STANDBY_MS) warmer.warm() else 0L
        val start = Runnable {
            warmRunnable = null
            if (previous != null) staleDoneUntil = SystemClock.elapsedRealtime() + 300L + text.length * 40L
            val started = runCatching { tts?.speak(text) }.isSuccess
            // 완료 이벤트가 오지 않는 경우를 대비: 글자 수에 비례한 최대 시간
            speakTimeout = MainThread.postDelayed(if (started) 1_500L + text.length * 180L else 0L) { finishSpeak() }
        }
        if (warmMs > 0) warmRunnable = MainThread.postDelayed(warmMs) { start.run() } else start.run()
    }

    private fun finishSpeak() {
        MainThread.cancel(speakTimeout)
        speakTimeout = null
        val done = pendingSpeak ?: return
        lastSpeechEndMs = SystemClock.elapsedRealtime()
        pendingSpeak = null
        done()
    }

    fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) {
        listenCallback = onResult
        listenStartedAt = SystemClock.elapsedRealtime()
        firstSpeechAt = null
        partial = ""
        val started = runCatching { stt?.request() }.isSuccess
        if (!started) return deliver("")
        listenTimeout = MainThread.postDelayed(timeoutMs) {
            stopRecognition()
            deliver(partial)
        }
    }

    private fun deliver(text: String) {
        val cb = listenCallback ?: return
        listenCallback = null
        MainThread.cancel(listenTimeout)
        listenTimeout = null
        val latency = firstSpeechAt?.let { it - listenStartedAt }
        cb(if (latency == null) "" else text, latency)
    }

    fun cancel() {
        stopRecognition()
        runCatching { tts?.stop() }
        listenCallback = null
        MainThread.cancel(listenTimeout)
        MainThread.cancel(warmRunnable)
        warmRunnable = null
        pendingSpeak = null
        MainThread.cancel(speakTimeout)
    }

    fun release() = warmer.release()

    private companion object {
        const val TAG = "ShimpyoPleosSpeech"
    }
}

/**
 * 음성 출력 예열. 소리가 한동안 없으면 오디오 출력이 대기(standby)로 들어가고,
 * 다시 깨어날 때 첫 수십 ms가 끊기거나 튈 수 있다. 말하기 직전에 짧은 무음을 먼저 재생해 출력을 깨워 둔다.
 * 시험용으로 `adb shell setprop debug.shimpyo.warmup 0`이면 끈다.
 */
private class OutputWarmer {
    private var track: android.media.AudioTrack? = null

    /** 무음 재생을 시작하고, 말을 시작해도 되는 시점까지 기다릴 시간(ms)을 돌려준다. 실패하면 0. */
    fun warm(): Long = try {
        release()
        val frames = SAMPLE_RATE * SILENCE_MS / 1000
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    // Pleos TTS 서비스가 쓰는 것과 같은 용도(USAGE_MEDIA, 음성)로 같은 출력을 깨운다.
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        t.write(ShortArray(frames), 0, frames)
        t.play()
        track = t
        MainThread.postDelayed(SILENCE_MS + 200L) { if (track === t) release() }
        LEAD_MS
    } catch (t: Throwable) {
        Log.i("ShimpyoWarmer", "warm-up failed: ${t.message}")
        0L
    }

    fun release() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val SILENCE_MS = 400
        /** 무음을 튼 뒤 TTS를 부르기까지. TTS 첫 음성이 나오기까지 100ms 남짓 더 걸려 무음 구간과 이어진다. */
        const val LEAD_MS = 250L
        /** 이만큼 조용했으면 출력이 잠들었을 수 있다고 본다(AudioFlinger 기본 대기 진입 3초보다 짧게). */
        const val STANDBY_MS = 2_000L

        fun enabled(): Boolean = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
                .invoke(null, "debug.shimpyo.warmup", "1") as String
        }.getOrDefault("1") != "0"
    }
}

/** 안드로이드 기본 TTS·음성 인식. 일반 AVD나 휴대폰 시연용. */
private class AndroidSpeech(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = mutableMapOf<String, () -> Unit>()
    private var counter = 0

    private var recognizer: SpeechRecognizer? = null
    private var listenCallback: ((String, Long?) -> Unit)? = null
    private var listenStartedAt = 0L
    private var speechStartedAt: Long? = null
    private var listenTimeout: Runnable? = null
    private var retried = false
    private var lastIntent: Intent? = null

    val recognizerAvailable: Boolean
        get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun start() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.KOREAN
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = complete(utteranceId)
                    // 새 문장(QUEUE_FLUSH)으로 끊긴 문장은 onDone이 오지 않는다. 그 콜백도 끝내야 기다리는 쪽이 멈추지 않는다.
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = complete(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId)
                })
            }
        }
    }

    fun stop() {
        cancel()
        runCatching { tts?.shutdown() }
        runCatching { recognizer?.destroy() }
        tts = null
        recognizer = null
    }

    fun speak(text: String, onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !ttsReady) {
            // TTS가 없으면 자막만 보이고, 읽는 데 걸릴 만한 시간 뒤에 넘어간다.
            MainThread.postDelayed(800L + text.length * 60L, onDone)
            return
        }
        val id = "shimpyo-${counter++}"
        pending[id] = onDone
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) complete(id)
    }

    private fun complete(id: String?) {
        MainThread.post {
            val done = id?.let { pending.remove(it) } ?: return@post
            done()
        }
    }

    fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) {
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()?.also {
            recognizer = it
        }
        if (r == null) {
            MainThread.postDelayed(timeoutMs) { onResult("", null) }
            return
        }
        listenCallback = onResult
        listenStartedAt = SystemClock.elapsedRealtime()
        speechStartedAt = null
        retried = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() {
                speechStartedAt = SystemClock.elapsedRealtime()
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                val early = SystemClock.elapsedRealtime() - listenStartedAt < 1_000L
                if (early && !retried && (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT)) {
                    // 앞 인식이 아직 정리 중이라 생긴 오류. 무응답(졸음)으로 세지 않고 잠깐 뒤 다시 듣는다.
                    retried = true
                    runCatching { r.cancel() }
                    MainThread.postDelayed(400L) { if (listenCallback != null) runCatching { r.startListening(lastIntent) }.onFailure { deliver("") } }
                    return
                }
                deliver("")
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                deliver(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (speechStartedAt == null) speechStartedAt = SystemClock.elapsedRealtime()
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        lastIntent = intent
        runCatching { r.startListening(intent) }.onFailure { deliver("") }
        listenTimeout = MainThread.postDelayed(timeoutMs) {
            runCatching { r.stopListening() }
            // 이미 말을 시작했으면 마지막 결과(onResults)를 조금 더 기다린다. 끝까지 안 오면 빈 대답.
            if (speechStartedAt != null) listenTimeout = MainThread.postDelayed(LATE_RESULT_MS) { deliver("") } else deliver("")
        }
    }

    private fun deliver(text: String) {
        MainThread.post {
            val cb = listenCallback ?: return@post
            listenCallback = null
            MainThread.cancel(listenTimeout)
            listenTimeout = null
            val latency = speechStartedAt?.let { it - listenStartedAt }
            cb(text, latency)
        }
    }

    fun cancel() {
        listenCallback = null
        MainThread.cancel(listenTimeout)
        runCatching { recognizer?.cancel() }
        runCatching { tts?.stop() }
        pending.clear()
    }

    private companion object {
        const val LATE_RESULT_MS = 1_500L
    }
}
