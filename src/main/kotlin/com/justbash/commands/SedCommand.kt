package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Port of just-bash `src/commands/sed/` sources (types.ts, lexer.ts, parser.ts,
 * sed-regex.ts, executor.ts, sed.ts) merged into a single Kotlin file.
 *
 * Faithful port: all sed commands, address types, and substitution flags are
 * preserved. BRE/ERE conversion and POSIX character classes are translated
 * onto `java.util.regex.Pattern` (see [breToEre]). Execution is synchronous
 * (no coroutines). The TypeScript error subsystem is replaced by [SedError].
 */

private class SedError(override val message: String) : RuntimeException(message)

// ---------------------------------------------------------------------------
// types.ts
// ---------------------------------------------------------------------------

private sealed class SedAddress {
    data class Number(val n: Int) : SedAddress()
    object Dollar : SedAddress()
    data class Pattern(val pattern: String) : SedAddress()
    data class Step(val first: Int, val step: Int) : SedAddress()
    data class Relative(val offset: Int) : SedAddress()
}

private class AddressRange(
    val start: SedAddress? = null,
    val end: SedAddress? = null,
    var negated: Boolean = false,
)

private sealed class SedCmd {
    abstract val address: AddressRange?

    class Substitute(
        override val address: AddressRange?,
        val pattern: String,
        val replacement: String,
        val global: Boolean,
        val ignoreCase: Boolean,
        val printOnMatch: Boolean,
        val nthOccurrence: Int?,
        val extendedRegex: Boolean,
    ) : SedCmd()

    class Print(override val address: AddressRange?) : SedCmd()
    class PrintFirstLine(override val address: AddressRange?) : SedCmd()
    class Delete(override val address: AddressRange?) : SedCmd()
    class DeleteFirstLine(override val address: AddressRange?) : SedCmd()
    class Append(override val address: AddressRange?, val text: String) : SedCmd()
    class Insert(override val address: AddressRange?, val text: String) : SedCmd()
    class Change(override val address: AddressRange?, val text: String) : SedCmd()
    class Hold(override val address: AddressRange?) : SedCmd()
    class HoldAppend(override val address: AddressRange?) : SedCmd()
    class Get(override val address: AddressRange?) : SedCmd()
    class GetAppend(override val address: AddressRange?) : SedCmd()
    class Exchange(override val address: AddressRange?) : SedCmd()
    class Next(override val address: AddressRange?) : SedCmd()
    class NextAppend(override val address: AddressRange?) : SedCmd()
    class Quit(override val address: AddressRange?, val exitCode: Int? = null) : SedCmd()
    class QuitSilent(override val address: AddressRange?, val exitCode: Int? = null) : SedCmd()
    class Transliterate(override val address: AddressRange?, val source: String, val dest: String) : SedCmd()
    class LineNumber(override val address: AddressRange?) : SedCmd()
    class Branch(override val address: AddressRange?, val label: String?) : SedCmd()
    class BranchOnSubst(override val address: AddressRange?, val label: String?) : SedCmd()
    class BranchOnNoSubst(override val address: AddressRange?, val label: String?) : SedCmd()
    class Label(val name: String) : SedCmd() {
        override val address: AddressRange? get() = null
    }
    class Zap(override val address: AddressRange?) : SedCmd()
    class Group(override val address: AddressRange?, val commands: List<SedCmd>) : SedCmd()
    class ListCmd(override val address: AddressRange?) : SedCmd()
    class PrintFilename(override val address: AddressRange?) : SedCmd()
    class Version(override val address: AddressRange?, val minVersion: String?) : SedCmd()
    class ReadFile(override val address: AddressRange?, val filename: String) : SedCmd()
    class ReadFileLine(override val address: AddressRange?, val filename: String) : SedCmd()
    class WriteFile(override val address: AddressRange?, val filename: String) : SedCmd()
    class WriteFirstLine(override val address: AddressRange?, val filename: String) : SedCmd()
    class Execute(override val address: AddressRange?, val command: String?) : SedCmd()
}

private class RangeState {
    var active: Boolean = false
    var startLine: Int? = null
    var completed: Boolean = false
}

private data class PendingFileRead(val filename: String, val wholeFile: Boolean)
private data class PendingFileWrite(val filename: String, val content: String)

private class SedState {
    var patternSpace: String = ""
    var holdSpace: String = ""
    var lineNumber: Int = 0
    var totalLines: Int = 0
    var deleted: Boolean = false
    var printed: Boolean = false
    var quit: Boolean = false
    var quitSilent: Boolean = false
    var exitCode: Int? = null
    var errorMessage: String? = null
    val appendBuffer: MutableList<String> = ArrayList()
    var changedText: String? = null
    var substitutionMade: Boolean = false
    val lineNumberOutput: MutableList<String> = ArrayList()
    val nCommandOutput: MutableList<String> = ArrayList()
    var restartCycle: Boolean = false
    var inDRestartedCycle: Boolean = false
    var currentFilename: String? = null
    val pendingFileReads: MutableList<PendingFileRead> = ArrayList()
    val pendingFileWrites: MutableList<PendingFileWrite> = ArrayList()
    val rangeStates: MutableMap<String, RangeState> = LinkedHashMap()
    var lastPattern: String? = null
    var branchRequest: String? = null
    var linesConsumedInCycle: Int = 0
}

private class SedExecutionLimits(val maxIterations: Long, val maxStringLength: Long)

private val POSIX_CLASSES: Map<String, String> = mapOf(
    "alnum" to "a-zA-Z0-9",
    "alpha" to "a-zA-Z",
    "ascii" to "\\x00-\\x7F",
    "blank" to " \\t",
    "cntrl" to "\\x00-\\x1F\\x7F",
    "digit" to "0-9",
    "graph" to "!-~",
    "lower" to "a-z",
    "print" to " -~",
    "punct" to "!-/:-@\\[-`{-~",
    "space" to " \\t\\n\\r\\f\\v",
    "upper" to "A-Z",
    "word" to "a-zA-Z0-9_",
    "xdigit" to "0-9A-Fa-f",
)

private fun breToEre(pattern: String): String {
    val result = StringBuilder()
    var i = 0
    var inBracket = false
    val n = pattern.length
    while (i < n) {
        val ch = pattern[i]
        if (ch == '[' && !inBracket) {
            if (i + 1 < n && pattern[i + 1] == '[' && i + 2 < n && pattern[i + 2] == ':') {
                val closeIdx = pattern.indexOf(":]]", i + 3)
                if (closeIdx != -1) {
                    val className = pattern.substring(i + 3, closeIdx)
                    val js = POSIX_CLASSES[className]
                    if (js != null) {
                        result.append('[').append(js).append(']')
                        i = closeIdx + 3
                        continue
                    }
                }
            }
            if (i + 3 < n && pattern[i + 1] == '^' && pattern[i + 2] == '[' && pattern[i + 3] == ':') {
                val closeIdx = pattern.indexOf(":]]", i + 4)
                if (closeIdx != -1) {
                    val className = pattern.substring(i + 4, closeIdx)
                    val js = POSIX_CLASSES[className]
                    if (js != null) {
                        result.append("[^").append(js).append(']')
                        i = closeIdx + 3
                        continue
                    }
                }
            }
            result.append('[')
            i++
            inBracket = true
            if (i < n && pattern[i] == '^') { result.append('^'); i++ }
            if (i < n && pattern[i] == ']') { result.append("\\]"); i++ }
            continue
        }
        if (inBracket) {
            if (pattern[i] == ']') { result.append(']'); i++; inBracket = false; continue }
            if (pattern[i] == '[' && i + 1 < n && pattern[i + 1] == ':') {
                val closeIdx = pattern.indexOf(":]", i + 2)
                if (closeIdx != -1) {
                    val className = pattern.substring(i + 2, closeIdx)
                    val js = POSIX_CLASSES[className]
                    if (js != null) { result.append(js); i = closeIdx + 2; continue }
                }
            }
            if (pattern[i] == '\\' && i + 1 < n) { result.append(pattern[i]).append(pattern[i + 1]); i += 2; continue }
            result.append(pattern[i]); i++; continue
        }
        if (pattern[i] == '\\') {
            if (i + 1 < n) {
                val next = pattern[i + 1]
                when (next) {
                    '+', '?', '|', '(', ')', '{', '}' -> { result.append(next); i += 2; continue }
                    't' -> { result.append('\t'); i += 2; continue }
                    'n' -> { result.append('\n'); i += 2; continue }
                    'r' -> { result.append('\r'); i += 2; continue }
                }
                result.append(pattern[i]).append(next); i += 2; continue
            }
        }
        if (ch == '+' || ch == '?' || ch == '|' || ch == '(' || ch == ')') { result.append('\\').append(ch); i++; continue }
        if (ch == '^') {
            val isAnchor = result.isEmpty() || result.endsWith("(")
            if (!isAnchor) { result.append("\\^"); i++; continue }
        }
        if (ch == '$') {
            val isEnd = i == n - 1
            val beforeGroupClose = i + 2 < n && pattern[i + 1] == '\\' && pattern[i + 2] == ')'
            if (!isEnd && !beforeGroupClose) { result.append("\\$"); i++; continue }
        }
        result.append(ch); i++
    }
    return result.toString()
}

