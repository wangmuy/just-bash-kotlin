package com.justbash.interpreter

import com.justbash.ExecResult

object Result {
    fun ok(): ExecResult = ExecResult("", "", 0)
    fun success(stdout: String = ""): ExecResult = ExecResult(stdout, "", 0)
    fun failure(stderr: String, exitCode: Int = 1): ExecResult = ExecResult("", stderr, exitCode)
    fun result(stdout: String, stderr: String, exitCode: Int): ExecResult = ExecResult(stdout, stderr, exitCode)
    fun testResult(passed: Boolean): ExecResult = ExecResult("", "", if (passed) 0 else 1)
}

fun throwExecutionLimit(message: String, limitType: String, stdout: String = "", stderr: String = ""): Nothing {
    throw ExecutionLimitError(message, limitType, stdout, stderr)
}
