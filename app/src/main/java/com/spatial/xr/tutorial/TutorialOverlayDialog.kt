package com.spatial.xr.tutorial

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class TutorialOverlayDialog(
    context: Context,
    private val tutorialManager: TutorialManager
) : Dialog(context), TutorialManager.TutorialListener {

    private lateinit var animatedView: AnimatedTutorialView
    private lateinit var stepIndicatorText: TextView
    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var hintText: TextView
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E60A0A14")) // Semi-transparent frosted glass
            setPadding(40, 60, 40, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Header
        stepIndicatorText = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#00E5FF"))
            gravity = Gravity.CENTER
        }
        root.addView(stepIndicatorText)

        titleText = TextView(context).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 16)
        }
        root.addView(titleText)

        // Animated Graphical Canvas
        animatedView = AnimatedTutorialView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                650
            ).apply {
                topMargin = 10
                bottomMargin = 20
            }
        }
        root.addView(animatedView)

        // Description
        descriptionText = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#D0D0E0"))
            gravity = Gravity.CENTER
            setPadding(20, 8, 20, 8)
        }
        root.addView(descriptionText)

        // Interaction hint pill
        hintText = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FFD700"))
            setBackgroundColor(Color.parseColor("#25253A"))
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
        }
        root.addView(hintText)

        // Navigation button bar
        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        val skipButton = Button(context).apply {
            text = "Skip"
            setTextColor(Color.parseColor("#8E8EA0"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                tutorialManager.completeTutorial()
                dismiss()
            }
        }

        prevButton = Button(context).apply {
            text = "Previous"
            setOnClickListener {
                tutorialManager.previousStep()
            }
        }

        nextButton = Button(context).apply {
            text = "Next"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setOnClickListener {
                val hasNext = tutorialManager.nextStep()
                if (!hasNext) {
                    dismiss()
                }
            }
        }

        navBar.addView(skipButton)
        navBar.addView(prevButton)
        navBar.addView(nextButton)
        root.addView(navBar)

        setContentView(root)
        tutorialManager.setListener(this)
        updateStepUi(tutorialManager.getCurrentStepIndex(), tutorialManager.getCurrentStep())
    }

    private fun updateStepUi(index: Int, step: TutorialManager.TutorialStep) {
        stepIndicatorText.text = "SPATIAL XR GUIDE • STEP ${index + 1} OF ${tutorialManager.getStepCount()}"
        titleText.text = step.headline
        descriptionText.text = step.description
        hintText.text = "💡 ${step.interactionHint}"
        animatedView.setTopic(step.topic)

        prevButton.isEnabled = (index > 0)
        prevButton.visibility = if (index > 0) Button.VISIBLE else Button.INVISIBLE
        nextButton.text = if (index == tutorialManager.getStepCount() - 1) "Get Started" else "Next"
    }

    override fun onTutorialStepChanged(stepIndex: Int, step: TutorialManager.TutorialStep) {
        updateStepUi(stepIndex, step)
    }

    override fun onTutorialDismissed() {
        dismiss()
    }

    override fun onTutorialCompleted() {
        dismiss()
    }
}
