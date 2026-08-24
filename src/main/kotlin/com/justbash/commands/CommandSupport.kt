package com.justbash.commands

import com.justbash.ExecResult

/**
 * Shared helpers for command implementations.
 *
 * Port of just-bash `src/utils/args.ts` (parseArgs), `src/commands/help.ts`
 * (showHelp / hasHelpFlag / unknownOption), plus small encoding/latin1
 * semantics shared by several commands.
 */

internal object Args {
    /**
     * A parsed flag set. Boolean flags map to Boolean; string flags map to
     * String; number flags map to Int. Non-boolean flags without a default are
     * absent (null) unless explicitly provided.
     */
    class Flags {
        private val bools = HashMap<String, Boolean>()
        private val strings = HashMap<String, String?>()
        private val numbers = HashMap<String, Int?>()

        fun setBool(name: String, value: Boolean) { bools[name] = value }
        fun setString(name: String, value: String?) { strings[name] = value }
        fun setNumber(name: String, value: Int?) { numbers[name] = value }

        fun bool(name: String): Boolean = bools[name] ?: false
        fun string(name: String): String? = strings[name]
        fun number(name: String): Int? = numbers[name]
    }

    sealed class ParseOutcome {
        data class Ok(val flags: Flags, val positional: List<String>) : ParseOutcome()
        data class Err(val error: ExecResult) : ParseOutcome()
    }

    class Def(
        val short: String? = null,
        val long: String? = null,
        val type: String = "boolean",
        val default: Any? = UNSET,
    )

    object UNSET
}

/**
 * Lightweight argument parser mirroring just-bash `parseArgs`.
 */
internal fun parseArgs(
    cmdName: String,
    args: List<String>,
    defs: Map<String, Args.Def>,
): Args.ParseOutcome {
    val shortToInfo = HashMap<String, Pair<String, String>>()
    val longToInfo = HashMap<String, Pair<String, String>>()

    for ((name, def) in defs) {
        val info = Pair(name, def.type)
        def.short?.let { shortToInfo[it] = info }
        def.long?.let { longToInfo[it] = info }
    }

    val flags = Args.Flags()
    for ((name, def) in defs) {
        if (def.default !== Args.UNSET) {
            when (def.type) {
                "boolean" -> flags.setBool(name, def.default as Boolean)
                "string" -> flags.setString(name, def.default as String)
                "number" -> flags.setNumber(name, (def.default as Number).toInt())
            }
        } else if (def.type == "boolean") {
            flags.setBool(name, false)
        }
        // string/number without default remain unset (null)
    }

    val positional = ArrayList<String>()
    var stopParsing = false

    var i = 0
    while (i < args.size) {
        val arg = args[i]

        if (stopParsing || !arg.startsWith("-") || arg == "-") {
            positional.add(arg)
            i++
            continue
        }

        if (arg == "--") {
            stopParsing = true
            i++
            continue
        }

        if (arg.startsWith("--")) {
            // Long option
            val eqIndex = arg.indexOf('=')
            val optName: String
            var optValue: String? = null
            if (eqIndex != -1) {
                optName = arg.substring(2, eqIndex)
                optValue = arg.substring(eqIndex + 1)
            } else {
                optName = arg.substring(2)
            }

            val info = longToInfo[optName]
            if (info == null) {
                return Args.ParseOutcome.Err(unknownOption(cmdName, arg))
            }
            val (name, type) = info
            if (type == "boolean") {
                flags.setBool(name, true)
            } else {
                if (optValue == null) {
                    if (i + 1 >= args.size) {
                        return Args.ParseOutcome.Err(ExecResult(
                            stderr = "$cmdName: option '--$optName' requires an argument\n",
                            exitCode = 1,
                        ))
                    }
                    optValue = args[++i]
                }
                if (type == "number") {
                    flags.setNumber(name, optValue.toIntOrNull())
                } else {
                    flags.setString(name, optValue)
                }
            }
        } else {
            // Short option(s)
            val chars = arg.substring(1)
            var j = 0
            while (j < chars.length) {
                val c = chars[j].toString()
                val info = shortToInfo[c]
                if (info == null) {
                    return Args.ParseOutcome.Err(unknownOption(cmdName, "-$c"))
                }
                val (name, type) = info
                if (type == "boolean") {
                    flags.setBool(name, true)
                    j++
                } else {
                    val optValue: String
                    if (j + 1 < chars.length) {
                        optValue = chars.substring(j + 1)
                    } else if (i + 1 < args.size) {
                        optValue = args[++i]
                    } else {
                        return Args.ParseOutcome.Err(ExecResult(
                            stderr = "$cmdName: option requires an argument -- '$c'\n",
                            exitCode = 1,
                        ))
                    }
                    if (type == "number") {
                        flags.setNumber(name, optValue.toIntOrNull())
                    } else {
                        flags.setString(name, optValue)
                    }
                    break
                }
            }
        }
        i++
    }

    return Args.ParseOutcome.Ok(flags, positional)
}

internal fun unknownOption(cmdName: String, option: String): ExecResult {
    val msg = if (option.startsWith("--")) {
        "$cmdName: unrecognized option '$option'\n"
    } else {
        "$cmdName: invalid option -- '${option.replace(Regex("^-"), "")}'\n"
    }
    return ExecResult(stdout = "", stderr = msg, exitCode = 1)
}

internal fun hasHelpFlag(args: List<String>): Boolean = args.contains("--help")

/**
 * Render a help result mirroring just-bash `showHelp`.
 */
internal fun showHelp(
    name: String,
    summary: String,
    usage: String,
    options: List<String> = emptyList(),
    description: List<String>? = null,
): ExecResult {
    val sb = StringBuilder()
    sb.append("$name - $summary\n\n")
    sb.append("Usage: $usage\n")
    if (description != null && description.isNotEmpty()) {
        sb.append("\nDescription:\n")
        for (line in description) {
            if (line.isNotEmpty()) sb.append("  $line\n") else sb.append("\n")
        }
    }
    if (options.isNotEmpty()) {
        sb.append("\nOptions:\n")
        for (opt in options) {
            sb.append("  $opt\n")
        }
    }
    return ExecResult(stdout = sb.toString(), stderr = "", exitCode = 0)
}

/**
 * latin1FromBytes semantics: each byte becomes a character U+0000..U+00FF in
 * its own right. On the JVM we keep raw bytes and decode where the TS
 * original's latin1 view is used for byte-clean slicing. This helper returns
 * a String whose `.length` equals the byte count (one char per byte).
 */
internal fun latin1FromBytes(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        sb.append((b.toInt() and 0xFF).toChar())
    }
    return sb.toString()
}
