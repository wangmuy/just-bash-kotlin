package com.justbash.interpreter

import com.justbash.ExecResult
import com.justbash.ast.FunctionDefNode

object Functions {
    suspend fun executeFunctionDef(ctx: InterpreterContext, node: FunctionDefNode): ExecResult {
        ctx.state.functions[node.name] = node
        return Result.ok()
    }

    suspend fun callFunction(ctx: InterpreterContext, func: FunctionDefNode, args: List<String>, stdin: ByteArray = ByteArray(0)): ExecResult {
        ctx.state.callDepth++
        if (ctx.state.callDepth > ctx.limits.maxCallDepth) {
            ctx.state.callDepth--
            throwExecutionLimit("${func.name}: maximum recursion depth (${ctx.limits.maxCallDepth}) exceeded", "recursion")
        }
        if (ctx.state.funcNameStack == null) ctx.state.funcNameStack = ArrayList()
        if (ctx.state.callLineStack == null) ctx.state.callLineStack = ArrayList()
        if (ctx.state.sourceStack == null) ctx.state.sourceStack = ArrayList()
        ctx.state.funcNameStack!!.add(0, func.name)
        ctx.state.callLineStack!!.add(0, ctx.state.currentLine)
        ctx.state.sourceStack!!.add(0, func.sourceFile ?: "main")

        ctx.state.localScopes.add(LinkedHashMap())
        if (ctx.state.localArrayScopes == null) ctx.state.localArrayScopes = ArrayList()
        ctx.state.localArrayScopes!!.add(LinkedHashMap())

        val savedPositional = LinkedHashMap<String, String?>()
        val oldCount = (ctx.state.env["#"] ?: "0").toInt()
        val extent = maxOf(args.size, oldCount)
        for (i in 0 until extent) {
            val key = (i + 1).toString()
            savedPositional[key] = ctx.state.env[key]
            if (i < args.size) ctx.state.env[key] = args[i] else ctx.state.env.remove(key)
        }
        savedPositional["@"] = ctx.state.env["@"]
        savedPositional["#"] = ctx.state.env["#"]
        ctx.state.env["@"] = args.joinToString(" ")
        ctx.state.env["#"] = args.size.toString()

        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            val localScope = ctx.state.localScopes.removeAt(ctx.state.localScopes.size - 1)
            ctx.state.localArrayScopes?.removeAt(ctx.state.localArrayScopes!!.size - 1)
            for ((varName, originalValue) in localScope) {
                if (originalValue == null) ctx.state.env.remove(varName) else ctx.state.env[varName] = originalValue
            }
            for ((key, value) in savedPositional) {
                if (value == null) ctx.state.env.remove(key) else ctx.state.env[key] = value
            }
            ctx.state.funcNameStack?.removeAt(0)
            ctx.state.callLineStack?.removeAt(0)
            ctx.state.sourceStack?.removeAt(0)
            ctx.state.callDepth--
        }

        try {
            return ctx.executeCommand(func.body, stdin, stdin.isNotEmpty())
        } catch (e: ReturnError) {
            cleanup()
            return Result.result(e.stdout, e.stderr, e.exitCode)
        } catch (e: ControlFlowError) {
            cleanup()
            throw e
        } finally {
            cleanup()
        }
    }
}