private fun normalizeForJs(pattern: String): String {
    val result = StringBuilder()
    var inBracket = false
    var i = 0
    val n = pattern.length
    while (i < n) {
        if (pattern[i] == '[' && !inBracket) {
            inBracket = true
            result.append('[')
            i++
            if (i < n && pattern[i] == '^') { result.append('^'); i++ }
            if (i < n && pattern[i] == ']') { result.append(']'); i++ }
            continue
        } else if (pattern[i] == ']' && inBracket) {
            inBracket = false
            result.append(']')
        } else if (!inBracket && pattern[i] == '{' && pattern[i + 1] == ',') {
            result.append("{0,")
            i++
        } else {
            result.append(pattern[i])
        }
        i++
    }
    return result.toString()
}

private fun escapeForList(input: String): String {
    val result = StringBuilder()
    for (ch in input) {
        val code = ch.code
        when {
            ch == '\\' -> result.append("\\\\")
            ch == '\t' -> result.append("\\t")
            ch == '\n' -> result.append("$\n")
            ch == '\r' -> result.append("\\r")
            ch == '\u0007' -> result.append("\\a")
            ch == '\b' -> result.append("\\b")
            ch == '\u000C' -> result.append("\\f")
            ch == '\u000B' -> result.append("\\v")
            code < 32 || code >= 127 -> result.append('\\').append(Integer.toOctalString(code).padStart(3, '0'))
            else -> result.append(ch)
        }
    }
    result.append('$')
    return result.toString()
}

private enum class SedTokenType {
    NUMBER, DOLLAR, PATTERN, STEP, RELATIVE_OFFSET,
    LBRACE, RBRACE, SEMICOLON, NEWLINE, COMMA, NEGATION,
    COMMAND, SUBSTITUTE, TRANSLITERATE, LABEL_DEF, BRANCH,
    BRANCH_ON_SUBST, BRANCH_ON_NO_SUBST, TEXT_CMD, FILE_READ,
    FILE_READ_LINE, FILE_WRITE, FILE_WRITE_LINE, EXECUTE, VERSION,
    EOF, ERROR,
}

private class SedToken(
    val type: SedTokenType,
    val value: String,
    val line: Int,
    val column: Int,
    val pattern: String? = null,
    val replacement: String? = null,
    val flags: String? = null,
    val source: String? = null,
    val dest: String? = null,
    val text: String? = null,
    val label: String? = null,
    val filename: String? = null,
    val command: String? = null,
    val first: Int? = null,
    val step: Int? = null,
    val offset: Int? = null,
)

