package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import java.util.Base64
import java.util.Locale

/**
 * Port of just-bash `base64/base64.ts`, `printf/printf.ts` (plus `escapes.ts`)
 * and `seq/seq.ts`.
 */

// ---------------------------------------------------------------------------
// base64
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `base64/base64.ts`.
 *
 * - `base64`            -> encode stdin
 * - `base64 -d`         -> decode stdin
 * - `base64 file`       -> encode file
 * - `base64 -d file`    -> decode file
 */
object Base64Command : Command {
    override val name = "base64"

    private val defs = mapOf(
        "decode" to Args.Def(short = "d", long = "decode", type = "boolean"),
        "wrap" to Args.Def(short = "w", long = "wrap", type = "number", default = 76),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "base64", "base64 encode/decode data and print to standard output",
                "base64 [OPTION]... [FILE]",
                listOf(
                    "-d, --decode      decode data",
                    "-w, --wrap=COLS   wrap encoded lines after COLS character (default 76, 0 to disable)",
                    "    --help        display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("base64", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val decode = parsed.flags.bool("decode")
        val wrapCols = parsed.flags.number("wrap") ?: 76
        val files = parsed.positional

        if (wrapCols < 0) {
            return ExecResult(stderr = "base64: invalid wrap size\n", exitCode = 1)
        }

        // Read input as binary, concatenating files and (optionally) stdin.
        val read = readBinary(ctx, files)
        if (read == null) {
            return ExecResult(stderr = "base64: invalid input\n", exitCode = 1)
        }

        val maxOut = ctx.limits.maxStringLength

        return if (decode) {
            // Strip all whitespace from the latin1 text view, then decode.
            val cleaned = latin1FromBytes(read).replace(Regex("\\s"), "")
            val decoded = try {
                Base64.getDecoder().decode(cleaned)
            } catch (_: IllegalArgumentException) {
                return ExecResult(stderr = "base64: invalid input\n", exitCode = 1)
            }
            if (decoded.size.toLong() > maxOut) {
                return ExecResult(stderr = "base64: output size limit exceeded ($maxOut bytes)\n", exitCode = 1)
            }
            ExecResult(stdout = latin1FromBytes(decoded), stderr = "", exitCode = 0)
        } else {
            val encoded = Base64.getEncoder().encodeToString(read)
            val output = if (wrapCols > 0) {
                val chunks = ArrayList<String>()
                var i = 0
                while (i < encoded.length) {
                    chunks.add(encoded.substring(i, (i + wrapCols).coerceAtMost(encoded.length)))
                    i += wrapCols
                }
                if (encoded.isEmpty()) "" else chunks.joinToString("\n") + "\n"
            } else {
                encoded
            }
            if (output.length.toLong() > maxOut) {
                return ExecResult(stderr = "base64: output size limit exceeded ($maxOut bytes)\n", exitCode = 1)
            }
            ExecResult(stdout = output, stderr = "", exitCode = 0)
        }
    }

    /**
     * Read input as raw bytes. Returns null if any named file is missing.
     */
    private fun readBinary(ctx: CommandContext, files: List<String>): ByteArray? {
        if (files.isEmpty() || (files.size == 1 && files[0] == "-")) {
            return ctx.stdin
        }
        val chunks = ArrayList<ByteArray>()
        var total = 0
        for (file in files) {
            if (file == "-") {
                chunks.add(ctx.stdin)
                total += ctx.stdin.size
                continue
            }
            val data = try {
                ctx.fs.readFileBuffer(ctx.fs.resolvePath(ctx.cwd, file))
            } catch (_: Exception) {
                return null
            }
            chunks.add(data)
            total += data.size
        }
        val result = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}

// ---------------------------------------------------------------------------
// printf
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `printf/printf.ts` and `printf/escapes.ts`.
 *
 * Format specifiers: %s %d %i %x %X %o %u %f %F %e %E %g %G %c %% %b %q.
 * Width/precision with `-+0 #'` flags supported. `%b` (backslash escapes) and
 * `%q` (shell quoting) are bash-specific and implemented manually; everything
 * else delegates to JVM `String.format`.
 */
object PrintfCommand : Command {
    override val name = "printf"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "printf", "format and print data",
                "printf [-v var] FORMAT [ARGUMENT...]",
                listOf(
                    "    -v var        assign the output to shell variable VAR rather than display it",
                    "    --help        display this help and exit",
                ),
                description = listOf(
                    "FORMAT controls the output like in C printf.",
                    "",
                    "Escape sequences: \\n (newline), \\t (tab), \\\\ (backslash)",
                    "Format specifiers: %s (string), %d (integer), %f (float), %x (hex), %o (octal), %% (literal %)",
                    "Width and precision: %10s (width 10), %.2f (2 decimal places), %010d (zero-padded)",
                    "Flags: %- (left-justify), %+ (show sign), %0 (zero-pad)",
                ),
            )
        }

