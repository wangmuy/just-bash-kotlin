package com.justbash.interpreter

sealed class ControlFlowError(
    override val message: String,
    var stdout: String = "",
    var stderr: String = "",
) : RuntimeException(message)

class BreakError(var levels: Int = 1, stdout: String = "", stderr: String = "") :
    ControlFlowError("break", stdout, stderr)

class ContinueError(var levels: Int = 1, stdout: String = "", stderr: String = "") :
    ControlFlowError("continue", stdout, stderr)

class ReturnError(val exitCode: Int = 0, stdout: String = "", stderr: String = "") :
    ControlFlowError("return", stdout, stderr)

class ErrexitError(val exitCode: Int, stdout: String = "", stderr: String = "") :
    ControlFlowError("errexit: command exited with status $exitCode", stdout, stderr)

class NounsetError(val varName: String, stdout: String = "") :
    ControlFlowError("$varName: unbound variable", stdout, "bash: $varName: unbound variable\n")

class ExitError(val exitCode: Int, stdout: String = "", stderr: String = "") :
    ControlFlowError("exit", stdout, stderr)

class ArithmeticError(message: String, stdout: String = "", stderr: String = "") :
    ControlFlowError(message, stdout, stderr) {
    init { if (stderr.isEmpty()) this.stderr = "bash: $message\n" }
}

class BadSubstitutionError(message: String, stdout: String = "", stderr: String = "") :
    ControlFlowError(message, stdout, stderr) {
    init { if (stderr.isEmpty()) this.stderr = "bash: $message: bad substitution\n" }
}

class GlobError(val pattern: String, stdout: String = "", stderr: String = "") :
    ControlFlowError("no match: $pattern", stdout, stderr) {
    init { if (stderr.isEmpty()) this.stderr = "bash: no match: $pattern\n" }
}

class ExecutionLimitError(message: String, val limitType: String, stdout: String = "", stderr: String = "") :
    ControlFlowError(message, stdout, stderr) {
    companion object { const val EXIT_CODE = 126 }
}

class PosixFatalError(val exitCode: Int, stdout: String = "", stderr: String = "") :
    ControlFlowError("posix fatal error", stdout, stderr)

class SubshellExitError(stdout: String = "", stderr: String = "") :
    ControlFlowError("subshell exit", stdout, stderr)

class ParseException(message: String, val line: Int = 1, val column: Int = 1) : RuntimeException(message)

fun isScopeExitError(e: Throwable): Boolean = e is BreakError || e is ContinueError || e is ReturnError
