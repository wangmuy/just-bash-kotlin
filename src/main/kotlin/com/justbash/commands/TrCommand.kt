package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Port of just-bash `tr/tr.ts`.
 */
object TrCommand : Command {
    override val name = "tr"

    private val defs = mapOf(
        "complement" to Args.Def(short = "c", long = "complement"),
        "complementUpper" to Args.Def(short = "C"),
        "delete" to Args.Def(short = "d", long = "delete"),
        "squeeze" to Args.Def(short = "s", long = "squeeze-repeats"),
    )

    private val posixClasses = mapOf(
        "[:alnum:]" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
        "[:alpha:]" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
        "[:blank:]" to " \t",
        "[:cntrl:]" to (CharArray(32) { i -> (i).toChar() }.concatToString() + 127.toChar().toString()),
        "[:digit:]" to "0123456789",
        "[:graph:]" to (CharArray(94) { i -> (33 + i).toChar() }.concatToString()),
        "[:lower:]" to "abcdefghijklmnopqrstuvwxyz",
        "[:print:]" to (CharArray(95) { i -> (32 + i).toChar() }.concatToString()),
        "[:punct:]" to "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
        "[:space:]" to " \t\n\r\u000C\u000B",
        "[:upper:]" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "[:xdigit:]" to "0123456789ABCDEFabcdef",
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "tr", "translate or delete characters",
                "tr [OPTION]... SET1 [SET2]",
                listOf(
                    "-c, -C, --complement   use the complement of SET1",
                    "-d, --delete           delete characters in SET1",
                    "-s, --squeeze-repeats  squeeze repeated characters",
                    "    --help             display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("tr", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val complementMode = parsed.flags.bool("complement") || parsed.flags.bool("complementUpper")
        val deleteMode = parsed.flags.bool("delete")
        val squeezeMode = parsed.flags.bool("squeeze")
        val sets = parsed.positional

        if (sets.isEmpty()) {
            return ExecResult(stdout = "", stderr = "tr: missing operand\n", exitCode = 1)
        }
        if (!deleteMode && !squeezeMode && sets.size < 2) {
            return ExecResult(stdout = "", stderr = "tr: missing operand after SET1\n", exitCode = 1)
        }

        val set1Raw = expandRange(sets[0])
        val set2 = if (sets.size > 1) expandRange(sets[1]) else ""

        // Decode stdin to UTF-8 for codepoint-level matching
        val content = ctx.stdin.toString(Charsets.UTF_8)
        val set1 = set1Raw.toSet()
        val set2Chars = set2.toSet()

        val isInSet1: (Char) -> Boolean = { ch -> if (complementMode) ch !in set1 else ch in set1 }

        val output = StringBuilder()

        if (deleteMode) {
            for (ch in content) {
                if (!isInSet1(ch)) output.append(ch)
            }
        } else if (squeezeMode && sets.size == 1) {
            var prev = '\u0000'
            for (ch in content) {
                if (isInSet1(ch) && ch == prev) continue
                output.append(ch)
                prev = ch
            }
        } else {
            var translatedPrev = ""
            if (complementMode) {
                val targetChar = if (set2.isNotEmpty()) set2[set2.length - 1].toString() else ""
                for (ch in content) {
                    if (ch !in set1) {
                        appendSqueezed(output, targetChar, translatedPrev, squeezeMode, set2Chars)
                        translatedPrev = targetChar
                    } else {
                        appendSqueezed(output, ch.toString(), translatedPrev, squeezeMode, set2Chars)
                        translatedPrev = ch.toString()
                    }
                }
            } else {
                val translationMap = HashMap<Char, Char>()
                for (idx in set1Raw.indices) {
                    val target = if (idx < set2.length) set2[idx] else set2[set2.length - 1]
                    translationMap[set1Raw[idx]] = target
                }
                for (ch in content) {
                    val translated = translationMap[ch]?.toString() ?: ch.toString()
                    appendSqueezed(output, translated, translatedPrev, squeezeMode, set2Chars)
                    translatedPrev = translated
                }
            }
        }

        return ExecResult(stdout = output.toString(), stderr = "", exitCode = 0)
    }

    private fun appendSqueezed(sb: StringBuilder, value: String, prev: String, squeezeMode: Boolean, set2Chars: Set<Char>) {
        if (squeezeMode && value.length == 1 && value[0] in set2Chars && value == prev) return
        sb.append(value)
    }

    private fun expandRange(set: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < set.length) {
            // POSIX character classes
            if (set[i] == '[' && i + 1 < set.length && set[i + 1] == ':') {
                var found = false
                for ((className, chars) in posixClasses) {
                    if (set.regionMatches(i, className, 0, className.length)) {
                        result.append(chars)
                        i += className.length
                        found = true
                        break
                    }
                }
                if (found) continue
            }

            // Escape sequences
            if (set[i] == '\\' && i + 1 < set.length) {
                val next = set[i + 1]
                result.append(
                    when (next) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> next
                    }
                )
                i += 2
                continue
            }

            // Character ranges a-z
            if (i + 2 < set.length && set[i + 1] == '-') {
                val start = set[i].code
                val end = set[i + 2].code
                if (end >= start) {
                    for (code in start..end) {
                        result.append(code.toChar())
                    }
                    i += 3
                    continue
                }
            }

            result.append(set[i])
            i++
        }
        return result.toString()
    }
}