private class SedLexer(
    input: String,
    maxInputLength: Long = 1024L * 1024L,
    maxTokens: Long = 100_000L,
) {
    private val input = input
    private val maxInputLength = maxInputLength
    private val maxTokens = maxTokens
    private var pos = 0
    private var line = 1
    private var column = 1

    fun tokenize(): List<SedToken> {
        if (input.length.toLong() > maxInputLength) throw SedError("sed: script size limit exceeded ($maxInputLength bytes)")
        val tokens = ArrayList<SedToken>()
        while (pos < input.length) {
            val token = nextToken()
            if (token != null) {
                if (tokens.size.toLong() >= maxTokens - 1) throw SedError("sed: token limit exceeded ($maxTokens)")
                tokens.add(token)
            }
        }
        tokens.add(SedToken(SedTokenType.EOF, "", line, column))
        return tokens
    }

    private fun peek(offset: Int = 0): Char = if (pos + offset < input.length) input[pos + offset] else '\u0000'

    private fun advance(): Char {
        val ch = if (pos < input.length) input[pos++] else '\u0000'
        if (ch == '\n') { line++; column = 1 } else column++
        return ch
    }

    private fun readEscapedString(delimiter: Char): String? {
        val result = StringBuilder()
        while (pos < input.length && peek() != delimiter) {
            when {
                peek() == '\\' -> {
                    advance()
                    val escaped = advance()
                    result.append(when (escaped) { 'n' -> '\n'; 't' -> '\t'; else -> escaped })
                }
                peek() == '\n' -> return null
                else -> result.append(advance())
            }
        }
        return result.toString()
    }

    private fun skipWhitespace() {
        while (pos < input.length) {
            val ch = peek()
            when {
                ch == ' ' || ch == '\t' || ch == '\r' -> advance()
                ch == '#' -> { while (pos < input.length && peek() != '\n') advance() }
                else -> break
            }
        }
    }

    private fun nextToken(): SedToken? {
        skipWhitespace()
        if (pos >= input.length) return null
        val sl = line
        val sc = column
        val ch = peek()
        if (ch == '\n') { advance(); return SedToken(SedTokenType.NEWLINE, "\n", sl, sc) }
        if (ch == ';') { advance(); return SedToken(SedTokenType.SEMICOLON, ";", sl, sc) }
        if (ch == '{') { advance(); return SedToken(SedTokenType.LBRACE, "{", sl, sc) }
        if (ch == '}') { advance(); return SedToken(SedTokenType.RBRACE, "}", sl, sc) }
        if (ch == ',') { advance(); return SedToken(SedTokenType.COMMA, ",", sl, sc) }
        if (ch == '!') { advance(); return SedToken(SedTokenType.NEGATION, "!", sl, sc) }
        if (ch == '$') { advance(); return SedToken(SedTokenType.DOLLAR, "$", sl, sc) }
        if (isDigit(ch)) return readNumber()
        if (ch == '+' && isDigit(peek(1))) return readRelativeOffset()
        if (ch == '/') return readPattern()
        if (ch == ':') return readLabelDef()
        return readCommand()
    }

    private fun readNumber(): SedToken {
        val sl = line; val sc = column
        val numStr = StringBuilder()
        while (isDigit(peek())) numStr.append(advance())
        if (peek() == '~') {
            advance()
            val stepStr = StringBuilder()
            while (isDigit(peek())) stepStr.append(advance())
            val first = numStr.toString().toInt()
            val step = stepStr.toString().toIntOrNull() ?: 0
            return SedToken(SedTokenType.STEP, "$first~$step", sl, sc, first = first, step = step)
        }
        val num = numStr.toString().toInt()
        return SedToken(SedTokenType.NUMBER, num.toString(), sl, sc, first = num)
    }

    private fun readRelativeOffset(): SedToken {
        val sl = line; val sc = column
        advance()
        val numStr = StringBuilder()
        while (isDigit(peek())) numStr.append(advance())
        val offset = numStr.toString().toIntOrNull() ?: 0
        return SedToken(SedTokenType.RELATIVE_OFFSET, "+$offset", sl, sc, offset = offset)
    }

    private fun readPattern(): SedToken {
        val sl = line; val sc = column
        advance()
        val pattern = StringBuilder()
        var inBracket = false
        while (pos < input.length) {
            val ch = peek()
            if (ch == '/' && !inBracket) break
            when {
                ch == '\\' -> {
                    pattern.append(advance())
                    if (pos < input.length && peek() != '\n') pattern.append(advance())
                }
                ch == '\n' -> break
                ch == '[' && !inBracket -> {
                    inBracket = true
                    pattern.append(advance())
                    if (peek() == '^') pattern.append(advance())
                    if (peek() == ']') pattern.append(advance())
                }
                ch == ']' && inBracket -> { inBracket = false; pattern.append(advance()) }
                else -> pattern.append(advance())
            }
        }
        if (peek() == '/') advance()
        val p = pattern.toString()
        return SedToken(SedTokenType.PATTERN, p, sl, sc, pattern = p)
    }

    private fun readLabelDef(): SedToken {
        val sl = line; val sc = column
        advance()
        while (peek() == ' ' || peek() == '\t') advance()
        val label = StringBuilder()
        while (pos < input.length) {
            val ch = peek()
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == ';' || ch == '}' || ch == '{') break
            label.append(advance())
        }
        val value = label.toString()
        return SedToken(SedTokenType.LABEL_DEF, value, sl, sc, label = value)
    }

    private fun readCommand(): SedToken {
        val sl = line; val sc = column
        val ch = advance()
        return when (ch) {
            's' -> readSubstitute(sl, sc)
            'y' -> readTransliterate(sl, sc)
            'a', 'i', 'c' -> readTextCommand(ch, sl, sc)
            'b' -> readBranch(SedTokenType.BRANCH, "b", sl, sc)
            't' -> readBranch(SedTokenType.BRANCH_ON_SUBST, "t", sl, sc)
            'T' -> readBranch(SedTokenType.BRANCH_ON_NO_SUBST, "T", sl, sc)
            'r' -> readFileCommand(SedTokenType.FILE_READ, "r", sl, sc)
            'R' -> readFileCommand(SedTokenType.FILE_READ_LINE, "R", sl, sc)
            'w' -> readFileCommand(SedTokenType.FILE_WRITE, "w", sl, sc)
            'W' -> readFileCommand(SedTokenType.FILE_WRITE_LINE, "W", sl, sc)
            'e' -> readExecute(sl, sc)
            'p', 'P', 'd', 'D', 'h', 'H', 'g', 'G', 'x', 'n', 'N', 'q', 'Q', 'z', '=', 'l', 'F' ->
                SedToken(SedTokenType.COMMAND, ch.toString(), sl, sc)
            'v' -> readVersion(sl, sc)
            else -> SedToken(SedTokenType.ERROR, ch.toString(), sl, sc)
        }
    }

    private fun readSubstitute(sl: Int, sc: Int): SedToken {
        val delimiter = advance()
        if (delimiter == '\u0000' || delimiter == '\n') return SedToken(SedTokenType.ERROR, "s", sl, sc)

        val pattern = StringBuilder()
        var inBracket = false
        while (pos < input.length) {
            val ch = peek()
            if (ch == delimiter && !inBracket) break
            when {
                ch == '\\' -> {
                    advance()
                    if (pos < input.length && peek() != '\n') {
                        val escaped = peek()
                        if (escaped == delimiter && !inBracket) pattern.append(advance())
                        else { pattern.append('\\'); pattern.append(advance()) }
                    } else pattern.append('\\')
                }
                ch == '\n' -> break
                ch == '[' && !inBracket -> {
                    inBracket = true
                    pattern.append(advance())
                    if (peek() == '^') pattern.append(advance())
                    if (peek() == ']') pattern.append(advance())
                }
                ch == ']' && inBracket -> { inBracket = false; pattern.append(advance()) }
                else -> pattern.append(advance())
            }
        }
        if (peek() != delimiter) return SedToken(SedTokenType.ERROR, "unterminated substitution pattern", sl, sc)
        advance()

        val replacement = StringBuilder()
        while (pos < input.length && peek() != delimiter) {
            when {
                peek() == '\\' -> {
                    advance()
                    if (pos < input.length) {
                        val next = peek()
                        if (next == '\\') {
                            advance()
                            if (pos < input.length && peek() == '\n') { replacement.append('\n'); advance() }
                            else replacement.append('\\')
                        } else if (next == '\n') { replacement.append('\n'); advance() }
                        else replacement.append('\\').append(advance())
                    } else replacement.append('\\')
                }
                peek() == '\n' -> break
                else -> replacement.append(advance())
            }
        }
        if (peek() == delimiter) advance()

        val flags = StringBuilder()
        while (pos < input.length) {
            val ch = peek()
            if (ch == 'g' || ch == 'i' || ch == 'p' || ch == 'I' || isDigit(ch)) flags.append(advance()) else break
        }
        val p = pattern.toString(); val r = replacement.toString(); val f = flags.toString()
        return SedToken(SedTokenType.SUBSTITUTE, "s$delimiter$p$delimiter$r$delimiter$f", sl, sc, pattern = p, replacement = r, flags = f)
    }

    private fun readTransliterate(sl: Int, sc: Int): SedToken {
        val delimiter = advance()
        if (delimiter == '\u0000' || delimiter == '\n') return SedToken(SedTokenType.ERROR, "y", sl, sc)
        val source = readEscapedString(delimiter)
        if (source == null || peek() != delimiter) return SedToken(SedTokenType.ERROR, "unterminated transliteration source", sl, sc)
        advance()
        val dest = readEscapedString(delimiter)
        if (dest == null || peek() != delimiter) return SedToken(SedTokenType.ERROR, "unterminated transliteration dest", sl, sc)
        advance()
        var nextChar = peek()
        while (nextChar == ' ' || nextChar == '\t') { advance(); nextChar = peek() }
        if (nextChar != '\u0000' && nextChar != ';' && nextChar != '\n' && nextChar != '}') {
            return SedToken(SedTokenType.ERROR, "extra text at the end of a transform command", sl, sc)
        }
        return SedToken(SedTokenType.TRANSLITERATE, "y$delimiter$source$delimiter$dest$delimiter", sl, sc, source = source, dest = dest)
    }

    private fun readTextCommand(cmd: Char, sl: Int, sc: Int): SedToken {
        // Consume optional backslash (separator) and trailing whitespace
        if (peek() == '\\') advance()
        if (peek() == ' ' || peek() == '\t') advance()
        // Skip newline after backslash (multi-line text)
        if (peek() == '\n') advance()

        val text = StringBuilder()
        while (pos < input.length) {
            val ch = peek()
            if (ch == '\n') {
                if (text.endsWith("\\")) { text.deleteCharAt(text.length - 1); text.append('\n'); advance(); continue }
                break
            }
            if (ch == '\\' && pos + 1 < input.length) {
                val next = input[pos + 1]
                if (next == 'n') { text.append('\n'); advance(); advance(); continue }
                if (next == 't') { text.append('\t'); advance(); advance(); continue }
                if (next == 'r') { text.append('\r'); advance(); advance(); continue }
            }
            text.append(advance())
        }
        return SedToken(SedTokenType.TEXT_CMD, cmd.toString(), sl, sc, text = text.toString())
    }

    private fun readBranch(type: SedTokenType, cmd: String, sl: Int, sc: Int): SedToken {
        while (peek() == ' ' || peek() == '\t') advance()
        val label = StringBuilder()
        while (pos < input.length) {
            val ch = peek()
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == ';' || ch == '}' || ch == '{') break
            label.append(advance())
        }
        return SedToken(type, cmd, sl, sc, label = label.toString().ifEmpty { null })
    }

    private fun readVersion(sl: Int, sc: Int): SedToken {
        while (peek() == ' ' || peek() == '\t') advance()
        val version = StringBuilder()
        while (pos < input.length) {
            val ch = peek()
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == ';' || ch == '}' || ch == '{') break
            version.append(advance())
        }
        return SedToken(SedTokenType.VERSION, "v", sl, sc, label = version.toString().ifEmpty { null })
    }

    private fun readFileCommand(type: SedTokenType, cmd: String, sl: Int, sc: Int): SedToken {
        while (peek() == ' ' || peek() == '\t') advance()
        val filename = StringBuilder()
        while (pos < input.length) {
            val ch = peek()
            if (ch == '\n' || ch == ';') break
            filename.append(advance())
        }
        return SedToken(type, cmd, sl, sc, filename = filename.toString().trim())
    }

    private fun readExecute(sl: Int, sc: Int): SedToken {
        while (peek() == ' ' || peek() == '\t') advance()
        val command = StringBuilder()
        while (pos < input.length) {
            val ch = peek()
            if (ch == '\n' || ch == ';') break
            command.append(advance())
        }
        return SedToken(SedTokenType.EXECUTE, "e", sl, sc, command = command.toString().trim().ifEmpty { null })
    }

    private fun isDigit(ch: Char): Boolean = ch in '0'..'9'
}

