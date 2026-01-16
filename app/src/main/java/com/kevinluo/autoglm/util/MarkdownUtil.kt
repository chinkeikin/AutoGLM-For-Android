package com.kevinluo.autoglm.util

/**
 * Utility object for Markdown text processing.
 * Provides functions to strip Markdown syntax for plain text display.
 */
object MarkdownUtil {
    /**
     * Strips common Markdown syntax from a string, converting it to plain text.
     * 
     * Handles:
     * - **bold** or __bold__
     * - *italic* or _italic_
     * - `code`
     * - # Headers
     * - [links](url)
     * - > blockquotes
     * - ~~strikethrough~~
     * - Lists (-, *, +, numbered)
     * 
     * @param text The Markdown text to strip
     * @return Plain text without Markdown syntax
     */
    fun stripMarkdown(text: String): String {
        return text
            // Bold: **text** or __text__
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            
            // Italic: *text* or _text_ (after bold to avoid conflicts)
            .replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
            .replace(Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)"), "$1")
            
            // Strikethrough: ~~text~~
            .replace(Regex("~~(.+?)~~"), "$1")
            
            // Inline code: `text`
            .replace(Regex("`(.+?)`"), "$1")
            
            // Headers: # Header or ## Header etc
            .replace(Regex("^#{1,6}\\s+(.*)$", RegexOption.MULTILINE), "$1")
            
            // Links: [text](url) -> text
            .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")
            
            // Blockquotes: > text
            .replace(Regex("^>\\s+(.*)$", RegexOption.MULTILINE), "$1")
            
            // Unordered list markers: -, *, +
            .replace(Regex("^[\\-\\*\\+]\\s+", RegexOption.MULTILINE), "• ")
            
            // Ordered list markers: 1. 2. etc
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            
            // Code blocks: ```code```
            .replace(Regex("```[\\s\\S]*?```"), "")
            
            // Horizontal rules: ---, ***, ___
            .replace(Regex("^([-*_]){3,}$", RegexOption.MULTILINE), "")
            
            // Clean up excessive whitespace
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

/**
 * Extension function to strip Markdown syntax from a String.
 * 
 * @receiver The Markdown text to strip
 * @return Plain text without Markdown syntax
 */
fun String.stripMarkdown(): String = MarkdownUtil.stripMarkdown(this)
