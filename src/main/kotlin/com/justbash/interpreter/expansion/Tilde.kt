package com.justbash.interpreter.expansion

fun applyTildeExpansion(ctx: ExpansionContext, value: String): String {
    if (!value.startsWith("~")) return value
    val home = ctx.state.env["HOME"] ?: "/home/user"
    if (value == "~" || value.startsWith("~/")) return home + value.substring(1)
    var i = 1; while (i < value.length && (value[i].isLetterOrDigit() || value[i] == '_' || value[i] == '-')) i++
    val username = value.substring(1, i); val rest = value.substring(i)
    if (rest.isNotEmpty() && !rest.startsWith("/")) return value
    if (username == "root") return "/root$rest"
    return value
}
fun applyAssignmentTildeExpansion(ctx: ExpansionContext, value: String): String = value.split(":").joinToString(":") { applyTildeExpansion(ctx, it) }
