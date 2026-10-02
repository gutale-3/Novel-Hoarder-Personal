package com.example.util

/**
 * Strips repetitive header and footer watermarks, promotional links,
 * translator/editor notices, aggregator signatures, and user-specified noisy patterns
 * from novel chapters to provide a clean reading and listening experience.
 */
object WatermarkCleaner {

    private val BUILTIN_WATERMARK_PATTERNS = listOf(
        // Aggregator / site promotion watermarks
        Regex("(?i).*visit\\s+[\\w.-]+\\.(?:com|org|net|me|xyz|io|site|top|cc|info).*for\\s+(?:the\\s+)?fastest\\s+update.*"),
        Regex("(?i).*read\\s+(?:this\\s+novel\\s+)?(?:latest\\s+chapters?\\s+)?at\\s+[\\w.-]+\\.(?:com|org|net|me|xyz|io|site|top).*"),
        Regex("(?i).*find\\s+authorized\\s+novels\\s+in\\s+webnovel.*"),
        Regex("(?i).*this\\s+chapter\\s+is\\s+updated\\s+by\\s+[\\w.-]+.*"),
        Regex("(?i).*\\[please\\s+note\\s+that\\s+this\\s+chapter\\s+was\\s+scraped.*\\].*"),
        Regex("(?i).*this\\s+novel\\s+has\\s+been\\s+translated\\s+by.*"),

        // Support / Patreon / Discord links
        Regex("(?i).*support\\s+(?:the\\s+author|the\\s+translator|me)\\s+(?:on|at)\\s+patreon.*"),
        Regex("(?i).*join\\s+(?:our\\s+)?discord\\s*(?:server)?:?\\s*https?://\\S+.*"),
        Regex("(?i).*https?://discord\\.(?:gg|com)/\\S+.*"),
        Regex("(?i).*https?://(?:www\\.)?patreon\\.com/\\S+.*"),

        // Error report / comments spam
        Regex("(?i).*if\\s+you\\s+find\\s+any\\s+errors?\\s*\\(.*\\),\\s*please\\s+let\\s+us\\s+know.*"),
        Regex("(?i).*please\\s+report\\s+any\\s+errors?\\s+in\\s+the\\s+comment\\s+section.*"),

        // Pure repetitive divider lines
        Regex("^\\s*[*_~=-]{3,}\\s*$")
    )

    /**
     * Cleans a list of chapter paragraphs.
     * Header and footer regions (first 4 and last 4 paragraphs) are aggressively matched against patterns.
     * Custom user phrases are stripped from any position in the text.
     */
    fun cleanParagraphs(
        paragraphs: List<String>,
        enabled: Boolean = true,
        customPhrases: List<String> = emptyList()
    ): List<String> {
        if (!enabled || paragraphs.isEmpty()) return paragraphs

        val validCustomPatterns = customPhrases
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { phrase ->
                try {
                    Regex("(?i)" + Regex.escape(phrase))
                } catch (e: Exception) {
                    null
                }
            }

        val total = paragraphs.size

        return paragraphs.filterIndexed { index, para ->
            val trimmed = para.trim()
            if (trimmed.isEmpty()) return@filterIndexed false

            // Check custom patterns first (applies everywhere)
            val matchesCustom = validCustomPatterns.any { it.containsMatchIn(trimmed) }
            if (matchesCustom) {
                return@filterIndexed false
            }

            // Built-in patterns: check header/footer zone (first 4, last 4 paragraphs)
            val isHeaderOrFooterZone = index < 4 || index >= (total - 4)
            val matchesBuiltin = BUILTIN_WATERMARK_PATTERNS.any { it.matches(trimmed) || it.containsMatchIn(trimmed) }

            if (matchesBuiltin) {
                if (isHeaderOrFooterZone) {
                    // Stripped because it is in the header or footer
                    false
                } else {
                    // In the middle of chapter, only strip if it's short standalone promotional noise (< 200 chars)
                    trimmed.length < 200
                }
            } else {
                true
            }
        }
    }

    /**
     * Cleans a raw chapter text block by splitting into paragraphs, filtering watermarks, and rejoining.
     */
    fun cleanChapterContent(
        content: String,
        enabled: Boolean = true,
        customPhrases: List<String> = emptyList()
    ): String {
        if (!enabled || content.isBlank()) return content
        val rawParagraphs = content.split("\n")
        val cleaned = cleanParagraphs(rawParagraphs, enabled, customPhrases)
        return cleaned.joinToString("\n")
    }
}
