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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import java.util.Locale

class InkVoxInputMethodService : InputMethodService() {
    private enum class ImeState {
        READY,
        LISTENING,
        PROCESSING,
        EMPTY,
        FAILED,
        PERMISSION_REQUIRED,
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var state = ImeState.READY
    private var sessionCounter = 0L
    private var activeSessionId: Long? = null
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

    private val recordingDeadline = Runnable { stopRecognition() }
    private val processingDeadline = Runnable {
        if (state == ImeState.PROCESSING) {
            cancelActive(ImeState.FAILED)
        }
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
        if (activeSessionId == null) updateIdleState()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (activeSessionId == null) updateIdleState()
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
        clearTimers()
        activeSessionId = null
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }

    private fun onRecordClicked() {
        when (state) {
            ImeState.LISTENING -> stopRecognition()
            ImeState.PROCESSING -> Unit
            ImeState.PERMISSION_REQUIRED -> openPermissionRequest()
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setState(ImeState.FAILED)
            return
        }

        cancelActive(ImeState.READY)
        val sessionId = ++sessionCounter
        val sessionEditorGeneration = editorGeneration
        activeSessionId = sessionId

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(SessionListener(sessionId, sessionEditorGeneration))
            }
            recordingStartedAt = SystemClock.elapsedRealtime()
            setState(ImeState.LISTENING)
            handler.post(recordingTicker)
            handler.postDelayed(recordingDeadline, MAX_RECORDING_MILLIS)
            recognizer?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
        } catch (error: RuntimeException) {
            debugLog("start_failed=${error.javaClass.simpleName}")
            cancelActive(ImeState.FAILED)
        }
    }

    private fun stopRecognition() {
        if (state != ImeState.LISTENING) return
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingDeadline)
        setState(ImeState.PROCESSING)
        handler.postDelayed(processingDeadline, PROCESSING_TIMEOUT_MILLIS)
        try {
            recognizer?.stopListening()
        } catch (error: RuntimeException) {
            debugLog("stop_failed=${error.javaClass.simpleName}")
            cancelActive(ImeState.FAILED)
        }
    }

    private fun cancelActive(nextState: ImeState) {
        activeSessionId = null
        clearTimers()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        setState(nextState)
    }

    private fun finishActiveSession() {
        activeSessionId = null
        clearTimers()
        recognizer?.destroy()
        recognizer = null
    }

    private fun clearTimers() {
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingDeadline)
        handler.removeCallbacks(processingDeadline)
    }

    private fun updateIdleState() {
        when {
            sensitiveInput -> setState(ImeState.READY)
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                setState(ImeState.PERMISSION_REQUIRED)
            else -> setState(ImeState.READY)
        }
    }

    private fun setState(nextState: ImeState) {
        if (state != nextState) debugLog("state=${state.name}->${nextState.name}")
        state = nextState
        renderState()
    }

    private fun renderState() {
        val active = state == ImeState.LISTENING || state == ImeState.PROCESSING
        statusView?.setText(
            when {
                sensitiveInput -> R.string.sensitive_field
                state == ImeState.READY -> R.string.ready
                state == ImeState.LISTENING -> R.string.listening
                state == ImeState.PROCESSING -> R.string.processing
                state == ImeState.EMPTY -> R.string.empty_result
                state == ImeState.FAILED -> R.string.recognition_failed
                else -> R.string.permission_required
            },
        )
        recordButton?.apply {
            isEnabled = !sensitiveInput && state != ImeState.PROCESSING
            setText(
                when (state) {
                    ImeState.LISTENING -> R.string.stop_recording
                    ImeState.EMPTY, ImeState.FAILED -> R.string.retry
                    ImeState.PERMISSION_REQUIRED -> R.string.grant_permission
                    else -> R.string.start_recording
                },
            )
            contentDescription = text
        }
        cancelButton?.visibility = if (active) View.VISIBLE else View.GONE
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

    private fun isCurrentSession(sessionId: Long, generation: Long): Boolean {
        return activeSessionId == sessionId && editorGeneration == generation
    }

    private fun completeWithError(sessionId: Long, generation: Long, error: Int) {
        if (!isCurrentSession(sessionId, generation)) return
        debugLog("recognition_error=$error")
        finishActiveSession()
        setState(
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> ImeState.EMPTY
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ImeState.PERMISSION_REQUIRED
                else -> ImeState.FAILED
            },
        )
    }

    private inner class SessionListener(
        private val sessionId: Long,
        private val generation: Long,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!isCurrentSession(sessionId, generation) || state != ImeState.LISTENING) return
            handler.removeCallbacks(recordingTicker)
            handler.removeCallbacks(recordingDeadline)
            setState(ImeState.PROCESSING)
            handler.postDelayed(processingDeadline, PROCESSING_TIMEOUT_MILLIS)
        }

        override fun onError(error: Int) {
            completeWithError(sessionId, generation, error)
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrentSession(sessionId, generation)) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
            finishActiveSession()

            if (text == null) {
                setState(ImeState.EMPTY)
                return
            }
            if (sensitiveInput || currentInputConnection?.commitText(text, 1) != true) {
                setState(ImeState.FAILED)
                return
            }
            debugLog("recognition_completed_ms=${SystemClock.elapsedRealtime() - recordingStartedAt}")
            setState(ImeState.READY)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun debugLog(message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "InkVox"
        private const val MAX_RECORDING_MILLIS = 60_000L
        private const val PROCESSING_TIMEOUT_MILLIS = 15_000L
    }
}