        if (args.isEmpty()) {
            return ExecResult(stderr = "printf: usage: printf format [arguments]\n", exitCode = 2)
        }

        // Parse options
        var targetVar: String? = null
        var argIndex = 0
        while (argIndex < args.size) {
            val arg = args[argIndex]
            if (arg == "--") {
                argIndex++
                break
            }
            if (arg == "-v") {
                if (argIndex + 1 >= args.size) {
                    return ExecResult(stderr = "printf: -v: option requires an argument\n", exitCode = 1)
                }
                targetVar = args[argIndex + 1]
                if (!VALID_IDENT.matches(targetVar)) {
                    return ExecResult(stderr = "printf: `$targetVar': not a valid identifier\n", exitCode = 2)
                }
                argIndex += 2
            } else if (arg.startsWith("-") && arg != "-") {
                // Unknown option — treat as format string (bash behavior).
                break
            } else {
                break
            }
        }

        if (argIndex >= args.size) {
            return ExecResult(stderr = "printf: usage: printf format [arguments]\n", exitCode = 1)
        }

        val format = processEscapes(args[argIndex])
        val formatArgs = args.subList(argIndex + 1, args.size)

        val output = StringBuilder()
        var argPos = 0
        var hadError = false
        var errorMessage = ""

        do {
            val once = formatOnce(format, formatArgs, argPos)
            output.append(once.result)
            argPos += once.argsConsumed
            if (once.error) {
                hadError = true
                if (once.errMsg.isNotEmpty()) errorMessage = once.errMsg
            }
            if (once.stopped) break
        } while (argPos < formatArgs.size && argPos > 0)

        val finalOutput = output.toString()
        if (targetVar != null) {
            ctx.env[targetVar] = finalOutput
            return ExecResult(stderr = if (hadError) errorMessage else "", exitCode = if (hadError) 1 else 0)
        }

