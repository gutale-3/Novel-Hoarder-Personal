package com.example.util

/**
 * Turns "the chapter was too short" into "this selector matched nothing", which is what a broken
 * plugin almost always means after a site redesign.
 */
object SelectorDiagnostics {

    /** JS returning `{ name: count }`, with -1 for a selector the browser rejects as invalid. */
    fun countScript(selectors: Map<String, String>): String {
        val entries = selectors.filterValues { it.isNotBlank() }.entries.joinToString(",") { (name, sel) ->
            "[${JsResultParser.jsLiteral(name)}, ${JsResultParser.jsLiteral(sel)}]"
        }
        return """
            (() => {
                const out = {};
                [$entries].forEach(([name, sel]) => {
                    try { out[name] = document.querySelectorAll(sel).length; }
                    catch (e) { out[name] = -1; }
                });
                return out;
            })()
        """.trimIndent()
    }

    /** Human-readable summary of the selectors that missed, or null when they all matched. */
    fun describeMisses(raw: String?): String? {
        val obj = JsResultParser.toJsonObject(raw) ?: return null
        val problems = obj.keys().asSequence().mapNotNull { name ->
            when (obj.optInt(name, 0)) {
                0 -> "$name matched nothing"
                -1 -> "$name is not a valid CSS selector"
                else -> null
            }
        }.toList()
        return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
