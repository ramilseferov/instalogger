package com.instalogger.app.util

/**
 * Robust string sanitizer to eliminate raw JSON escape sequences (\n, \r, \t, \", \'),
 * erroneous `/n` markers, HTML entities (&amp;, &quot;, &#39;), and normalize line breaks.
 */
fun String?.cleanDisplayText(): String {
    if (this == null) return ""
    var text = this

    // Normalize newlines and literal escape sequences
    text = text
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\r", "\n")
        .replace("/n", "\n") // Specific user request: "/n kimi yazılar"
        .replace("\\t", "    ")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    // Collapse multiple blank lines to at most 2 newlines
    text = text.replace(Regex("\n{3,}"), "\n\n")

    return text.trim()
}
