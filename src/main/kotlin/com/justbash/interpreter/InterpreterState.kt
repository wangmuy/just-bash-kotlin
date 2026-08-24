package com.justbash.interpreter

import com.justbash.ExecutionLimits
import com.justbash.ast.FunctionDefNode
import java.time.LocalDateTime
import java.time.ZoneOffset

data class ShellOptions(
    var errexit: Boolean = false, var pipefail: Boolean = false, var nounset: Boolean = false,
    var xtrace: Boolean = false, var verbose: Boolean = false, var posix: Boolean = false,
    var allexport: Boolean = false, var noclobber: Boolean = false, var noglob: Boolean = false,
    var noexec: Boolean = false, var vi: Boolean = false, var emacs: Boolean = false,
)

data class ShoptOptions(
    var extglob: Boolean = false, var dotglob: Boolean = false, var nullglob: Boolean = false,
    var failglob: Boolean = false, var globstar: Boolean = false, var globskipdots: Boolean = true,
    var nocaseglob: Boolean = false, var nocasematch: Boolean = false, var expand_aliases: Boolean = false,
    var lastpipe: Boolean = false, var xpg_echo: Boolean = false,
)

data class ShellArray(var kind: String, val elements: MutableMap<String, String> = LinkedHashMap())

data class LocalVarStackEntry(val value: String?, val scopeIndex: Int)

class InterpreterState(
    val env: MutableMap<String, String> = LinkedHashMap(),
    var arrays: MutableMap<String, ShellArray>? = null,
    var cwd: String = "/home/user",
    var previousDir: String = "/home/user",
    var lastExitCode: Int = 0,
    var lastArg: String = "",
    var currentLine: Int = 0,
    val options: ShellOptions = ShellOptions(),
    val shoptOptions: ShoptOptions = ShoptOptions(),
    val functions: MutableMap<String, FunctionDefNode> = LinkedHashMap(),
    var callDepth: Int = 0,
    var sourceDepth: Int = 0,
    var loopDepth: Int = 0,
    var inCondition: Boolean = false,
    var errexitSafe: Boolean = false,
    var parentHasLoopContext: Boolean = false,
    var commandCount: Long = 0,
    var startTime: Long = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC),
    var lastBackgroundPid: Int = 0,
    var bashPid: Int = 1,
    var nextVirtualPid: Int = 100,
    var virtualPid: Int = 1,
    val localScopes: MutableList<MutableMap<String, String?>> = ArrayList(),
    var localArrayScopes: MutableList<MutableMap<String, ShellArray?>>? = null,
    var localVarDepth: MutableMap<String, Int>? = null,
    var localVarStack: MutableMap<String, MutableList<LocalVarStackEntry>>? = null,
    var fullyUnsetLocals: MutableMap<String, Int>? = null,
    var tempEnvBindings: MutableList<MutableMap<String, String?>>? = null,
    var readonlyVars: MutableSet<String>? = null,
    var associativeArrays: MutableSet<String>? = null,
    var namerefs: MutableSet<String>? = null,
    var integerVars: MutableSet<String>? = null,
    var lowercaseVars: MutableSet<String>? = null,
    var uppercaseVars: MutableSet<String>? = null,
    var exportedVars: MutableSet<String>? = null,
    var tempExportedVars: MutableSet<String>? = null,
    var localExportedVars: MutableList<MutableSet<String>>? = null,
    var declaredVars: MutableSet<String>? = null,
    var funcNameStack: MutableList<String>? = null,
    var callLineStack: MutableList<Int>? = null,
    var sourceStack: MutableList<String>? = null,
    var currentSource: String? = null,
    var groupStdin: String? = null,
    var groupStdinSourceFd: Int? = null,
    var fileDescriptors: MutableMap<Int, String>? = null,
    var inputFds: MutableSet<Int>? = null,
    var fdAliases: MutableMap<Int, MutableSet<Int>>? = null,
    var closedStandardFds: MutableSet<Int>? = null,
    var nextFd: Int? = null,
    var expansionStderr: String? = null,
    var extraArgs: List<String>? = null,
    var hashTable: MutableMap<String, String>? = null,
    var directoryStack: MutableList<String>? = null,
    var completions: MutableMap<String, String> = LinkedHashMap(),
    var completionOptions: MutableMap<String, MutableSet<String>> = LinkedHashMap(),
) {
    fun cloneBasics(): InterpreterState {
        val s = InterpreterState(
            env = LinkedHashMap(env),
            arrays = arrays?.let { LinkedHashMap(it) },
            cwd = cwd, previousDir = previousDir, lastExitCode = lastExitCode,
            lastArg = lastArg, currentLine = currentLine,
            options = options.copy(), shoptOptions = shoptOptions.copy(),
        )
        s.functions.putAll(functions)
        s.callDepth = callDepth; s.sourceDepth = sourceDepth; s.loopDepth = loopDepth
        s.parentHasLoopContext = parentHasLoopContext; s.commandCount = commandCount
        s.startTime = startTime; s.bashPid = bashPid; s.nextVirtualPid = nextVirtualPid
        s.virtualPid = virtualPid
        return s
    }
}

data class InterpreterOptions(val limits: ExecutionLimits = ExecutionLimits())
