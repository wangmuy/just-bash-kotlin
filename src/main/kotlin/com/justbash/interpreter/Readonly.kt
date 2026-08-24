package com.justbash.interpreter

object Readonly {
    fun markReadonly(ctx: InterpreterContext, name: String) {
        if (ctx.state.readonlyVars == null) ctx.state.readonlyVars = LinkedHashSet()
        ctx.state.readonlyVars!!.add(name)
    }

    fun isReadonly(ctx: InterpreterContext, name: String): Boolean =
        ctx.state.readonlyVars?.contains(name) == true

    fun checkReadonlyError(ctx: InterpreterContext, name: String, command: String = "bash") {
        if (isReadonly(ctx, name)) {
            throw ExitError(1, "", "$command: $name: readonly variable\n")
        }
    }

    fun markExported(ctx: InterpreterContext, name: String) {
        if (ctx.state.exportedVars == null) ctx.state.exportedVars = LinkedHashSet()
        ctx.state.exportedVars!!.add(name)
        if (ctx.state.localScopes.isNotEmpty()) {
            val currentScope = ctx.state.localScopes[ctx.state.localScopes.size - 1]
            if (currentScope.containsKey(name)) {
                if (ctx.state.localExportedVars == null) ctx.state.localExportedVars = ArrayList()
                val localExports = ctx.state.localExportedVars!!
                while (localExports.size < ctx.state.localScopes.size) localExports.add(LinkedHashSet())
                localExports[localExports.size - 1].add(name)
            }
        }
    }

    fun unmarkExported(ctx: InterpreterContext, name: String) {
        ctx.state.exportedVars?.remove(name)
    }
}