private class SedParser(
    private val scripts: List<String>,
    extendedRegex: Boolean = false,
    private val limits: SedExecutionLimits? = null,
) {
    private var tokens: List<SedToken> = emptyList()
    private var pos = 0
    private val extendedRegex = extendedRegex

    fun parse(): List<SedCmd> {
        val allCommands = ArrayList<SedCmd>()
        for (script in scripts) {
            val lexer = SedLexer(script, limits?.maxStringLength ?: (1024L * 1024L), limits?.maxStringLength ?: 100_000L)
            tokens = lexer.tokenize()
            pos = 0
            while (!isAtEnd()) {
                if (check(SedTokenType.NEWLINE) || check(SedTokenType.SEMICOLON)) { advance(); continue }
                val posBefore = pos
                val (cmd, error) = parseCommand()
                if (error != null) throw SedError(error)
                if (cmd != null) allCommands.add(cmd)
                if (pos == posBefore && !isAtEnd()) throw SedError("unknown command: '${peek().value.ifEmpty { peek().type.name }}'")
            }
        }
        val labelError = validateLabels(allCommands)
        if (labelError != null) throw SedError(labelError)
        return allCommands
    }

    private fun parseCommand(): Pair<SedCmd?, String?> {
        val addressResult = parseAddressRange()
        if (addressResult != null && addressResult.second != null) return Pair(null, addressResult.second)
        val address = addressResult?.first

        if (check(SedTokenType.NEGATION)) { advance(); if (address != null) address.negated = true }

        while (check(SedTokenType.NEWLINE) || check(SedTokenType.SEMICOLON)) advance()

        if (isAtEnd()) {
            if (address != null && (address.start != null || address.end != null)) return Pair(null, "command expected")
            return Pair(null, null)
        }

        val token = peek()
        return when (token.type) {
            SedTokenType.COMMAND -> { advance(); parseSimpleCommand(token, address) }
            SedTokenType.SUBSTITUTE -> { advance(); parseSubstituteFromToken(token, address) }
            SedTokenType.TRANSLITERATE -> { advance(); parseTransliterateFromToken(token, address) }
            SedTokenType.LABEL_DEF -> { advance(); Pair(SedCmd.Label(token.label ?: ""), null) }
            SedTokenType.BRANCH -> { advance(); Pair(SedCmd.Branch(address, token.label), null) }
            SedTokenType.BRANCH_ON_SUBST -> { advance(); Pair(SedCmd.BranchOnSubst(address, token.label), null) }
            SedTokenType.BRANCH_ON_NO_SUBST -> { advance(); Pair(SedCmd.BranchOnNoSubst(address, token.label), null) }
            SedTokenType.TEXT_CMD -> { advance(); parseTextCommand(token, address) }
            SedTokenType.FILE_READ -> { advance(); Pair(SedCmd.ReadFile(address, token.filename ?: ""), null) }
            SedTokenType.FILE_READ_LINE -> { advance(); Pair(SedCmd.ReadFileLine(address, token.filename ?: ""), null) }
            SedTokenType.FILE_WRITE -> { advance(); Pair(SedCmd.WriteFile(address, token.filename ?: ""), null) }
            SedTokenType.FILE_WRITE_LINE -> { advance(); Pair(SedCmd.WriteFirstLine(address, token.filename ?: ""), null) }
            SedTokenType.EXECUTE -> { advance(); Pair(SedCmd.Execute(address, token.command), null) }
            SedTokenType.VERSION -> { advance(); Pair(SedCmd.Version(address, token.label), null) }
            SedTokenType.LBRACE -> parseGroup(address)
            SedTokenType.RBRACE -> Pair(null, null)
            SedTokenType.ERROR -> Pair(null, "invalid command: ${token.value}")
            else -> {
                if (address != null && (address.start != null || address.end != null)) Pair(null, "command expected") else Pair(null, null)
            }
        }
    }

    private fun parseSimpleCommand(token: SedToken, address: AddressRange?): Pair<SedCmd?, String?> {
        val cmd = token.value
        return when (cmd) {
            "p" -> Pair(SedCmd.Print(address), null)
            "P" -> Pair(SedCmd.PrintFirstLine(address), null)
            "d" -> Pair(SedCmd.Delete(address), null)
            "D" -> Pair(SedCmd.DeleteFirstLine(address), null)
            "h" -> Pair(SedCmd.Hold(address), null)
            "H" -> Pair(SedCmd.HoldAppend(address), null)
            "g" -> Pair(SedCmd.Get(address), null)
            "G" -> Pair(SedCmd.GetAppend(address), null)
            "x" -> Pair(SedCmd.Exchange(address), null)
            "n" -> Pair(SedCmd.Next(address), null)
            "N" -> Pair(SedCmd.NextAppend(address), null)
            "q" -> Pair(SedCmd.Quit(address), null)
            "Q" -> Pair(SedCmd.QuitSilent(address), null)
            "z" -> Pair(SedCmd.Zap(address), null)
            "=" -> Pair(SedCmd.LineNumber(address), null)
            "l" -> Pair(SedCmd.ListCmd(address), null)
            "F" -> Pair(SedCmd.PrintFilename(address), null)
            else -> Pair(null, "unknown command: $cmd")
        }
    }

    private fun parseSubstituteFromToken(token: SedToken, address: AddressRange?): Pair<SedCmd?, String?> {
        val flags = token.flags ?: ""
        var nthOccurrence: Int? = null
        val numMatch = Regex("(\\d+)").find(flags)
        if (numMatch != null) nthOccurrence = numMatch.groupValues[1].toIntOrNull()
        return Pair(
            SedCmd.Substitute(
                address = address,
                pattern = token.pattern ?: "",
                replacement = token.replacement ?: "",
                global = flags.contains('g'),
                ignoreCase = flags.contains('i') || flags.contains('I'),
                printOnMatch = flags.contains('p'),
                nthOccurrence = nthOccurrence,
                extendedRegex = extendedRegex,
            ),
            null,
        )
    }

    private fun parseTransliterateFromToken(token: SedToken, address: AddressRange?): Pair<SedCmd?, String?> {
        val source = token.source ?: ""
        val dest = token.dest ?: ""
        if (source.length != dest.length) return Pair(null, "transliteration sets must have same length")
        return Pair(SedCmd.Transliterate(address, source, dest), null)
    }

    private fun parseTextCommand(token: SedToken, address: AddressRange?): Pair<SedCmd?, String?> {
        val cmd = token.value
        val text = token.text ?: ""
        return when (cmd) {
            "a" -> Pair(SedCmd.Append(address, text), null)
            "i" -> Pair(SedCmd.Insert(address, text), null)
            "c" -> Pair(SedCmd.Change(address, text), null)
            else -> Pair(null, "unknown text command: $cmd")
        }
    }

    private fun parseGroup(address: AddressRange?): Pair<SedCmd?, String?> {
        advance()
        val commands = ArrayList<SedCmd>()
        while (!isAtEnd() && !check(SedTokenType.RBRACE)) {
            if (check(SedTokenType.NEWLINE) || check(SedTokenType.SEMICOLON)) { advance(); continue }
            val posBefore = pos
            val (cmd, error) = parseCommand()
            if (error != null) return Pair(null, error)
            if (cmd != null) commands.add(cmd)
            if (pos == posBefore && !isAtEnd()) return Pair(null, "unknown command: '${peek().value.ifEmpty { peek().type.name }}'")
        }
        if (!check(SedTokenType.RBRACE)) return Pair(null, "unmatched brace in grouped commands")
        advance()
        return Pair(SedCmd.Group(address, commands), null)
    }

    private fun parseAddressRange(): Pair<AddressRange?, String?>? {
        if (check(SedTokenType.COMMA)) return Pair(null, "expected context address")
        val start = parseAddress()
        if (start == null) return null
        var end: SedAddress? = null
        if (check(SedTokenType.RELATIVE_OFFSET)) {
            val token = advance()
            end = SedAddress.Relative(token.offset ?: 0)
        } else if (check(SedTokenType.COMMA)) {
            advance()
            end = parseAddress()
            if (end == null) return Pair(null, "expected context address")
        }
        return Pair(AddressRange(start, end), null)
    }

    private fun parseAddress(): SedAddress? {
        val token = peek()
        return when (token.type) {
            SedTokenType.NUMBER -> { advance(); SedAddress.Number(token.first ?: 0) }
            SedTokenType.DOLLAR -> { advance(); SedAddress.Dollar }
            SedTokenType.PATTERN -> { advance(); SedAddress.Pattern(token.pattern ?: token.value) }
            SedTokenType.STEP -> { advance(); SedAddress.Step(token.first ?: 0, token.step ?: 0) }
            SedTokenType.RELATIVE_OFFSET -> { advance(); SedAddress.Relative(token.offset ?: 0) }
            else -> null
        }
    }

    private fun peek(): SedToken = tokens.getOrElse(pos) { SedToken(SedTokenType.EOF, "", 0, 0) }
    private fun advance(): SedToken { if (!isAtEnd()) pos++; return tokens[pos - 1] }
    private fun check(type: SedTokenType): Boolean = peek().type == type
    private fun isAtEnd(): Boolean = peek().type == SedTokenType.EOF

    private fun validateLabels(commands: List<SedCmd>): String? {
        val defined = HashSet<String>()
        collectLabels(commands, defined)
        return findUndefinedLabel(commands, defined)
    }

    private fun collectLabels(commands: List<SedCmd>, labels: MutableSet<String>) {
        for (cmd in commands) {
            if (cmd is SedCmd.Label) labels.add(cmd.name)
            else if (cmd is SedCmd.Group) collectLabels(cmd.commands, labels)
        }
    }

    private fun findUndefinedLabel(commands: List<SedCmd>, defined: Set<String>): String? {
        for (cmd in commands) {
            val label = when (cmd) {
                is SedCmd.Branch -> cmd.label
                is SedCmd.BranchOnSubst -> cmd.label
                is SedCmd.BranchOnNoSubst -> cmd.label
                else -> null
            }
            if (label != null && label !in defined) return label
            if (cmd is SedCmd.Group) {
                val r = findUndefinedLabel(cmd.commands, defined)
                if (r != null) return r
            }
        }
        return null
    }
}

