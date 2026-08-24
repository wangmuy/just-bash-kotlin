package com.justbash.interpreter

import com.justbash.CommandRegistry
import com.justbash.ExecutionLimits
import com.justbash.ExecResult
import com.justbash.ast.CommandNode
import com.justbash.ast.ScriptNode
import com.justbash.ast.StatementNode
import com.justbash.fs.IFileSystem

class InterpreterContext(
    val state: InterpreterState,
    val fs: IFileSystem,
    val commands: CommandRegistry,
    val limits: ExecutionLimits = ExecutionLimits(),
    var wordExpander: WordExpander,
) {
    var executeStatement: (suspend (StatementNode) -> ExecResult) = { Result.ok() }
    var executeCommand: (suspend (CommandNode, ByteArray, Boolean) -> ExecResult) = { _, _, _ -> Result.ok() }
    var executeScript: (suspend (ScriptNode) -> ExecResult) = { Result.ok() }
    var execString: (suspend (String) -> ExecResult) = { Result.ok() }
    var findCommandInPath: (String) -> List<String> = { emptyList() }
    var buildExportedEnv: () -> Map<String, String> = { emptyMap() }
    var executeUserScript: (suspend (String, List<String>, ByteArray) -> ExecResult) = { _, _, _ -> Result.ok() }
}
