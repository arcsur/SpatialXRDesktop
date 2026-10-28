package com.spatial.xr.ai

import com.spatial.xr.window.SpatialWindowManager
import org.json.JSONArray
import org.json.JSONObject

class GeminiSpatialAgent(
    private val windowManager: SpatialWindowManager? = null,
    private var tutorialManager: com.spatial.xr.tutorial.TutorialManager? = null
) {

    enum class WakeTrigger {
        KEYWORD_HEY_GEMINI,
        FRAME_BUTTON_PRESS,
        THREE_FINGER_TAP
    }

    data class ToolCall(
        val name: String,
        val args: JSONObject
    )

    data class AgentResponse(
        val textResponse: String,
        val executedToolCalls: List<ToolCall> = emptyList()
    )

    interface SpatialToolExecutor {
        fun onLaunchApp(packageName: String, anchorMode: String): Boolean
        fun onRepositionWindow(windowId: Int, action: String): Boolean
        fun onOrganizeWorkspace(layoutStyle: String): Boolean
        fun onAnalyzeScene(query: String): String
        fun onShowTutorial(topic: String): Boolean
    }

    private var customExecutor: SpatialToolExecutor? = null

    fun setCustomExecutor(executor: SpatialToolExecutor?) {
        this.customExecutor = executor
    }

    fun setTutorialManager(manager: com.spatial.xr.tutorial.TutorialManager?) {
        this.tutorialManager = manager
    }

    /**
     * Tool declaration schema definitions for Gemini function calling.
     */
    fun getToolDeclarationsJson(): JSONArray {
        val tools = JSONArray()

        val launchApp = JSONObject().apply {
            put("name", "launch_app")
            put("description", "Launch an Android app in 3D spatial space.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().put("type", "STRING").put("description", "Android package name to launch"))
                    put("anchor_mode", JSONObject().put("type", "STRING").put("description", "WORLD_LOCKED or FLOATING"))
                })
                put("required", JSONArray().put("package_name"))
            })
        }

        val organizeWorkspace = JSONObject().apply {
            put("name", "organize_workspace")
            put("description", "Re-arrange open spatial windows.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("layout_style", JSONObject().put("type", "STRING").put("description", "GRID, CAROUSEL, or SIDE_BY_SIDE"))
                })
                put("required", JSONArray().put("layout_style"))
            })
        }

        val analyzeScene = JSONObject().apply {
            put("name", "analyze_screen_or_scene")
            put("description", "Inspect what the user is currently looking at through the glasses.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().put("type", "STRING").put("description", "User question about current view"))
                })
                put("required", JSONArray().put("query"))
            })
        }

        val showTutorial = JSONObject().apply {
            put("name", "show_usage_tutorial")
            put("description", "Display the animated graphical usage guide and tutorial explaining spatial gestures (pointing, pinch click, resizing, palm dock, air-swipe, voice).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("topic", JSONObject().put("type", "STRING").put("description", "Optional topic: 'pointer', 'resize', 'dock', 'swipe', 'gemini', or 'all'"))
                })
            })
        }

        tools.put(launchApp)
        tools.put(organizeWorkspace)
        tools.put(analyzeScene)
        tools.put(showTutorial)
        return tools
    }

    fun parseAndExecuteToolCall(toolCallJson: JSONObject): Boolean {
        val name = toolCallJson.optString("name")
        val args = toolCallJson.optJSONObject("args") ?: JSONObject()
        return executeSpatialTool(ToolCall(name, args))
    }

    fun executeSpatialTool(call: ToolCall): Boolean {
        customExecutor?.let { executor ->
            return when (call.name) {
                "launch_app" -> executor.onLaunchApp(
                    call.args.optString("package_name", "com.android.chrome"),
                    call.args.optString("anchor_mode", "FLOATING")
                )
                "reposition_window" -> executor.onRepositionWindow(
                    call.args.optInt("window_id", 1),
                    call.args.optString("action", "CENTER")
                )
                "organize_workspace" -> executor.onOrganizeWorkspace(
                    call.args.optString("layout_style", "CAROUSEL")
                )
                "analyze_screen_or_scene" -> {
                    executor.onAnalyzeScene(call.args.optString("query", "What am I looking at?"))
                    true
                }
                "show_usage_tutorial" -> {
                    val topic = call.args.optString("topic", "all")
                    executor.onShowTutorial(topic)
                }
                else -> false
            }
        }

        // Default integration with windowManager and tutorialManager
        return when (call.name) {
            "launch_app" -> {
                val pkg = call.args.optString("package_name", "com.android.chrome")
                val winId = (windowManager?.getAllWindows()?.size ?: 0) + 1
                windowManager?.createWindow(winId, pkg, winId)
                true
            }
            "organize_workspace" -> {
                // Layout windows side by side
                var x = -0.6f
                windowManager?.getAllWindows()?.forEach { win ->
                    win.position = SpatialWindowManager.Vector3(x, 0f, -1.2f)
                    x += 0.7f
                }
                true
            }
            "show_usage_tutorial" -> {
                val topic = call.args.optString("topic", "all")
                tutorialManager?.startTutorialForQuery(topic)
                true
            }
            else -> false
        }
    }

    /**
     * Inspect voice query for help/tutorial intent.
     */
    fun processVoiceIntent(query: String): AgentResponse {
        val q = query.trim().lowercase()
        val isHelp = q.contains("help") || q.contains("tutorial") || q.contains("how to") ||
                q.contains("how do i") || q.contains("how can i") || q.contains("instructions") || q.contains("guide")

        if (isHelp) {
            val topic = when {
                q.contains("pointer") || q.contains("pinch") || q.contains("click") -> "pointer"
                q.contains("resize") || q.contains("window") -> "resize"
                q.contains("palm") || q.contains("dock") || q.contains("apps") -> "dock"
                q.contains("swipe") || q.contains("cinema") -> "swipe"
                q.contains("voice") || q.contains("dictat") -> "gemini"
                else -> "all"
            }
            val call = ToolCall("show_usage_tutorial", JSONObject().put("topic", topic))
            executeSpatialTool(call)
            return AgentResponse(
                textResponse = "Opening the Spatial XR usage tutorial for you.",
                executedToolCalls = listOf(call)
            )
        }

        return AgentResponse(textResponse = "")
    }

    fun isWakeTriggerValid(trigger: WakeTrigger, audioKeyword: String? = null): Boolean {
        return when (trigger) {
            WakeTrigger.KEYWORD_HEY_GEMINI -> audioKeyword?.contains("hey gemini", ignoreCase = true) == true
            WakeTrigger.FRAME_BUTTON_PRESS -> true
            WakeTrigger.THREE_FINGER_TAP -> true
        }
    }
}
