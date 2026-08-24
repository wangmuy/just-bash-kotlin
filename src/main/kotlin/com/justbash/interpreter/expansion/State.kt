package com.justbash.interpreter.expansion

import com.justbash.ast.ArithExpr
import com.justbash.ast.CommandNode
import com.justbash.ast.ScriptNode
import com.justbash.ast.StatementNode
import com.justbash.fs.IFileSystem

class ShellOptions(var errexit: Boolean = false, var pipefail: Boolean = false, var nounset: Boolean = false, var xtrace: Boolean = false, var verbose: Boolean = false, var posix: Boolean = false, var allexport: Boolean = false, var noclobber: Boolean = false, var noglob: Boolean = false, var noexec: Boolean = false, var vi: Boolean = false, var emacs: Boolean = false)
class ShoptOptions(var extglob: Boolean = false, var dotglob: Boolean = false, var nullglob: Boolean = false, var failglob: Boolean = false, var globstar: Boolean = false, var globskipdots: Boolean = true, var nocaseglob: Boolean = false, var nocasematch: Boolean = false, var expand_aliases: Boolean = false, var lastpipe: Boolean = false, var xpg_echo: Boolean = false)

class ShellArray(var kind: String, val elements: MutableMap<String, String> = LinkedHashMap())

class InterpreterState {
    var env: MutableMap<String, String> = LinkedHashMap()
    var arrays: MutableMap<String, ShellArray> = LinkedHashMap()
    var cwd: String = "/home/user"
    var previousDir: String = "/home/user"
    var lastExitCode: Int = 0
    var lastArg: String = ""
    var currentLine: Int = 1
    val options = ShellOptions()
    val shoptOptions = ShoptOptions()
    val associativeArrays: MutableSet<String> = LinkedHashSet()
    val namerefs: MutableSet<String> = LinkedHashSet()
    val boundNamerefs: MutableSet<String> = LinkedHashSet()
    val invalidNamerefs: MutableSet<String> = LinkedHashSet()
    val integerVars: MutableSet<String> = LinkedHashSet()
    val readonlyVars: MutableSet<String> = LinkedHashSet()
    val exportedVars: MutableSet<String> = LinkedHashSet()
    var virtualPid: Int = 1
    var virtualPpid: Int = 0
    var virtualUid: Int = 1000
    var virtualGid: Int = 1000
    var bashPid: Int = 1
    var nextVirtualPid: Int = 2
    var lastBackgroundPid: Int = 0
    var startTime: Long = System.currentTimeMillis()
    val funcNameStack: MutableList<String> = ArrayList()
    val callLineStack: MutableList<Int> = ArrayList()
    val sourceStack: MutableList<String> = ArrayList()
    var expansionExitCode: Int? = null
    var expansionStderr: String = ""
    var inCondition: Boolean = false
    var loopDepth: Int = 0
    var suppressVerbose: Boolean = false
}

class ExecutionLimits(var maxArrayElements: Int = 1_000_000, var maxStringLength: Int = 64 * 1024 * 1024, var maxBraceExpansionResults: Int = 100_000, var maxGlobOperations: Long = 1_000_000, var maxSubstitutionDepth: Int = 200)

class ExecutionScope {
    private val counters = HashMap<String, Long>()
    fun consumeLimited(kind: String, amount: Long, limit: Long, description: String) {
        val current = counters.getOrDefault(kind, 0L)
        if (current + amount > limit) { throw ExecutionLimitError("$description: execution limit exceeded ($limit)", "glob_operations") }
        counters[kind] = current + amount
    }
}

class RunResult(val stdout: String = "", val stderr: String = "", val exitCode: Int = 0)

interface ExpansionContext {
    val state: InterpreterState
    val fs: IFileSystem
    val limits: ExecutionLimits
    val executionScope: ExecutionScope
    var substitutionDepth: Int
    fun executeScript(node: ScriptNode): RunResult
    fun execFn(script: String): RunResult
    fun globList(pattern: String): List<String>
}

open class ExpansionHost(
    final override val state: InterpreterState,
    final override val fs: IFileSystem,
    final override val limits: ExecutionLimits = ExecutionLimits(),
    final override val executionScope: ExecutionScope = ExecutionScope(),
    override var substitutionDepth: Int = 0,
    private val _executeScript: (ScriptNode) -> RunResult = { RunResult() },
    private val _execFn: (String) -> RunResult = { RunResult() },
    private val _globList: (String) -> List<String> = { emptyList() },
) : ExpansionContext {
    override fun executeScript(node: ScriptNode): RunResult = _executeScript(node)
    override fun execFn(script: String): RunResult = _execFn(script)
    override fun globList(pattern: String): List<String> = _globList(pattern)
}
