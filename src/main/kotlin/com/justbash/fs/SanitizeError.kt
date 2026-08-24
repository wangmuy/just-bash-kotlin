package com.justbash.fs

/**
 * Error message sanitization — replaces real OS paths with `<path>` to prevent
 * host filesystem information leakage.
 *
 * Port of just-bash `src/fs/sanitize-error.ts`. Pure utility, no I/O dependencies.
 */
object SanitizeError {

    private val UNIX_PATH_PREFIXES = Regex(
        "/(?:Users|home|private|var|opt|Library|System|usr|etc|tmp|nix|snap|workspace|root|srv|mnt|app)\\b[^\\s'\"`,\\)}\\]\\:]*"
    )
    private val WINDOWS_PATH = Regex("[A-Z]:\\\\[^\\s'\"`,\\)}\\]\\:]+")
    private val STACK_TRACE_LINE = Regex("\n\\s+at\\s.*")

    /**
     * Replace real OS paths in error messages with `<path>`.
     * Preserves virtual paths that don't match common host prefixes.
     */
    fun sanitize(message: String): String {
        if (message.isEmpty()) return message
        var sanitized = message.replace(STACK_TRACE_LINE, "")
        sanitized = sanitized.replace(UNIX_PATH_PREFIXES, "<path>")
        sanitized = sanitized.replace(WINDOWS_PATH, "<path>")
        return sanitized
    }
}