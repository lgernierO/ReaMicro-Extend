package com.reamicro.fix.hook

import org.json.JSONObject

internal data class ReaderSessionPreferences(
    val lightBackgroundStyle: String = "",
    val darkBackgroundStyle: String = "",
    val sentenceQueryTemplateSuffix: String = "是什么意思？",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lightBackgroundStyle", lightBackgroundStyle)
        put("darkBackgroundStyle", darkBackgroundStyle)
        put("sentenceQueryTemplateSuffix", sentenceQueryTemplateSuffix)
    }

    fun preferenceValues(): Map<String, String> = mapOf(
        "getBACKGROUND_LIGHT_STYLE" to lightBackgroundStyle,
        "getBACKGROUND_DARK_STYLE" to darkBackgroundStyle,
        "getSENTENCE_QUERY_TEMPLATE_SUFFIX" to sentenceQueryTemplateSuffix,
    )

    companion object {
        fun fromJson(json: JSONObject?): ReaderSessionPreferences = ReaderSessionPreferences(
            lightBackgroundStyle = json?.optString("lightBackgroundStyle", "") ?: "",
            darkBackgroundStyle = json?.optString("darkBackgroundStyle", "") ?: "",
            sentenceQueryTemplateSuffix = json?.optString("sentenceQueryTemplateSuffix", "是什么意思？") ?: "是什么意思？",
        )

        fun capture(read: (String) -> Any?): ReaderSessionPreferences = ReaderSessionPreferences(
            lightBackgroundStyle = read("getBACKGROUND_LIGHT_STYLE") as? String ?: "",
            darkBackgroundStyle = read("getBACKGROUND_DARK_STYLE") as? String ?: "",
            sentenceQueryTemplateSuffix = read("getSENTENCE_QUERY_TEMPLATE_SUFFIX") as? String ?: "是什么意思？",
        )
    }
}