private data class ExecuteContext(val lines: List<String>, val currentLineIndex: Int)

private fun matchesAddress(address: SedAddress, lineNum: Int, totalLines: Int, line: String, state: SedState?): Boolean {
    return when (address) {
        is SedAddress.Dollar -> lineNum == totalLines
        is SedAddress.Number -> lineNum == address.n
        is SedAddress.Step -> if (address.step == 0) lineNum == address.first else (lineNum - address.first) % address.step == 0 && lineNum >= address.first
        is SedAddress.Pattern -> {
            try {
                var rawPattern = address.pattern
                if (rawPattern.isEmpty() && state?.lastPattern != null) rawPattern = state.lastPattern!!
                else if (rawPattern.isNotEmpty() && state != null) state.lastPattern = rawPattern
                val pattern = normalizeForJs(breToEre(rawPattern))
                Pattern.compile(pattern).matcher(line).find()
            } catch (_: PatternSyntaxException) { false }
        }
        is SedAddress.Relative -> false
    }
}

private fun serializeRange(range: AddressRange): String {
    fun serializeAddr(addr: SedAddress?): String = when (addr) {
        null -> "undefined"
        is SedAddress.Dollar -> "$"
        is SedAddress.Number -> addr.n.toString()
        is SedAddress.Pattern -> "/${addr.pattern}/"
        is SedAddress.Step -> "${addr.first}~${addr.step}"
        is SedAddress.Relative -> "+${addr.offset}"
    }
    return "${serializeAddr(range.start)},${serializeAddr(range.end)}"
}

private fun isInRangeInternal(
    range: AddressRange?,
    lineNum: Int,
    totalLines: Int,
    line: String,
    rangeStates: MutableMap<String, RangeState>?,
    state: SedState?,
): Boolean {
    if (range == null || (range.start == null && range.end == null)) return true
    val start = range.start
    val end = range.end

    if (start != null && end == null) return matchesAddress(start, lineNum, totalLines, line, state)

    if (start != null && end != null) {
        val hasPatternStart = start is SedAddress.Pattern
        val hasPatternEnd = end is SedAddress.Pattern
        val hasRelativeEnd = end is SedAddress.Relative

        if (hasRelativeEnd && rangeStates != null) {
            val rangeKey = serializeRange(range)
            var rangeState = rangeStates[rangeKey]
            if (rangeState == null) { rangeState = RangeState(); rangeStates[rangeKey] = rangeState }

            if (!rangeState.active) {
                val startMatches = matchesAddress(start, lineNum, totalLines, line, state)
                if (startMatches) {
                    rangeState.active = true
                    rangeState.startLine = lineNum
                    if ((end as SedAddress.Relative).offset == 0) rangeState.active = false
                    return true
                }
                return false
            } else {
                val startLine = rangeState.startLine ?: lineNum
                if (lineNum >= startLine + (end as SedAddress.Relative).offset) rangeState.active = false
                return true
            }
        }

        if (!hasPatternStart && !hasPatternEnd && !hasRelativeEnd) {
            val startNum = when (start) { is SedAddress.Number -> start.n; is SedAddress.Dollar -> totalLines; else -> 1 }
            val endNum = when (end) { is SedAddress.Number -> end.n; is SedAddress.Dollar -> totalLines; else -> totalLines }
            if (startNum <= endNum) return lineNum >= startNum && lineNum <= endNum
            if (rangeStates != null) {
                val rangeKey = serializeRange(range)
                var rangeState = rangeStates[rangeKey]
                if (rangeState == null) { rangeState = RangeState(); rangeStates[rangeKey] = rangeState }
                if (!rangeState.completed) {
                    if (lineNum >= startNum) { rangeState.completed = true; return true }
                }
                return false
            }
            return false
        }

        if (rangeStates != null) {
            val rangeKey = serializeRange(range)
            var rangeState = rangeStates[rangeKey]
            if (rangeState == null) { rangeState = RangeState(); rangeStates[rangeKey] = rangeState }

            if (!rangeState.active) {
                if (rangeState.completed) return false
                val startMatches: Boolean
                if (start is SedAddress.Number) startMatches = lineNum >= start.n
                else startMatches = matchesAddress(start, lineNum, totalLines, line, state)
                if (startMatches) {
                    rangeState.active = true
                    rangeState.startLine = lineNum
                    if (matchesAddress(end, lineNum, totalLines, line, state)) {
                        rangeState.active = false
                        if (start is SedAddress.Number) rangeState.completed = true
                    }
                    return true
                }
                return false
            } else {
                if (matchesAddress(end, lineNum, totalLines, line, state)) {
                    rangeState.active = false
                    if (start is SedAddress.Number) rangeState.completed = true
                }
                return true
            }
        }

        return matchesAddress(start, lineNum, totalLines, line, state)
    }

    return true
}

private fun isInRange(
    range: AddressRange?,
    lineNum: Int,
    totalLines: Int,
    line: String,
    rangeStates: MutableMap<String, RangeState>?,
    state: SedState?,
): Boolean {
    val result = isInRangeInternal(range, lineNum, totalLines, line, rangeStates, state)
    return if (range?.negated == true) !result else result
}

private fun processReplacement(replacement: String, match: String, groups: List<String>): String {
    val result = StringBuilder()
    var i = 0
    while (i < replacement.length) {
        val ch = replacement[i]
        if (ch == '\\') {
            if (i + 1 < replacement.length) {
                val next = replacement[i + 1]
                when {
                    next == '&' -> { result.append('&'); i += 2; continue }
                    next == 'n' -> { result.append('\n'); i += 2; continue }
                    next == 't' -> { result.append('\t'); i += 2; continue }
                    next == 'r' -> { result.append('\r'); i += 2; continue }
                    next in '0'..'9' -> {
                        val digit = next - '0'
                        if (digit == 0) result.append(match) else result.append(groups.getOrNull(digit - 1) ?: "")
                        i += 2; continue
                    }
                    else -> { result.append(next); i += 2; continue }
                }
            }
        }
        if (ch == '&') { result.append(match); i++; continue }
        result.append(ch); i++
    }
    return result.toString()
}

