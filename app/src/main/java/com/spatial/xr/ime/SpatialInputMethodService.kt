package com.spatial.xr.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SpatialInputMethodService : InputMethodService(), DictationEngine.DictationListener {

    enum class KeyboardMode {
        VOICE_DICTATION,
        SPATIAL_QWERTY
    }

    private lateinit var dictationEngine: DictationEngine
    private var currentMode = KeyboardMode.VOICE_DICTATION
    private var waveformAmplitude = 0f
    private var statusTextView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        dictationEngine = DictationEngine(this)
        dictationEngine.setListener(this)
    }

    override fun onCreateInputView(): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        statusTextView = TextView(this).apply {
            text = "🎙️ Voice Dictation Active (Temple Mic)"
            textSize = 14f
        }
        rootLayout.addView(statusTextView)

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val clearBtn = Button(this).apply {
            text = "Clear"
            setOnClickListener { handleClearAction() }
        }

        val enterBtn = Button(this).apply {
            text = "Enter"
            setOnClickListener { handleEnterAction() }
        }

        val toggleBtn = Button(this).apply {
            text = "QWERTY"
            setOnClickListener { toggleKeyboardMode() }
        }

        buttonBar.addView(clearBtn)
        buttonBar.addView(enterBtn)
        buttonBar.addView(toggleBtn)
        rootLayout.addView(buttonBar)

        return rootLayout
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        dictationEngine.startListening()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        dictationEngine.stopListening()
    }

    fun handleClearAction(inputConnection: InputConnection? = currentInputConnection) {
        // Send delete key events to clear current composing or preceding text
        inputConnection?.apply {
            finishComposingText()
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    fun handleEnterAction(
        inputConnection: InputConnection? = currentInputConnection,
        editorInfo: EditorInfo? = currentInputEditorInfo
    ) {
        dictationEngine.commitComposingText(inputConnection)
        val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            inputConnection?.performEditorAction(action)
        } else {
            inputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            inputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    fun toggleKeyboardMode(): KeyboardMode {
        currentMode = if (currentMode == KeyboardMode.VOICE_DICTATION) {
            KeyboardMode.SPATIAL_QWERTY
        } else {
            KeyboardMode.VOICE_DICTATION
        }
        statusTextView?.text = if (currentMode == KeyboardMode.VOICE_DICTATION) {
            "🎙️ Voice Dictation Active (Temple Mic)"
        } else {
            "⌨️ 3D Ray-Interactive QWERTY Keyboard"
        }
        return currentMode
    }

    fun getKeyboardMode(): KeyboardMode = currentMode
    fun getDictationEngine(): DictationEngine = dictationEngine
    fun getWaveformAmplitude(): Float = waveformAmplitude

    // DictationEngine.DictationListener
    override fun onTranscriptionInterim(interimText: String) {
        statusTextView?.text = "🎙️ $interimText"
    }

    override fun onTranscriptionCommitted(committedText: String) {
        statusTextView?.text = "✓ $committedText"
    }

    override fun onRmsChanged(rmsdB: Float) {
        waveformAmplitude = (rmsdB / 10.0f).coerceIn(0f, 1f)
    }

    override fun onError(errorCode: Int) {
        statusTextView?.text = "⚠️ Mic error: $errorCode"
    }
}
