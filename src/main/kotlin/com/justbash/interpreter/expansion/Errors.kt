package com.justbash.interpreter.expansion

open class ControlFlowError(message: String, open val stdout: String = "", open val stderr: String = "") : RuntimeException(message)
class NounsetError(val varName: String, stdout: String = "") : ControlFlowError("$varName: unbound variable", stdout, "bash: $varName: unbound variable\n")
class ExitError(val exitCode: Int, stdout: String = "", stderr: String = "") : ControlFlowError("exit", stdout, stderr)
class ArithmeticError(message: String, stdout: String = "", stderr: String = "", val fatal: Boolean = false) : ControlFlowError(message, stdout, if (stderr.isNotEmpty()) stderr else "bash: $message\n")
class BadSubstitutionError(message: String, stdout: String = "", stderr: String = "") : ControlFlowError(message, stdout, if (stderr.isNotEmpty()) stderr else "bash: $message: bad substitution\n")
class GlobError(pattern: String, stdout: String = "", stderr: String = "") : ControlFlowError("no match: $pattern", stdout, if (stderr.isNotEmpty()) stderr else "bash: no match: $pattern\n")
class BraceExpansionError(message: String, stdout: String = "", stderr: String = "") : ControlFlowError(message, stdout, if (stderr.isNotEmpty()) stderr else "bash: $message\n")
class ExecutionLimitError(message: String, val limitType: String, stdout: String = "", stderr: String = "") : ControlFlowError(message, stdout, if (stderr.isNotEmpty()) stderr else "bash: $message\n") { companion object { const val EXIT_CODE = 126 } }
class ExecutionAbortedError(stdout: String = "", stderr: String = "") : ControlFlowError("execution aborted", stdout, stderr)
