package com.spatial.xr.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class VisualQAPipeline {

    companion object {
        const val SYSTEM_PROMPT_PREFIX = "You are a spatial assistant. The user is looking at this image through AR glasses. Answer concisely in 2 sentences max."
    }

    data class MultimodalPayload(
        val promptText: String,
        val mimeType: String,
        val base64Data: String,
        val jsonPayload: JSONObject
    )

    fun createLookAndAskPayload(
        userQuestion: String = "What am I looking at?",
        imageBytes: ByteArray,
        mimeType: String = "image/webp"
    ): MultimodalPayload {
        val base64Encoded = Base64.getEncoder().encodeToString(imageBytes)
        val fullPrompt = "$SYSTEM_PROMPT_PREFIX\nUser Question: $userQuestion"

        val rootJson = JSONObject()
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()

        val textPart = JSONObject().apply {
            put("text", fullPrompt)
        }

        val inlineDataPart = JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", mimeType)
                put("data", base64Encoded)
            })
        }

        partsArray.put(textPart)
        partsArray.put(inlineDataPart)
        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        rootJson.put("contents", contentsArray)

        return MultimodalPayload(
            promptText = fullPrompt,
            mimeType = mimeType,
            base64Data = base64Encoded,
            jsonPayload = rootJson
        )
    }

    fun parseGeminiResponse(rawJsonResponse: String): String {
        return try {
            val root = JSONObject(rawJsonResponse)
            val candidates = root.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val firstPart = parts?.optJSONObject(0)
            firstPart?.optString("text") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
