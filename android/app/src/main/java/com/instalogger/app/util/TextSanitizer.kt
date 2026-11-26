package com.instalogger.app.util

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
