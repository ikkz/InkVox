package com.inkvox

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit

class InkVoxInputMethodService : InputMethodService() {
    private enum class ImeState {
        READY,
        CONNECTING,
        LISTENING,
        PROCESSING,
        EMPTY,
        FAILED,
        PERMISSION_REQUIRED,
        CONFIG_REQUIRED,
    }

    private class RecognitionSession(
        val id: Long,
        val editorGeneration: Long,
        val taskId: String,
    ) {
        val finalSentences = TreeMap<Int, String>()

        @Volatile var socket: WebSocket? = null
        @Volatile var recorder: AudioRecord? = null
        @Volatile var stopping = false
        @Volatile var cancelled = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()
    private var state = ImeState.READY
    private var sessionCounter = 0L
    private var activeSession: RecognitionSession? = null
    private var editorGeneration = 0L
    private var recordingStartedAt = 0L
    private var sensitiveInput = false

    private var statusView: TextView? = null
    private var durationView: TextView? = null
    private var recordButton: Button? = null
    private var cancelButton: Button? = null
    private var backspaceButton: Button? = null
    private var switchButton: Button? = null

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (state != ImeState.LISTENING) return
            val elapsedSeconds = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1_000
            durationView?.text = String.format(
                Locale.getDefault(),
                "%d:%02d",
                elapsedSeconds / 60,
                elapsedSeconds % 60,
            )
            handler.postDelayed(this, 1_000)
        }
    }

    private val connectionDeadline = Runnable {
        if (state == ImeState.CONNECTING) cancelActive(ImeState.FAILED)
    }
    private val recordingDeadline = Runnable { stopRecognition() }
    private val processingDeadline = Runnable {
        if (state == ImeState.PROCESSING) cancelActive(ImeState.FAILED)
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) cancelActive(ImeState.READY)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOffReceiver, filter)
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        return layoutInflater.inflate(R.layout.input_method, null).also { view ->
            statusView = view.findViewById(R.id.status)
            durationView = view.findViewById(R.id.duration)
            recordButton = view.findViewById<Button>(R.id.record).apply {
                setOnClickListener { onRecordClicked() }
            }
            cancelButton = view.findViewById<Button>(R.id.cancel).apply {
                setOnClickListener { cancelActive(ImeState.READY) }
            }
            backspaceButton = view.findViewById<Button>(R.id.backspace).apply {
                setOnClickListener { deleteOneCodePoint() }
            }
            switchButton = view.findViewById<Button>(R.id.switch_ime).apply {
                setOnClickListener { switchInputMethod() }
            }
            renderState()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorGeneration++
        cancelActive(ImeState.READY)
        sensitiveInput = attribute?.let(::isSensitiveInput) == true
        updateIdleState()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sensitiveInput = info?.let(::isSensitiveInput) == true
        if (activeSession == null) updateIdleState()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (activeSession == null) updateIdleState()
    }

    override fun onWindowHidden() {
        cancelActive(ImeState.READY)
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        editorGeneration++
        cancelActive(ImeState.READY)
        super.onFinishInput()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        cancelActive(ImeState.READY)
        unregisterReceiver(screenOffReceiver)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun onRecordClicked() {
        when (state) {
            ImeState.LISTENING -> stopRecognition()
            ImeState.CONNECTING, ImeState.PROCESSING -> Unit
            ImeState.PERMISSION_REQUIRED -> openPermissionRequest()
            ImeState.CONFIG_REQUIRED -> openCloudConfig()
            else -> startRecognition()
        }
    }

    private fun startRecognition() {
        if (sensitiveInput) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setState(ImeState.PERMISSION_REQUIRED)
            openPermissionRequest()
            return
        }
        val credentials = CloudConfig.load(this)
        if (credentials == null) {
            setState(ImeState.CONFIG_REQUIRED)
            return
        }

        cancelActive(ImeState.READY)
        val session = RecognitionSession(
            id = ++sessionCounter,
            editorGeneration = editorGeneration,
            taskId = UUID.randomUUID().toString(),
        )
        activeSession = session
        setState(ImeState.CONNECTING)
        handler.postDelayed(connectionDeadline, CONNECTION_TIMEOUT_MILLIS)

        try {
            val request = Request.Builder()
                .url("wss://${credentials.workspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference")
                .header("Authorization", "Bearer ${credentials.apiKey}")
                .build()
            session.socket = client.newWebSocket(request, SessionListener(session))
        } catch (error: RuntimeException) {
            debugLog("connect_failed=${error.javaClass.simpleName}")
            cancelActive(ImeState.FAILED)
        }
    }

    private fun stopRecognition() {
        if (state != ImeState.LISTENING) return
        val session = activeSession ?: return
        session.stopping = true
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingDeadline)
        setState(ImeState.PROCESSING)
        handler.postDelayed(processingDeadline, PROCESSING_TIMEOUT_MILLIS)
        try {
            session.recorder?.stop()
        } catch (_: IllegalStateException) {
            // The capture thread may already have stopped the recorder.
        }
    }

    private fun cancelActive(nextState: ImeState) {
        val session = activeSession
        activeSession = null
        clearTimers()
        if (session != null) {
            session.cancelled = true
            session.stopping = true
            try {
                session.recorder?.stop()
            } catch (_: IllegalStateException) {
                // The capture thread owns release and may already have stopped it.
            }
            session.socket?.cancel()
            session.finalSentences.clear()
        }
        setState(nextState)
    }

    private fun finishActiveSession(session: RecognitionSession) {
        if (activeSession !== session) return
        activeSession = null
        clearTimers()
        session.cancelled = true
        session.stopping = true
        try {
            session.recorder?.stop()
        } catch (_: IllegalStateException) {
            // The capture thread owns release and may already have stopped it.
        }
        session.socket?.close(1000, "done")
    }

    private fun clearTimers() {
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(connectionDeadline)
        handler.removeCallbacks(recordingDeadline)
        handler.removeCallbacks(processingDeadline)
    }

    private fun updateIdleState() {
        when {
            sensitiveInput -> setState(ImeState.READY)
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                setState(ImeState.PERMISSION_REQUIRED)
            !CloudConfig.hasConfig(this) -> setState(ImeState.CONFIG_REQUIRED)
            else -> setState(ImeState.READY)
        }
    }

    private fun setState(nextState: ImeState) {
        if (state != nextState) debugLog("state=${state.name}->${nextState.name}")
        state = nextState
        renderState()
    }

    private fun renderState() {
        val active = state == ImeState.CONNECTING ||
            state == ImeState.LISTENING ||
            state == ImeState.PROCESSING
        statusView?.setText(
            when {
                sensitiveInput -> R.string.sensitive_field
                state == ImeState.READY -> R.string.ready
                state == ImeState.CONNECTING -> R.string.connecting
                state == ImeState.LISTENING -> R.string.listening
                state == ImeState.PROCESSING -> R.string.processing
                state == ImeState.EMPTY -> R.string.empty_result
                state == ImeState.FAILED -> R.string.recognition_failed
                state == ImeState.CONFIG_REQUIRED -> R.string.config_required
                else -> R.string.permission_required
            },
        )
        recordButton?.apply {
            isEnabled = !sensitiveInput && state != ImeState.CONNECTING && state != ImeState.PROCESSING
            setText(
                when (state) {
                    ImeState.LISTENING -> R.string.stop_recording
                    ImeState.EMPTY, ImeState.FAILED -> R.string.retry
                    ImeState.PERMISSION_REQUIRED -> R.string.grant_permission
                    ImeState.CONFIG_REQUIRED -> R.string.open_cloud_config
                    else -> R.string.start_recording
                },
            )
            contentDescription = text
        }
        cancelButton?.visibility = if (active) View.VISIBLE else View.INVISIBLE
        backspaceButton?.isEnabled = !active
        switchButton?.isEnabled = !active
        if (state != ImeState.LISTENING) durationView?.text = ""
    }

    private fun openPermissionRequest() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_REQUEST_MICROPHONE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }

    private fun openCloudConfig() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }

    private fun deleteOneCodePoint() {
        val connection = currentInputConnection ?: return
        if (!connection.deleteSurroundingTextInCodePoints(1, 0)) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun switchInputMethod() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !switchToNextInputMethod(false)) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    private fun isSensitiveInput(info: EditorInfo): Boolean {
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isCurrentSession(session: RecognitionSession): Boolean {
        return activeSession === session && editorGeneration == session.editorGeneration
    }

    private fun handleServerMessage(session: RecognitionSession, message: String) {
        if (!isCurrentSession(session)) return
        val json = try {
            JSONObject(message)
        } catch (_: Exception) {
            completeWithFailure(session, ImeState.FAILED, "invalid_json")
            return
        }
        val header = json.optJSONObject("header")
        if (header == null || header.optString("task_id") != session.taskId) {
            completeWithFailure(session, ImeState.FAILED, "invalid_task")
            return
        }

        when (header.optString("event")) {
            "task-started" -> {
                if (state != ImeState.CONNECTING) return
                handler.removeCallbacks(connectionDeadline)
                recordingStartedAt = SystemClock.elapsedRealtime()
                setState(ImeState.LISTENING)
                handler.post(recordingTicker)
                handler.postDelayed(recordingDeadline, MAX_RECORDING_MILLIS)
                startAudioCapture(session)
            }
            "result-generated" -> {
                val sentence = json.optJSONObject("payload")
                    ?.optJSONObject("output")
                    ?.optJSONObject("sentence")
                    ?: return
                if (sentence.optBoolean("heartbeat") || !sentence.optBoolean("sentence_end")) return
                val sentenceId = sentence.optInt("sentence_id", -1)
                val text = sentence.optString("text")
                if (sentenceId > 0 && text.isNotBlank()) session.finalSentences[sentenceId] = text
            }
            "task-finished" -> completeWithResult(session)
            "task-failed" -> {
                val errorCode = header.optString("error_code")
                debugLog("task_failed=${safeErrorCode(errorCode)}")
                completeWithFailure(
                    session,
                    if (isAuthenticationError(errorCode)) ImeState.CONFIG_REQUIRED else ImeState.FAILED,
                    "task_failed",
                )
            }
            else -> completeWithFailure(session, ImeState.FAILED, "unknown_event")
        }
    }

    private fun completeWithResult(session: RecognitionSession) {
        if (!isCurrentSession(session)) return
        val result = session.finalSentences.values.joinToString(separator = "")
        finishActiveSession(session)
        session.finalSentences.clear()
        if (result.isBlank()) {
            setState(ImeState.EMPTY)
            return
        }
        if (sensitiveInput || currentInputConnection?.commitText(result, 1) != true) {
            setState(ImeState.FAILED)
            return
        }
        debugLog("recognition_completed_ms=${SystemClock.elapsedRealtime() - recordingStartedAt}")
        setState(ImeState.READY)
    }

    private fun completeWithFailure(
        session: RecognitionSession,
        nextState: ImeState,
        reason: String,
    ) {
        if (!isCurrentSession(session)) return
        debugLog("recognition_failed=$reason")
        cancelActive(nextState)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(session: RecognitionSession) {
        Thread({
            var recorder: AudioRecord? = null
            try {
                val minimumBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minimumBuffer > 0)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimumBuffer, AUDIO_CHUNK_BYTES),
                )
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                session.recorder = recorder

                if (!session.cancelled && !session.stopping) {
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val buffer = ByteArray(AUDIO_CHUNK_BYTES)
                    while (!session.cancelled && !session.stopping) {
                        val count = recorder.read(buffer, 0, buffer.size)
                        if (count > 0) {
                            if (session.socket?.send(buffer.toByteString(0, count)) != true) {
                                throw IllegalStateException("websocket_send_failed")
                            }
                        } else if (!session.stopping && !session.cancelled) {
                            throw IllegalStateException("audio_read_failed")
                        }
                    }
                }

                if (!session.cancelled && session.stopping &&
                    session.socket?.send(finishTaskMessage(session.taskId)) != true
                ) {
                    throw IllegalStateException("finish_send_failed")
                }
            } catch (_: SecurityException) {
                session.cancelled = true
                handler.post {
                    completeWithFailure(session, ImeState.PERMISSION_REQUIRED, "audio_permission")
                }
            } catch (error: RuntimeException) {
                session.cancelled = true
                handler.post {
                    debugLog("audio_failed=${error.javaClass.simpleName}")
                    completeWithFailure(session, ImeState.FAILED, "audio_capture")
                }
            } finally {
                if (recorder != null) {
                    try {
                        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    } catch (_: IllegalStateException) {
                        // Release still needs to run.
                    }
                    recorder.release()
                    if (session.recorder === recorder) session.recorder = null
                }
            }
        }, "InkVoxAudio").start()
    }

    private inner class SessionListener(
        private val session: RecognitionSession,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            handler.post {
                if (!isCurrentSession(session)) {
                    webSocket.cancel()
                    return@post
                }
                session.socket = webSocket
                debugLog("websocket_open=${response.code}")
                if (!webSocket.send(runTaskMessage(session.taskId))) {
                    completeWithFailure(session, ImeState.FAILED, "run_task_send")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handler.post { handleServerMessage(session, text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handler.post {
                if (!isCurrentSession(session)) return@post
                val code = response?.code
                debugLog("websocket_failed_http=${code ?: 0} type=${t.javaClass.simpleName}")
                completeWithFailure(
                    session,
                    if (code == 401 || code == 403) ImeState.CONFIG_REQUIRED else ImeState.FAILED,
                    "websocket",
                )
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handler.post {
                if (isCurrentSession(session)) {
                    debugLog("websocket_closed=$code")
                    completeWithFailure(session, ImeState.FAILED, "connection_closed")
                }
            }
        }
    }

    private fun runTaskMessage(taskId: String): String {
        return JSONObject()
            .put(
                "header",
                JSONObject()
                    .put("action", "run-task")
                    .put("task_id", taskId)
                    .put("streaming", "duplex"),
            )
            .put(
                "payload",
                JSONObject()
                    .put("task_group", "audio")
                    .put("task", "asr")
                    .put("function", "recognition")
                    .put("model", MODEL)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("format", "pcm")
                            .put("sample_rate", SAMPLE_RATE)
                            .put("heartbeat", true)
                            .put("language_hints", JSONArray().put("zh").put("en")),
                    )
                    .put("input", JSONObject()),
            )
            .toString()
    }

    private fun finishTaskMessage(taskId: String): String {
        return JSONObject()
            .put(
                "header",
                JSONObject()
                    .put("action", "finish-task")
                    .put("task_id", taskId)
                    .put("streaming", "duplex"),
            )
            .put("payload", JSONObject().put("input", JSONObject()))
            .toString()
    }

    private fun isAuthenticationError(errorCode: String): Boolean {
        val normalized = errorCode.uppercase(Locale.ROOT)
        return "AUTH" in normalized || "API_KEY" in normalized || "APIKEY" in normalized
    }

    private fun safeErrorCode(errorCode: String): String {
        return errorCode.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80)
    }

    private fun debugLog(message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "InkVox"
        private const val MODEL = "qwen-audio-3.0-asr-flash-streaming"
        private const val SAMPLE_RATE = 16_000
        private const val AUDIO_CHUNK_BYTES = 3_200
        private const val CONNECTION_TIMEOUT_MILLIS = 10_000L
        private const val MAX_RECORDING_MILLIS = 5 * 60_000L
        private const val PROCESSING_TIMEOUT_MILLIS = 15_000L
    }
}