        return ExecResult(
            stdout = finalOutput,
            stderr = if (hadError) errorMessage else "",
            exitCode = if (hadError) 1 else 0,
        )
    }

    private data class FormatResult(
        val result: String,
        val argsConsumed: Int,
        val error: Boolean,
        val errMsg: String,
        val stopped: Boolean,
    )

    /**
     * Format the (already escape-processed) format string once, consuming args
     * starting at [argPos].
     */
    private fun formatOnce(format: String, args: List<String>, argPos: Int): FormatResult {
        val result = StringBuilder()
        var i = 0
        var argsConsumed = 0
        var error = false
        var errMsg = ""

        while (i < format.length) {
            if (format[i] == '%' && i + 1 < format.length) {
                val specStart = i
                i++

                if (format[i] == '%') {
                    result.append('%')
                    i++
                    continue
                }

                // Parse flags
                while (i < format.length && format[i] in "+-0 #'") {
                    i++
                }

                // Parse width (handle '*' from args)
                var widthFromArg = false
                if (i < format.length && format[i] == '*') {
                    widthFromArg = true
                    i++
                } else {
                    while (i < format.length && format[i].isDigit()) i++
                }

                // Parse precision (handle '.*' from args)
                var precisionFromArg = false
                if (i < format.length && format[i] == '.') {
                    i++
                    if (i < format.length && format[i] == '*') {
                        precisionFromArg = true
                        i++
                    } else {
                        while (i < format.length && format[i].isDigit()) i++
                    }
                }

                // Skip length modifier
                if (i < format.length && format[i] in "hlL") i++

                if (i >= format.length) break
                val specifier = format[i]
                i++

                val fullSpec = format.substring(specStart, i)
                var adjustedSpec = fullSpec
                if (widthFromArg) {
                    val w = (args.getOrNull(argPos + argsConsumed) ?: "0").toIntOrNull() ?: 0
                    argsConsumed++
                    adjustedSpec = adjustedSpec.replaceFirst("*", w.toString())
                }
                if (precisionFromArg) {
                    val p = (args.getOrNull(argPos + argsConsumed) ?: "0").toIntOrNull() ?: 0
                    argsConsumed++
                    adjustedSpec = adjustedSpec.replaceFirst(".*", ".$p")
                }

                val arg = args.getOrNull(argPos + argsConsumed) ?: ""
                argsConsumed++

                when (specifier) {
                    'b' -> {
                        val b = processBEscapes(arg)
                        result.append(b.value)
                        if (b.stopped) {
                            return FormatResult(result.toString(), argsConsumed, error, errMsg, true)
                        }
                    }
                    'q' -> {
                        result.append(formatQuoted(specifier, adjustedSpec, arg))
                    }
                    else -> {
                        try {
                            val formatted = javaStringFormat(adjustedSpec, specifier, arg)
                            result.append(formatted)
                        } catch (_: Exception) {
                            error = true
                            errMsg = "printf: `$arg': invalid number\n"
                        }
                    }
                }
            } else {
                result.append(format[i])
                i++
            }
        }
        return FormatResult(result.toString(), argsConsumed, error, errMsg, false)
    }

    /**
     * Delegate to JVM `String.format`, mapping bash specifiers to compatible
     * Java conversion characters.
     */
    private fun javaStringFormat(spec: String, specifier: Char, arg: String): String {
        val locale = Locale.ROOT
        return when (specifier) {
            'd', 'i' -> String.format(locale, spec.replaceFirst(Regex("[di]$"), "d"), parseInteger(arg))
            'o' -> String.format(locale, spec.replaceFirst("o$", "o"), parseInteger(arg))
            'x' -> String.format(locale, spec, parseInteger(arg))
            'X' -> String.format(locale, spec, parseInteger(arg))
            'u' -> {
                // Java has no unsigned conversion; emulate with 32-bit unsigned decimal.
                val v = parseInteger(arg)
                val unsigned = if (v < 0) v + (1L shl 32) else v
                formatInteger(spec, unsigned)
            }
            'f', 'F', 'e', 'E', 'g', 'G' -> String.format(locale, spec, parseDouble(arg))
            'c' -> {
                val bytes = arg.toByteArray(Charsets.UTF_8)
                if (bytes.isEmpty()) "" else (bytes[0].toInt() and 0xFF).toChar().toString()
            }
            's' -> {
                // Java %s with width/precision works, but strip a bare '0' flag which
                // bash ignores for strings.
                String.format(locale, normalizeStringSpec(spec), arg)
            }
            else -> {
                // Unknown specifier — attempt String.format, else literal.
                try {
                    String.format(locale, spec, arg)
                } catch (_: Exception) {
                    spec
                }
            }
        }
    }

    private fun normalizeStringSpec(spec: String): String {
        // Remove a '0' flag for %s (Java would zero-pad; bash ignores it).
        return spec.replace(Regex("%([-+# ']*)0+(\\d*)s"), "%$1$2s")
    }

    private fun formatInteger(spec: String, value: Long): String {
        // Reuse String.format for integer conversions to get flags/width correct.
        val converted = if (spec.endsWith("u")) spec.dropLast(1) + "d" else spec
        return String.format(Locale.ROOT, converted, value)
    }
}

