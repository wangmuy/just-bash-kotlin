package com.justbash.interpreter

import com.justbash.ast.*
import com.justbash.fs.IFileSystem
import com.justbash.interpreter.expansion.ExecutionLimits as ExpLimits
import com.justbash.interpreter.expansion.ExecutionScope as ExpScope
import com.justbash.interpreter.expansion.InterpreterState as ExpState
import com.justbash.interpreter.expansion.ShellArray as ExpShellArray
import com.justbash.interpreter.expansion.RunResult
import com.justbash.interpreter.expansion.ExpansionHost
import com.justbash.interpreter.expansion.ExpansionContext
import com.justbash.interpreter.expansion.expandWord as expExpandWord
import kotlinx.coroutines.runBlocking

/**
 * Bridges the full [com.justbash.interpreter.expansion] word-expansion engine
 * into the interpreter's [WordExpander] interface.
 *
 * Key design: the expansion package's [ExpState.env] and the interpreter's
 * [InterpreterState.env] point at THE SAME `MutableMap` instance, so variable
 * mutations made during expansion (e.g. `${var:=default}`, `${var@Q}`) are
 * immediately visible to the interpreter and vice-versa — no bidirectional
 * copy. Arrays are shallow-mirrored (one `ShellArray` conversion per name) on
 * each expansion call because the two packages use distinct `ShellArray`
 * classes.
 */
class ExpansionBridge(
    private val interpreterCtx: InterpreterContext,
    private val fs: IFileSystem,
) : WordExpander {

    /** Expansion package state. `env` is shared with the interpreter. */
    private val expState = ExpState()

    private val expLimits = ExpLimits(
        maxStringLength = interpreterCtx.limits.maxStringLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        maxBraceExpansionResults = 100_000,
        maxGlobOperations = 1_000_000,
        maxSubstitutionDepth = 200,
    )

    private val expScope = ExpScope()

    /** The [ExpansionContext] backing this bridge; exposed for the interpreter's arithmetic evaluator. */
    val host: ExpansionContext = object : ExpansionHost(
        state = expState,
        fs = fs,
        limits = expLimits,
        executionScope = expScope,
        substitutionDepth = 0,
        _executeScript = { node ->
            val r = runBlocking { interpreterCtx.executeScript(node) }
            RunResult(r.stdout, r.stderr, r.exitCode)
        },
        _execFn = { script ->
            val r = runBlocking { interpreterCtx.execString(script) }
            RunResult(r.stdout, r.stderr, r.exitCode)
        },
        _globList = { pattern -> globList(pattern) },
    ) {}

    // ── WordExpander interface ────────────────────────────────────────

    override fun expandWord(word: WordNode): String {
        sync()
        return expExpandWord(host, word)
    }

    override fun expandWordWithGlob(word: WordNode): List<String> {
        sync()
        val expanded = expExpandWord(host, word)
        if (expState.options.noglob || !hasGlobChars(expanded)) {
            return listOf(expanded)
        }
        val matches = globList(expanded)
        return if (matches.isEmpty() && !expState.shoptOptions.nullglob) listOf(expanded) else matches
    }

    override fun expandRedirectTarget(word: WordNode): String {
        sync()
        return expExpandWord(host, word)
    }

    override fun isWordFullyQuoted(word: WordNode): Boolean =
        word.parts.all { it is SingleQuotedPart || it is EscapedPart }

    // ── state sync ────────────────────────────────────────────────────

    /**
     * Push the interpreter's live state into the expansion state. This MUST
     * be called before any expansion or arithmetic function that uses [host].
     * It is cheap: env is shared by reference, arrays are shallow-mirrored.
     */
    internal fun sync() {
        expState.env = interpreterCtx.state.env

        // Mirror arrays (shallow conversion per name)
        val interpArrays = interpreterCtx.state.arrays
        val expArrays = LinkedHashMap<String, ExpShellArray>()
        if (interpArrays != null) {
            for ((name, arr) in interpArrays) {
                expArrays[name] = ExpShellArray(arr.kind, arr.elements)
            }
        }
        expState.arrays = expArrays

        // Scalar fields
        expState.cwd = interpreterCtx.state.cwd
        expState.lastExitCode = interpreterCtx.state.lastExitCode
        expState.lastArg = interpreterCtx.state.lastArg
        expState.currentLine = interpreterCtx.state.currentLine

        // Options
        val o = interpreterCtx.state.options
        val so = interpreterCtx.state.shoptOptions
        expState.options.errexit = o.errexit
        expState.options.pipefail = o.pipefail
        expState.options.nounset = o.nounset
        expState.options.xtrace = o.xtrace
        expState.options.noglob = o.noglob
        expState.shoptOptions.extglob = so.extglob
        expState.shoptOptions.dotglob = so.dotglob
        expState.shoptOptions.nullglob = so.nullglob
        expState.shoptOptions.failglob = so.failglob
        expState.shoptOptions.globstar = so.globstar
        expState.shoptOptions.nocaseglob = so.nocaseglob
        expState.shoptOptions.nocasematch = so.nocasematch
        expState.shoptOptions.xpg_echo = so.xpg_echo
        expState.inCondition = interpreterCtx.state.inCondition
        expState.loopDepth = interpreterCtx.state.loopDepth

        // Write back array mutations (env is shared, so already sync'd)
        writeBackArrays()
    }

    private fun writeBackArrays() {
        val interpArrays = interpreterCtx.state.arrays
        if (interpArrays != null) {
            // Clear and refill from mirrored expansion arrays
            interpArrays.clear()
            for ((name, arr) in expState.arrays) {
                interpArrays[name] = com.justbash.interpreter.ShellArray(arr.kind, arr.elements)
            }
        }
    }

    // ── glob support ──────────────────────────────────────────────────

    private fun globList(pattern: String): List<String> {
        // Only glob the basename for simplicity; match against cwd entries
        val lastSlash = pattern.lastIndexOf('/')
        val dir: String
        val globPart: String
        if (lastSlash == -1) {
            dir = expState.cwd
            globPart = pattern
        } else {
            dir = if (lastSlash == 0) "/" else pattern.substring(0, lastSlash)
            globPart = pattern.substring(lastSlash + 1)
        }
        val resolvedDir = fs.resolvePath(expState.cwd, dir)
        return try {
            val regex = globToRegex(globPart)
            val entries = fs.readdirWithFileTypes(resolvedDir).filter { regex.matches(it.name) }
            entries.map { if (lastSlash == -1) it.name else "$dir/${it.name}" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when (c) {
                '*' -> if (i + 1 < pattern.length && pattern[i + 1] == '*') { sb.append(".*"); i++ } else sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                '[' -> {
                    var j = i + 1
                    while (j < pattern.length && pattern[j] != ']') j++
                    if (j < pattern.length) { sb.append(pattern.substring(i, j + 1)); i = j } else sb.append("\\[")
                }
                '.', '^', '$', '{', '}', '(', ')', '|', '+', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    private fun hasGlobChars(s: String): Boolean =
        s.any { it == '*' || it == '?' || it == '[' }
}

/** Factory for creating an [ExpansionBridge] from an [InterpreterContext]. */
fun InterpreterContext.createFullExpander(): WordExpander =
    ExpansionBridge(this, this.fs)