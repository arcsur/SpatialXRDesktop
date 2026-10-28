package com.spatial.xr.tutorial

import android.content.Context
import android.content.SharedPreferences

class TutorialManager(
    private val context: Context? = null
) {

    companion object {
        const val PREFS_NAME = "spatial_xr_tutorial_prefs"
        const val KEY_TUTORIAL_COMPLETED = "has_completed_tutorial"
    }

    enum class TutorialTopic(val id: Int, val title: String, val shortcut: String) {
        POINTER_PINCH(0, "Ray Pointer & Pinch Click", "pointer"),
        RESIZE_MOVE(1, "4-Corner 3D Resize & Move", "resize"),
        PALM_DOCK(2, "Look-at-Palm Wrist Dock", "dock"),
        AIR_SWIPE_CINEMA(3, "Air-Swipe & Cinema Mode", "swipe"),
        GEMINI_VOICE(4, "Voice Dictation & Gemini Assistant", "gemini");

        companion object {
            fun fromShortcutOrName(query: String): TutorialTopic {
                val q = query.trim().lowercase()
                return entries.firstOrNull {
                    it.shortcut.contains(q) || it.title.lowercase().contains(q) || it.name.lowercase().contains(q)
                } ?: POINTER_PINCH
            }
        }
    }

    data class TutorialStep(
        val topic: TutorialTopic,
        val headline: String,
        val description: String,
        val interactionHint: String
    )

    private val steps = listOf(
        TutorialStep(
            topic = TutorialTopic.POINTER_PINCH,
            headline = "Ray Pointer & Pinch to Select",
            description = "Point your index finger forward to cast a laser ray. Pinch thumb and index finger together (< 20mm) to click apps, links, and buttons.",
            interactionHint = "Try it: Extend index finger and pinch thumb."
        ),
        TutorialStep(
            topic = TutorialTopic.RESIZE_MOVE,
            headline = "Iron Man Diagonal Stretch & 3D Move",
            description = "Pinch opposite diagonal corners with two hands and pull apart or push together to stretch or shrink windows (Iron Man style). Pinch the top titlebar to freely reposition windows in 3D space—pushing farther away automatically scales window size for perspective.",
            interactionHint = "Pinch top-left & bottom-right to stretch; grab top bar to move in 3D."
        ),
        TutorialStep(
            topic = TutorialTopic.PALM_DOCK,
            headline = "Look at Your Palm for Apps",
            description = "Rotate your hand so your palm faces your face. A floating dock anchor button appears over your wrist. Pinch to open the 3D curved app dock.",
            interactionHint = "Raise wrist and look directly at your palm."
        ),
        TutorialStep(
            topic = TutorialTopic.AIR_SWIPE_CINEMA,
            headline = "Air-Swipe & 90° Cinema Mode",
            description = "Swipe your hand horizontally in the air (> 1.2 m/s) to rapidly cycle active apps. Tap 'Maximize' to expand any window into a curved 2.0m cinema screen.",
            interactionHint = "Air-swipe left or right to switch running apps."
        ),
        TutorialStep(
            topic = TutorialTopic.GEMINI_VOICE,
            headline = "Voice Dictation & Look-and-Ask",
            description = "Focus any text field for instant temple mic voice typing. Say 'Hey Gemini' or ask 'What am I looking at?' to analyze real-world scenes via pass-through.",
            interactionHint = "Say: 'Hey Gemini, organize my windows' or 'Help'."
        )
    )

    interface TutorialListener {
        fun onTutorialStepChanged(stepIndex: Int, step: TutorialStep)
        fun onTutorialDismissed()
        fun onTutorialCompleted()
    }

    private var currentStepIndex = 0
    private var isVisible = false
    private var listener: TutorialListener? = null
    private var testPrefCompleted = false

    fun setListener(listener: TutorialListener?) {
        this.listener = listener
    }

    fun isTutorialCompleted(): Boolean {
        if (context == null) return testPrefCompleted
        val prefs = getPreferences()
        return prefs?.getBoolean(KEY_TUTORIAL_COMPLETED, false) ?: testPrefCompleted
    }

    fun setTutorialCompleted(completed: Boolean) {
        testPrefCompleted = completed
        getPreferences()?.edit()?.putBoolean(KEY_TUTORIAL_COMPLETED, completed)?.apply()
    }

    fun startTutorial(topic: TutorialTopic? = null) {
        currentStepIndex = topic?.id ?: 0
        isVisible = true
        notifyStepChanged()
    }

    fun startTutorialForQuery(query: String) {
        val topic = TutorialTopic.fromShortcutOrName(query)
        startTutorial(topic)
    }

    fun nextStep(): Boolean {
        if (currentStepIndex < steps.size - 1) {
            currentStepIndex++
            notifyStepChanged()
            return true
        } else {
            completeTutorial()
            return false
        }
    }

    fun previousStep(): Boolean {
        if (currentStepIndex > 0) {
            currentStepIndex--
            notifyStepChanged()
            return true
        }
        return false
    }

    fun dismissTutorial() {
        isVisible = false
        listener?.onTutorialDismissed()
    }

    fun completeTutorial() {
        setTutorialCompleted(true)
        isVisible = false
        listener?.onTutorialCompleted()
    }

    fun isVisible(): Boolean = isVisible
    fun getCurrentStepIndex(): Int = currentStepIndex
    fun getCurrentStep(): TutorialStep = steps[currentStepIndex]
    fun getStepCount(): Int = steps.size
    fun getAllSteps(): List<TutorialStep> = steps

    private fun notifyStepChanged() {
        listener?.onTutorialStepChanged(currentStepIndex, steps[currentStepIndex])
    }

    private fun getPreferences(): SharedPreferences? {
        return context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