/** Parse an integer argument with bash-style character notation ('a' -> 97). */
private fun parseInteger(arg: String): Long {
    val t = arg.trim()
    // Character notation 'x' or "x"
    if (t.length >= 2 && (t[0] == '\'' || t[0] == '"')) {
        return t[1].code.toLong()
    }
    if (t.startsWith("0x") || t.startsWith("0X")) {
        return t.drop(2).toLongOrNull(16) ?: 0L
    }
    if (t.startsWith("0") && t.length > 1 && Regex("0[0-7]+").matches(t)) {
        return t.drop(1).toLongOrNull(8) ?: 0L
    }
    return t.toLongOrNull() ?: 0L
}

private fun parseDouble(arg: String): Double = arg.trim().toDoubleOrNull() ?: 0.0

private val VALID_IDENT = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

// ---------------------------------------------------------------------------
// seq
// ---------------------------------------------------------------------------

/**
 * Port of just-bash `seq/seq.ts`.
 *
 * - `seq LAST`            -> 1..LAST
 * - `seq FIRST LAST`      -> FIRST..LAST
 * - `seq FIRST INCR LAST` -> FIRST..LAST step INCR
 */
object SeqCommand : Command {
    override val name = "seq"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "seq", "print a sequence of numbers",
                "seq [OPTION]... LAST\nseq [OPTION]... FIRST LAST\nseq [OPTION]... FIRST INCREMENT LAST",
                listOf(
                    "-s, --separator=STRING  use STRING to separate numbers (default: \\n)",
                    "-w, --equal-width       equalize width by padding with leading zeros",
                    "    --help              display this help and exit",
                ),
            )
        }

        var separator = "\n"
        var equalizeWidth = false
        val nums = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-s" && i + 1 < args.size -> { separator = args[i + 1]; i += 2; continue }
                arg.startsWith("--separator=") -> { separator = arg.substring(12); i++; continue }
                arg == "-w" || arg == "--equal-width" -> { equalizeWidth = true; i++; continue }
                arg == "--" -> { i++ ; break }
                arg.startsWith("-s") && arg.length > 2 -> { separator = arg.substring(2); i++; continue }
                arg == "-ws" || arg == "-sw" -> {
                    equalizeWidth = true
                    if (i + 1 < args.size) { separator = args[i + 1]; i += 2; continue }
                }
                // else: unknown option or negative number — treat as number.
            }
            nums.add(arg)
            i++
        }

        while (i < args.size) {
            nums.add(args[i])
            i++
        }

        if (nums.isEmpty()) {
            return ExecResult(stderr = "seq: missing operand\n", exitCode = 1)
        }

        var first = 1.0
        var increment = 1.0
        val last: Double

        when (nums.size) {
            1 -> last = nums[0].toDoubleOrNull() ?: Double.NaN
            2 -> { first = nums[0].toDoubleOrNull() ?: Double.NaN; last = nums[1].toDoubleOrNull() ?: Double.NaN }
            else -> {
                first = nums[0].toDoubleOrNull() ?: Double.NaN
                increment = nums[1].toDoubleOrNull() ?: Double.NaN
                last = nums[2].toDoubleOrNull() ?: Double.NaN
            }
        }

        if (!first.isFinite() || !increment.isFinite() || !last.isFinite()) {
            val invalid = nums.firstOrNull { !(it.toDoubleOrNull()?.isFinite() ?: false) } ?: ""
            return ExecResult(stderr = "seq: invalid floating point argument: '$invalid'\n", exitCode = 1)
        }

        if (increment == 0.0) {
            return ExecResult(stderr = "seq: invalid Zero increment value: '0'\n", exitCode = 1)
        }

        // Determine precision from the ORIGINAL string forms (not the parsed
        // doubles), so `seq 3 5` has precision 0 (not "3.0").
        val precision = maxOf(
            precisionOfString(if (nums.size == 1) nums[0] else nums[0]),
            precisionOfString(if (nums.size >= 3) nums[1] else nums[0]),
            precisionOfString(nums.last()),
        )

        val results = ArrayList<String>()
        val maxIterations = ctx.limits.maxLoopIterations

        val append: (String) -> Unit = { value ->
            if (results.size.toLong() >= maxIterations) {
                throw SeqLimitError("seq: iteration limit exceeded ($maxIterations)")
            }
            results.add(value)
        }

        try {
            if (increment > 0.0) {
                var n = first
                while (n <= last + 1e-10) {
                    append(if (precision > 0) formatFixed(n, precision) else Math.round(n).toString())
                    n += increment
                }
            } else {
                var n = first
                while (n >= last - 1e-10) {
                    append(if (precision > 0) formatFixed(n, precision) else Math.round(n).toString())
                    n += increment
                }
            }
        } catch (e: SeqLimitError) {
            return ExecResult(stderr = e.message!! + "\n", exitCode = 1)
        }

        if (equalizeWidth && results.isNotEmpty()) {
            var maxLen = 0
            for (r in results) {
                maxLen = maxOf(maxLen, if (r.startsWith("-")) r.length - 1 else r.length)
            }
            for (j in results.indices) {
                val negative = results[j].startsWith("-")
                val num = if (negative) results[j].substring(1) else results[j]
                val padded = num.padStart(maxLen, '0')
                results[j] = if (negative) "-$padded" else padded
            }
        }

        val output = results.joinToString(separator)
        return ExecResult(stdout = if (output.isEmpty()) "" else "$output\n", stderr = "", exitCode = 0)
    }

    private fun formatFixed(value: Double, precision: Int): String {
        // Use BigDecimal-free fixed formatting with Locale.ROOT to avoid "," separators.
        return String.format(Locale.ROOT, "%.${precision}f", value)
    }
}

