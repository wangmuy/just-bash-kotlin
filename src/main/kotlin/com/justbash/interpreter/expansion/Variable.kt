package com.justbash.interpreter.expansion

import com.justbash.ShellMetadata

private fun isNameref(ctx: ExpansionContext, name: String): Boolean = ctx.state.namerefs.contains(name)
private fun resolveNameref(ctx: ExpansionContext, name: String, maxDepth: Int = 100): String? { if (!isNameref(ctx, name)) return name; if (ctx.state.invalidNamerefs.contains(name)) return name; val seen = LinkedHashSet<String>(); var current = name; var depth = maxDepth; while (depth-- > 0) { if (!seen.add(current)) return null; if (!isNameref(ctx, current)) return current; val target = ctx.state.env[current] ?: return current; if (!target.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\[.+\\])?$"))) return current; current = target }; return null }
internal fun getNamerefTarget(ctx: ExpansionContext, name: String): String? { if (!isNameref(ctx, name)) return null; return ctx.state.env[name] }

private val ALWAYS_SET = setOf("?", "$", "#", "_", "-", "0", "PPID", "UID", "EUID", "RANDOM", "SECONDS", "BASH_VERSION", "!", "BASHPID", "LINENO")

fun isVariableSet(ctx: ExpansionContext, name: String): Boolean {
    if (name in ALWAYS_SET) return true
    if (name == "@" || name == "*") { val n = (ctx.state.env["#"] ?: "0").toIntOrNull() ?: 0; return n > 0 }
    if (name == "PWD" || name == "OLDPWD") return ctx.state.env.containsKey(name)
    val bm = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\[(.+)\\]$").find(name)
    if (bm != null) { var an = bm.groupValues[1]; val sub = bm.groupValues[2]; if (isNameref(ctx, an)) { val r = resolveNameref(ctx, an); if (r != null && r != an) { if (Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\[(.+)\\]$").containsMatchIn(r)) return false; an = r } }; if (sub == "@" || sub == "*") { val els = getArrayElements(ctx, an); return els.isNotEmpty() || ctx.state.env.containsKey(an) }; if (ctx.state.associativeArrays.contains(an)) { val key = unquoteKey(sub); return hasArrayElement(ctx, an, key) }; val idx = sub.toIntOrNull() ?: 0; return hasArrayElement(ctx, an, idx) }
    if (isNameref(ctx, name)) { val r = resolveNameref(ctx, name); if (r == null || r == name) return ctx.state.env.containsKey(name); return isVariableSet(ctx, r) }
    if (ctx.state.env.containsKey(name)) return true
    if (isArray(ctx, name)) return hasArrayElement(ctx, name, 0)
    return false
}

fun getVariable(ctx: ExpansionContext, name: String, checkNounset: Boolean = true): String {
    when (name) {
        "?" -> return ctx.state.lastExitCode.toString()
        "$" -> return ctx.state.virtualPid.toString()
        "#" -> return ctx.state.env["#"] ?: "0"
        "@" -> return ctx.state.env["@"] ?: ""
        "_" -> return ctx.state.lastArg
        "-" -> { var f = "h"; if (ctx.state.options.errexit) f += "e"; if (ctx.state.options.noglob) f += "f"; if (ctx.state.options.nounset) f += "u"; if (ctx.state.options.verbose) f += "v"; if (ctx.state.options.xtrace) f += "x"; f += "B"; if (ctx.state.options.noclobber) f += "C"; f += "s"; return f }
        "*" -> { val n = (ctx.state.env["#"] ?: "0").toIntOrNull() ?: 0; if (n == 0) return ""; val params = (1..n).map { ctx.state.env[it.toString()] ?: "" }; return params.joinToString(getIfsSeparator(ctx.state.env)) }
        "0" -> return ctx.state.env["0"] ?: "bash"
        "PWD" -> return ctx.state.env["PWD"] ?: ""
        "OLDPWD" -> return ctx.state.env["OLDPWD"] ?: ""
        "PPID" -> return ctx.state.virtualPpid.toString()
        "UID" -> return ctx.state.virtualUid.toString()
        "EUID" -> return ctx.state.virtualUid.toString()
        "RANDOM" -> return ((Math.random() * 32768).toInt()).toString()
        "SECONDS" -> return ((System.currentTimeMillis() - ctx.state.startTime) / 1000).toString()
        "BASH_VERSION" -> return ShellMetadata.BASH_VERSION
        "!" -> return ctx.state.lastBackgroundPid.toString()
        "BASHPID" -> return ctx.state.bashPid.toString()
        "LINENO" -> return ctx.state.currentLine.toString()
        "FUNCNAME" -> { val fn = ctx.state.funcNameStack.firstOrNull(); if (fn != null) return fn; if (checkNounset && ctx.state.options.nounset) throw NounsetError("FUNCNAME"); return "" }
        "BASH_LINENO" -> { val l = ctx.state.callLineStack.firstOrNull(); if (l != null) return l.toString(); if (checkNounset && ctx.state.options.nounset) throw NounsetError("BASH_LINENO"); return "" }
        "BASH_SOURCE" -> { val s = ctx.state.sourceStack.firstOrNull(); if (s != null) return s; if (checkNounset && ctx.state.options.nounset) throw NounsetError("BASH_SOURCE"); return "" }
    }
    if (Regex("^[a-zA-Z_][a-zA-Z0-9_]*\\[\\]$").matches(name)) throw BadSubstitutionError("\${$name}")
    val bm = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\[(.+)\\]$").find(name)
    if (bm != null) { var an = bm.groupValues[1]; val sub = bm.groupValues[2]; if (isNameref(ctx, an)) { val r = resolveNameref(ctx, an); if (r != null && r != an) { val rb = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\[(.+)\\]$").find(r); if (rb != null) return ""; an = r } }; if (sub == "@" || sub == "*") { val els = getArrayElements(ctx, an); if (els.isNotEmpty()) return els.joinToString(" ") { (_, v) -> v }; val sv = ctx.state.env[an]; return sv ?: "" }; if (an == "FUNCNAME") { val idx = sub.toIntOrNull() ?: return ""; return ctx.state.funcNameStack.getOrElse(idx) { "" } }; if (an == "BASH_LINENO") { val idx = sub.toIntOrNull() ?: return ""; val l = ctx.state.callLineStack.getOrNull(idx) ?: return ""; return l.toString() }; if (an == "BASH_SOURCE") { val idx = sub.toIntOrNull() ?: return ""; return ctx.state.sourceStack.getOrElse(idx) { "" } }; if (ctx.state.associativeArrays.contains(an)) { var key = unquoteKey(sub); val value = getArrayElement(ctx, an, key); if (value == null && checkNounset && ctx.state.options.nounset) throw NounsetError("$an[$sub]"); return value ?: "" }; val idx = sub.toIntOrNull() ?: 0; val value = getArrayElement(ctx, an, idx); if (value != null) return value; if (idx == 0) { val sv = ctx.state.env[an]; if (sv != null) return sv }; if (checkNounset && ctx.state.options.nounset) throw NounsetError("$an[$idx]"); return "" }
    if (Regex("^[1-9][0-9]*$").matches(name)) { val value = ctx.state.env[name]; if (value == null && checkNounset && ctx.state.options.nounset) throw NounsetError(name); return value ?: "" }
    if (isNameref(ctx, name)) { val r = resolveNameref(ctx, name); if (r == null) return ""; if (r != name) return getVariable(ctx, r, checkNounset); val value = ctx.state.env[name]; if ((value == null || value == "") && checkNounset && ctx.state.options.nounset) throw NounsetError(name); return value ?: "" }
    val value = ctx.state.env[name]; if (value != null) return value
    if (isArray(ctx, name)) { val fv = getArrayElement(ctx, name, 0); if (fv != null) return fv; if (checkNounset && ctx.state.options.nounset) throw NounsetError(name); return "" }
    if (checkNounset && ctx.state.options.nounset) throw NounsetError(name)
    return ""
}