private fun globalReplace(input: String, pattern: Pattern, replaceFn: (String, List<String>) -> String): String {
    val result = StringBuilder()
    var pos = 0
    var skipZeroLengthAtNextPos = false
    val m = pattern.matcher(input)
    while (pos <= input.length) {
        if (pos > input.length) break
        m.region(pos, input.length)
        if (!m.find()) { result.append(input.substring(pos)); break }
        if (m.start() != pos) {
            result.append(input, pos, m.start())
            pos = m.start()
            skipZeroLengthAtNextPos = false
            continue
        }
        val matchedText = m.group()
        val groupCount = m.groupCount()
        val groups = ArrayList<String>(groupCount)
        for (g in 1..groupCount) groups.add(m.group(g) ?: "")

        if (skipZeroLengthAtNextPos && matchedText.isEmpty()) {
            if (pos < input.length) { result.append(input[pos]); pos++ } else break
            skipZeroLengthAtNextPos = false
            continue
        }
        result.append(replaceFn(matchedText, groups))
        skipZeroLengthAtNextPos = false
        if (matchedText.isEmpty()) {
            if (pos < input.length) { result.append(input[pos]); pos++ } else break
        } else {
            pos += matchedText.length
            skipZeroLengthAtNextPos = true
        }
    }
    return result.toString()
}

private fun checkSpaceSize(space: String, maxLen: Long, spaceName: String) {
    if (maxLen > 0 && space.length.toLong() > maxLen) throw SedError("sed: $spaceName size limit exceeded ($maxLen bytes)")
}

private fun executeCommand(cmd: SedCmd, state: SedState, limits: SedExecutionLimits?) {
    val lineNumber = state.lineNumber
    val totalLines = state.totalLines
    val patternSpace = state.patternSpace

    if (cmd is SedCmd.Label) return
    if (!isInRange(cmd.address, lineNumber, totalLines, patternSpace, state.rangeStates, state)) return

    when (cmd) {
        is SedCmd.Substitute -> {
            var rawPattern = cmd.pattern
            if (rawPattern.isEmpty() && state.lastPattern != null) rawPattern = state.lastPattern!!
            else if (rawPattern.isNotEmpty()) state.lastPattern = rawPattern
            val pattern = normalizeForJs(if (cmd.extendedRegex) rawPattern else breToEre(rawPattern))

            try {
                val regex = Pattern.compile(pattern, if (cmd.ignoreCase) Pattern.CASE_INSENSITIVE else 0)
                val matcher = regex.matcher(state.patternSpace)
                val hasMatch = matcher.find()

                if (hasMatch) {
                    state.substitutionMade = true

                    if (cmd.nthOccurrence != null && cmd.nthOccurrence > 0 && !cmd.global) {
                        val nth = cmd.nthOccurrence
                        val sb = StringBuilder()
                        var count = 0
                        var pos = 0
                        val m2 = regex.matcher(state.patternSpace)
                        while (m2.find()) {
                            count++
                            val matchText = m2.group()
                            sb.append(state.patternSpace, pos, m2.start())
                            if (count == nth) {
                                val gc = m2.groupCount()
                                val groups = ArrayList<String>(gc)
                                for (g in 1..gc) groups.add(m2.group(g) ?: "")
                                sb.append(processReplacement(cmd.replacement, matchText, groups))
                            } else sb.append(matchText)
                            pos = m2.end()
                            if (matchText.isEmpty() && m2.end() == m2.start()) {
                                if (pos < state.patternSpace.length) { sb.append(state.patternSpace[pos]); pos++ }
                            }
                        }
                        sb.append(state.patternSpace.substring(pos))
                        state.patternSpace = sb.toString()
                    } else if (cmd.global) {
                        state.patternSpace = globalReplace(state.patternSpace, regex) { match, groups ->
                            processReplacement(cmd.replacement, match, groups)
                        }
                    } else {
                        val matchGroup = matcher.group()
                        val gc = matcher.groupCount()
                        val groups = ArrayList<String>(gc)
                        for (g in 1..gc) groups.add(matcher.group(g) ?: "")
                        val replacement = processReplacement(cmd.replacement, matchGroup, groups)
                        state.patternSpace = matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement))
                    }

                    if (cmd.printOnMatch) state.lineNumberOutput.add(state.patternSpace)
                }
            } catch (_: PatternSyntaxException) { }
        }
        is SedCmd.Print -> state.lineNumberOutput.add(state.patternSpace)
        is SedCmd.PrintFirstLine -> {
            val idx = state.patternSpace.indexOf('\n')
            state.lineNumberOutput.add(if (idx != -1) state.patternSpace.substring(0, idx) else state.patternSpace)
        }
        is SedCmd.Delete -> state.deleted = true
        is SedCmd.DeleteFirstLine -> {
            val idx = state.patternSpace.indexOf('\n')
            if (idx != -1) {
                state.patternSpace = state.patternSpace.substring(idx + 1)
                state.restartCycle = true
                state.inDRestartedCycle = true
            } else state.deleted = true
        }
        is SedCmd.Zap -> state.patternSpace = ""
        is SedCmd.Append -> state.appendBuffer.add(cmd.text)
        is SedCmd.Insert -> state.appendBuffer.add(0, "__INSERT__${cmd.text}")
        is SedCmd.Change -> { state.deleted = true; state.changedText = cmd.text }
        is SedCmd.Hold -> state.holdSpace = state.patternSpace
        is SedCmd.HoldAppend -> {
            state.holdSpace = if (state.holdSpace.isNotEmpty()) "${state.holdSpace}\n${state.patternSpace}" else state.patternSpace
            checkSpaceSize(state.holdSpace, limits?.maxStringLength ?: 0, "hold space")
        }
        is SedCmd.Get -> state.patternSpace = state.holdSpace
        is SedCmd.GetAppend -> {
            state.patternSpace = "${state.patternSpace}\n${state.holdSpace}"
            checkSpaceSize(state.patternSpace, limits?.maxStringLength ?: 0, "pattern space")
        }
        is SedCmd.Exchange -> { val temp = state.patternSpace; state.patternSpace = state.holdSpace; state.holdSpace = temp }
        is SedCmd.Next -> state.printed = true
        is SedCmd.Quit -> { state.quit = true; if (cmd.exitCode != null) state.exitCode = cmd.exitCode }
        is SedCmd.QuitSilent -> { state.quit = true; state.quitSilent = true; if (cmd.exitCode != null) state.exitCode = cmd.exitCode }
        is SedCmd.ListCmd -> state.lineNumberOutput.add(escapeForList(state.patternSpace))
        is SedCmd.PrintFilename -> { if (state.currentFilename != null) state.lineNumberOutput.add(state.currentFilename!!) }
        is SedCmd.Version -> {
            val OUR_VERSION = intArrayOf(4, 8, 0)
            if (cmd.minVersion != null) {
                val parts = cmd.minVersion.split(".")
                val requestedVersion = ArrayList<Int>()
                var parseError = false
                for (part in parts) {
                    val num = part.toIntOrNull()
                    if (num == null || num < 0) {
                        state.quit = true; state.exitCode = 1
                        state.errorMessage = "sed: invalid version string: ${cmd.minVersion}"
                        parseError = true; break
                    }
                    requestedVersion.add(num)
                }
                if (!parseError) {
                    while (requestedVersion.size < 3) requestedVersion.add(0)
                    for (i in 0 until 3) {
                        if (requestedVersion[i] > OUR_VERSION[i]) {
                            state.quit = true; state.exitCode = 1
                            state.errorMessage = "sed: this is not GNU sed version ${cmd.minVersion}"
                            break
                        }
                        if (requestedVersion[i] < OUR_VERSION[i]) break
                    }
                }
            }
        }
        is SedCmd.ReadFile -> state.pendingFileReads.add(PendingFileRead(cmd.filename, true))
        is SedCmd.ReadFileLine -> state.pendingFileReads.add(PendingFileRead(cmd.filename, false))
        is SedCmd.WriteFile -> state.pendingFileWrites.add(PendingFileWrite(cmd.filename, "${state.patternSpace}\n"))
        is SedCmd.WriteFirstLine -> {
            val idx = state.patternSpace.indexOf('\n')
            val firstLine = if (idx != -1) state.patternSpace.substring(0, idx) else state.patternSpace
            state.pendingFileWrites.add(PendingFileWrite(cmd.filename, "$firstLine\n"))
        }
        is SedCmd.Execute -> {
            state.errorMessage = "sed: e command (shell execution) is not supported in sandboxed environment"
            state.quit = true
        }
        is SedCmd.Transliterate -> state.patternSpace = executeTransliterate(state.patternSpace, cmd.source, cmd.dest)
        is SedCmd.LineNumber -> state.lineNumberOutput.add(state.lineNumber.toString())
        is SedCmd.Branch, is SedCmd.BranchOnSubst, is SedCmd.BranchOnNoSubst, is SedCmd.Group, is SedCmd.NextAppend, is SedCmd.Label -> {}
    }
}