private class SeqLimitError(message: String) : Exception(message)

private fun precisionOfString(s: String): Int {
    val dot = s.indexOf('.')
    return if (dot == -1) 0 else s.length - dot - 1
}

// ---------------------------------------------------------------------------
// Escape-sequence helpers (shared by printf; mirrors just-bash escapes.ts)
// ---------------------------------------------------------------------------

/**
 * Process backslash escapes in the printf FORMAT string.
 * Handles \\ \n \t \r \a \b \f \v \e \0NNN(octal) \xHH \uHHHH \UHHHHHHHH.
 */
private fun processEscapes(str: String): String {
    val result = StringBuilder()
    var i = 0
    while (i < str.length) {
        if (str[i] == '\\' && i + 1 < str.length) {
            val next = str[i + 1]
            when (next) {
                'n' -> { result.append('\n'); i += 2 }
                't' -> { result.append('\t'); i += 2 }
                'r' -> { result.append('\r'); i += 2 }
                '\\' -> { result.append('\\'); i += 2 }
                'a' -> { result.append('\u0007'); i += 2 }
                'b' -> { result.append('\b'); i += 2 }
                'f' -> { result.append('\u000C'); i += 2 }
                'v' -> { result.append('\u000B'); i += 2 }
                'e', 'E' -> { result.append('\u001B'); i += 2 }
                'x' -> {
                    var j = i + 2
                    val hex = StringBuilder()
                    while (j < str.length && j < i + 4 && isHex(str[j])) {
                        hex.append(str[j]); j++
                    }
                    if (hex.isEmpty()) { result.append("\\x"); i += 2 }
                    else { result.append(hex.toString().toInt(16).toChar()); i = j }
                }
                'u' -> {
                    var j = i + 2
                    val hex = StringBuilder()
                    while (j < str.length && j < i + 6 && isHex(str[j])) {
                        hex.append(str[j]); j++
                    }
                    if (hex.isEmpty()) { result.append("\\u"); i += 2 }
                    else { result.append(hex.toString().toInt(16).toChar()); i = j }
                }
                'U' -> {
                    var j = i + 2
                    val hex = StringBuilder()
                    while (j < str.length && j < i + 10 && isHex(str[j])) {
                        hex.append(str[j]); j++
                    }
                    if (hex.isEmpty()) { result.append("\\U"); i += 2 }
                    else {
                        val code = hex.toString().toInt(16)
                        result.append(if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else "\\U$hex")
                        i = j
                    }
                }
                in '0'..'7' -> {
                    var j = i + 1
                    val octal = StringBuilder()
                    while (j < str.length && j < i + 4 && str[j] in '0'..'7') {
                        octal.append(str[j]); j++
                    }
                    result.append(octal.toString().toInt(8).toChar()); i = j
                }
                else -> { result.append(str[i]); i++ }
            }
        } else {
            result.append(str[i]); i++
        }
    }
    return result.toString()
}

private data class BEscapeResult(val value: String, val stopped: Boolean)

/**
 * Process backslash escapes for `%b`, with `\c` stopping output.
 */
private fun processBEscapes(str: String): BEscapeResult {
    val result = StringBuilder()
    var i = 0
    while (i < str.length) {
        if (str[i] == '\\' && i + 1 < str.length) {
            val next = str[i + 1]
            when (next) {
                'n' -> { result.append('\n'); i += 2 }
                't' -> { result.append('\t'); i += 2 }
                'r' -> { result.append('\r'); i += 2 }
                '\\' -> { result.append('\\'); i += 2 }
                'a' -> { result.append('\u0007'); i += 2 }
                'b' -> { result.append('\b'); i += 2 }
                'f' -> { result.append('\u000C'); i += 2 }
                'v' -> { result.append('\u000B'); i += 2 }
                'c' -> return BEscapeResult(result.toString(), true)
                'x' -> {
                    var j = i + 2
                    val hex = StringBuilder()
                    while (j < str.length && j < i + 4 && isHex(str[j])) {
                        hex.append(str[j]); j++
                    }
                    if (hex.isEmpty()) { result.append("\\x"); i += 2 }
                    else { result.append(hex.toString().toInt(16).toChar()); i = j }
                }
                'u' -> {
                    var j = i + 2
                    val hex = StringBuilder()
                    while (j < str.length && j < i + 6 && isHex(str[j])) {
                        hex.append(str[j]); j++
                    }
                    if (hex.isEmpty()) { result.append("\\u"); i += 2 }
                    else { result.append(hex.toString().toInt(16).toChar()); i = j }
                }
                '0' -> {
                    var j = i + 2
                    val octal = StringBuilder()
                    while (j < str.length && j < i + 5 && str[j] in '0'..'7') {
                        octal.append(str[j]); j++
                    }
                    if (octal.isEmpty()) { result.append('\u0000') }
                    else { result.append(octal.toString().toInt(8).toChar()) }
                    i = j
                }
                in '1'..'7' -> {
                    var j = i + 1
                    val octal = StringBuilder()
                    while (j < str.length && j < i + 4 && str[j] in '0'..'7') {
                        octal.append(str[j]); j++
                    }
                    result.append(octal.toString().toInt(8).toChar()); i = j
                }
                else -> { result.append(str[i]); i++ }
            }
        } else {
            result.append(str[i]); i++
        }
    }
    return BEscapeResult(result.toString(), false)
}

private fun isHex(c: Char): Boolean =
    (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

/**
 * Shell-quote a string for `%q` (bash-compatible backslash escaping).
 */
private fun shellQuote(str: String): String {
    if (str.isEmpty()) return "''"
    // String with only safe chars is returned as-is.
    if (Regex("^[a-zA-Z0-9_./-]+$").matches(str)) return str

    val result = StringBuilder()
    for (ch in str) {
        if (ch in " \t|&;<>()\$`\\\"'*?[#~=%!{}") {
            result.append('\\').append(ch)
        } else {
            result.append(ch)
        }
    }
    return result.toString()
}

private fun formatQuoted(specifier: Char, spec: String, str: String): String {
    val quoted = shellQuote(str)
    val m = Regex("^%(-?)(\\d*)q$").find(spec) ?: return quoted
    val leftJustify = m.groupValues[1] == "-"
    val width = m.groupValues[2].toIntOrNull() ?: 0
    return applyWidth(quoted, if (leftJustify) -width else width, -1)
}

/**
 * Apply width/alignment to an already-formatted value.
 */
private fun applyWidth(value: String, width: Int, precision: Int): String {
    var result = value
    if (precision >= 0 && result.length > precision) {
        result = result.substring(0, precision)
    }
    val absWidth = kotlin.math.abs(width)
    if (absWidth > result.length) {
        result = if (width < 0) result.padEnd(absWidth, ' ') else result.padStart(absWidth, ' ')
    }
    return result
}