private fun executeTransliterate(input: String, source: String, dest: String): String {
    val result = StringBuilder()
    for (char in input) {
        val idx = source.indexOf(char)
        if (idx != -1 && idx < dest.length) result.append(dest[idx]) else result.append(char)
    }
    return result.toString()
}

private const val DEFAULT_MAX_ITERATIONS = 10000

private fun executeCommands(
    commands: List<SedCmd>,
    state: SedState,
    ctx: ExecuteContext?,
    limits: SedExecutionLimits?,
): Int {
    val labelIndex = HashMap<String, Int>()
    for (i in commands.indices) {
        val cmd = commands[i]
        if (cmd is SedCmd.Label) labelIndex[cmd.name] = i
    }

    val maxIterations = limits?.maxIterations ?: DEFAULT_MAX_ITERATIONS.toLong()
    var totalIterations = 0L

    var i = 0
    while (i < commands.size) {
        totalIterations++
        if (totalIterations > maxIterations) throw SedError("sed: command execution exceeded maximum iterations ($maxIterations)")

        if (state.deleted || state.quit || state.quitSilent || state.restartCycle) break

        val cmd = commands[i]

        if (cmd is SedCmd.Next) {
            if (isInRange(cmd.address, state.lineNumber, state.totalLines, state.patternSpace, state.rangeStates, state)) {
                state.nCommandOutput.add(state.patternSpace)
                if (ctx != null && ctx.currentLineIndex + state.linesConsumedInCycle + 1 < ctx.lines.size) {
                    state.linesConsumedInCycle++
                    state.patternSpace = ctx.lines[ctx.currentLineIndex + state.linesConsumedInCycle]
                    state.lineNumber = ctx.currentLineIndex + state.linesConsumedInCycle + 1
                    state.substitutionMade = false
                } else {
                    state.quit = true
                    state.deleted = true
                    break
                }
            }
            i++; continue
        }

        if (cmd is SedCmd.NextAppend) {
            if (isInRange(cmd.address, state.lineNumber, state.totalLines, state.patternSpace, state.rangeStates, state)) {
                if (ctx != null && ctx.currentLineIndex + state.linesConsumedInCycle + 1 < ctx.lines.size) {
                    state.linesConsumedInCycle++
                    state.patternSpace = "${state.patternSpace}\n${ctx.lines[ctx.currentLineIndex + state.linesConsumedInCycle]}"
                    state.lineNumber = ctx.currentLineIndex + state.linesConsumedInCycle + 1
                } else {
                    state.quit = true
                    break
                }
            }
            i++; continue
        }

        if (cmd is SedCmd.Branch) {
            if (isInRange(cmd.address, state.lineNumber, state.totalLines, state.patternSpace, state.rangeStates, state)) {
                if (cmd.label != null) {
                    val target = labelIndex[cmd.label]
                    if (target != null) { i = target; continue }
                    state.branchRequest = cmd.label
                    break
                }
                break
            }
            i++; continue
        }

        if (cmd is SedCmd.BranchOnSubst) {
            if (isInRange(cmd.address, state.lineNumber, state.totalLines, state.patternSpace, state.rangeStates, state)) {
                if (state.substitutionMade) {
                    state.substitutionMade = false
                    if (cmd.label != null) {
                        val target = labelIndex[cmd.label]
                        if (target != null) { i = target; continue }
                        state.branchRequest = cmd.label
                        break
                    }
                    break
                }
            }
            i++; continue
        }

        if (cmd is SedCmd.BranchOnNoSubst) {
            if (isInRange(cmd.address, state.lineNumber, state.totalLines, state.patternSpace, state.rangeStates, state)) {
                if (!state.substitutionMade) {
                    if (cmd.label != null) {
                        val target = labelIndex[cmd.label]
                        if (target != null) { i = target; continue }
                        state.branchRequest = cmd.label
                        break
                    }
                    break
                }
            }
            i++; continue
        }

        if (cmd is SedCmd.Group) {
            if (isInRange(cmd.address, state.lineNumber, state.totalLines, state.patternSpace, state.rangeStates, state)) {
                executeCommands(cmd.commands, state, ctx, limits)
                if (state.branchRequest != null) {
                    val target = labelIndex[state.branchRequest]
                    if (target != null) { state.branchRequest = null; i = target; continue }
                    break
                }
            }
            i++; continue
        }

        executeCommand(cmd, state, limits)
        i++
    }

    return state.linesConsumedInCycle
}

object SedCommand : Command {
    override val name = "sed"

    private fun processContent(
        content: String,
        commands: List<SedCmd>,
        silent: Boolean,
        filename: String?,
        fs: com.justbash.fs.IFileSystem?,
        cwd: String?,
        limits: SedExecutionLimits?,
    ): ExecResult {
        val inputEndsWithNewline = content.endsWith("\n")

        val lines = content.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)

        val totalLines = lines.size
        val output = StringBuilder()
        var exitCode: Int? = null
        var lastOutputWasAutoPrint = false

        val maxOutputSize = limits?.maxStringLength ?: 0L
        fun appendOutput(text: String) {
            output.append(text)
            if (maxOutputSize > 0 && output.length.toLong() > maxOutputSize) throw SedError("sed: output size limit exceeded ($maxOutputSize bytes)")
        }

        var holdSpace = ""
        var lastPattern: String? = null
        val rangeStates = LinkedHashMap<String, RangeState>()

        val fileLineCache = HashMap<String, List<String>>()
        val fileLinePositions = HashMap<String, Int>()
        val fileWrites = LinkedHashMap<String, StringBuilder>()

        var lineIndex = 0
        while (lineIndex < lines.size) {
            val state = SedState()
            state.patternSpace = lines[lineIndex]
            state.holdSpace = holdSpace
            state.lastPattern = lastPattern
            state.lineNumber = lineIndex + 1
            state.totalLines = totalLines
            state.substitutionMade = false
            state.rangeStates.putAll(rangeStates)
            state.currentFilename = filename
            state.linesConsumedInCycle = 0

            val ctx = ExecuteContext(lines, lineIndex)

            var cycleIterations = 0
            val maxCycleIterations = 10000
            do {
                cycleIterations++
                if (cycleIterations > maxCycleIterations) break

                state.restartCycle = false
                state.pendingFileReads.clear()
                state.pendingFileWrites.clear()

                executeCommands(commands, state, ctx, limits)

                if (fs != null && cwd != null) {
                    for (read in state.pendingFileReads) {
                        val filePath = fs.resolvePath(cwd, read.filename)
                        try {
                            if (read.wholeFile) {
                                val fileContent = fs.readFile(filePath)
                                state.appendBuffer.add(fileContent.replace(Regex("\n$"), ""))
                            } else {
                                if (!fileLineCache.containsKey(filePath)) {
                                    fileLineCache[filePath] = fs.readFile(filePath).split("\n")
                                    fileLinePositions[filePath] = 0
                                }
                                val fileLines = fileLineCache[filePath]
                                val pos = fileLinePositions[filePath]
                                if (fileLines != null && pos != null && pos < fileLines.size) {
                                    state.appendBuffer.add(fileLines[pos])
                                    fileLinePositions[filePath] = pos + 1
                                }
                            }
                        } catch (_: Exception) { }
                    }
                    for (write in state.pendingFileWrites) {
                        val filePath = fs.resolvePath(cwd, write.filename)
                        fileWrites.getOrPut(filePath) { StringBuilder() }.append(write.content)
                    }
                }
            } while (state.restartCycle && !state.deleted && !state.quit && !state.quitSilent)

            lineIndex += state.linesConsumedInCycle
            if (state.linesConsumedInCycle == 0) lineIndex++

            holdSpace = state.holdSpace
            lastPattern = state.lastPattern

            if (!silent) {
                for (ln in state.nCommandOutput) appendOutput("$ln\n")
            }

            val hadLineNumberOutput = state.lineNumberOutput.isNotEmpty()
            for (ln in state.lineNumberOutput) appendOutput("$ln\n")

            val inserts = ArrayList<String>()
            val appends = ArrayList<String>()
            for (item in state.appendBuffer) {
                if (item.startsWith("__INSERT__")) inserts.add(item.substring(10)) else appends.add(item)
            }

            for (text in inserts) appendOutput("$text\n")

            var hadPatternSpaceOutput = false
            if (!state.deleted && !state.quitSilent) {
                if (silent) {
                    if (state.printed) { appendOutput("${state.patternSpace}\n"); hadPatternSpaceOutput = true }
                } else {
                    appendOutput("${state.patternSpace}\n")
                    hadPatternSpaceOutput = true
                }
            } else if (state.changedText != null) {
                appendOutput("${state.changedText}\n")
                hadPatternSpaceOutput = true
            }

            for (text in appends) appendOutput("$text\n")

            val hadOutput = hadLineNumberOutput || hadPatternSpaceOutput
            lastOutputWasAutoPrint = hadOutput && appends.isEmpty()

            if (state.quit || state.quitSilent) {
                if (state.exitCode != null) exitCode = state.exitCode
                if (state.errorMessage != null) return ExecResult(stdout = "", stderr = "${state.errorMessage}\n", exitCode = exitCode ?: 1)
                break
            }
        }

        if (fs != null && cwd != null) {
            for ((filePath, fileContent) in fileWrites) {
                try { fs.writeFile(filePath, fileContent.toString()) } catch (_: Exception) { }
            }
        }

        val resultStr = output.toString()
        val finalOutput = if (!inputEndsWithNewline && lastOutputWasAutoPrint && resultStr.endsWith("\n")) {
            resultStr.substring(0, resultStr.length - 1)
        } else resultStr

        return ExecResult(stdout = finalOutput, stderr = "", exitCode = exitCode ?: 0)
    }

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "sed", "stream editor for filtering and transforming text",
                "sed [OPTION]... {script} [input-file]...",
                listOf(
                    "-n, --quiet, --silent  suppress automatic printing of pattern space",
                    "-e script              add the script to commands to be executed",
                    "-f script-file         read script from file",
                    "-i, --in-place         edit files in place",
                    "-E, -r, --regexp-extended  use extended regular expressions",
                    "    --help             display this help and exit",
                ),
            )
        }

        val scripts = ArrayList<String>()
        val scriptFiles = ArrayList<String>()
        var silent = false
        var inPlace = false
        var extendedRegex = false
        val files = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-n" || arg == "--quiet" || arg == "--silent" -> silent = true
                arg == "-i" || arg == "--in-place" -> inPlace = true
                arg.startsWith("-i") -> inPlace = true
                arg == "-E" || arg == "-r" || arg == "--regexp-extended" -> extendedRegex = true
                arg == "-e" -> { if (i + 1 < args.size) scripts.add(args[++i]) }
                arg == "-f" -> { if (i + 1 < args.size) scriptFiles.add(args[++i]) }
                arg.startsWith("--") -> return unknownOption("sed", arg)
                arg == "-" -> files.add(arg)
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.substring(1)) {
                        if (c != 'n' && c != 'e' && c != 'f' && c != 'i' && c != 'E' && c != 'r') return unknownOption("sed", "-$c")
                    }
                    if (arg.contains('n')) silent = true
                    if (arg.contains('i')) inPlace = true
                    if (arg.contains('E') || arg.contains('r')) extendedRegex = true
                    if (arg.contains('e') && !arg.contains('n') && !arg.contains('i')) { if (i + 1 < args.size) scripts.add(args[++i]) }
                    if (arg.contains('f') && !arg.contains('e')) { if (i + 1 < args.size) scriptFiles.add(args[++i]) }
                }
                !arg.startsWith("-") && scripts.isEmpty() && scriptFiles.isEmpty() -> scripts.add(arg)
                !arg.startsWith("-") -> files.add(arg)
            }
            i++
        }

        for (scriptFile in scriptFiles) {
            val scriptPath = ctx.fs.resolvePath(ctx.cwd, scriptFile)
            try {
                val scriptContent = ctx.fs.readFile(scriptPath)
                for (line in scriptContent.split("\n")) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) scripts.add(trimmed)
                }
            } catch (_: Exception) {
                return ExecResult(stdout = "", stderr = "sed: couldn't open file $scriptFile: No such file or directory\n", exitCode = 1)
            }
        }

        if (scripts.isEmpty()) return ExecResult(stdout = "", stderr = "sed: no script specified\n", exitCode = 1)

        var silentMode = false
        var extendedRegexFromComment = false
        val firstScript = scripts[0]
        val match = Regex("^#([nr]+)\\s*(?:\\n|\$)").find(firstScript)
        if (match != null) {
            val flags = match.groupValues[1].lowercase()
            if (flags.contains('n')) silentMode = true
            if (flags.contains('r')) extendedRegexFromComment = true
            scripts[0] = firstScript.substring(match.range.last + 1)
        }

        val effectiveSilent = silent || silentMode
        val effectiveExtendedRegex = extendedRegex || extendedRegexFromComment

        val joinedScripts = ArrayList<String>()
        for (script in scripts) {
            if (joinedScripts.isNotEmpty() && joinedScripts.last().endsWith("\\")) {
                val last = joinedScripts.removeAt(joinedScripts.size - 1)
                joinedScripts.add("$last\n$script")
            } else joinedScripts.add(script)
        }
        val combinedScript = joinedScripts.joinToString("\n")

        val limits = SedExecutionLimits(ctx.limits.maxLoopIterations, ctx.limits.maxStringLength)

        val commands: List<SedCmd>
        try {
            commands = SedParser(listOf(combinedScript), effectiveExtendedRegex, limits).parse()
        } catch (e: SedError) {
            return ExecResult(stdout = "", stderr = "sed: ${e.message}\n", exitCode = 1)
        }

        if (inPlace) {
            if (files.isEmpty()) return ExecResult(stdout = "", stderr = "sed: -i requires at least one file argument\n", exitCode = 1)
            for (file in files) {
                if (file == "-") continue
                val filePath = ctx.fs.resolvePath(ctx.cwd, file)
                try {
                    val fileContent = ctx.fs.readFile(filePath)
                    val result = processContent(fileContent, commands, effectiveSilent, file, ctx.fs, ctx.cwd, limits)
                    if (result.stderr.isNotEmpty()) return result
                    ctx.fs.writeFile(filePath, result.stdout)
                } catch (e: SedError) {
                    return ExecResult(stdout = "", stderr = "sed: ${e.message}\n", exitCode = 1)
                } catch (_: Exception) {
                    return ExecResult(stdout = "", stderr = "sed: $file: No such file or directory\n", exitCode = 1)
                }
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        var content = ""
        if (files.isEmpty()) {
            content = ctx.stdin.toString(Charsets.UTF_8)
            try {
                return processContent(content, commands, effectiveSilent, null, ctx.fs, ctx.cwd, limits)
            } catch (e: SedError) {
                return ExecResult(stdout = "", stderr = "sed: ${e.message}\n", exitCode = 1)
            }
        }

        var stdinConsumed = false
        for (file in files) {
            val fileContent: String
            if (file == "-") {
                fileContent = if (stdinConsumed) "" else ctx.stdin.toString(Charsets.UTF_8)
                stdinConsumed = true
            } else {
                val filePath = ctx.fs.resolvePath(ctx.cwd, file)
                try { fileContent = ctx.fs.readFile(filePath) }
                catch (_: Exception) { return ExecResult(stdout = "", stderr = "sed: $file: No such file or directory\n", exitCode = 1) }
            }
            if (content.isNotEmpty() && fileContent.isNotEmpty() && !content.endsWith("\n")) content += "\n"
            content += fileContent
        }

        try {
            return processContent(
                content, commands, effectiveSilent,
                if (files.size == 1 && files[0] != "-") files[0] else null,
                ctx.fs, ctx.cwd, limits,
            )
        } catch (e: SedError) {
            return ExecResult(stdout = "", stderr = "sed: ${e.message}\n", exitCode = 1)
        }
    }
}