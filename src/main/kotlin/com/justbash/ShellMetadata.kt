package com.justbash

/**
 * Shell metadata: version strings and virtual process information formatting.
 */
object ShellMetadata {
    const val BASH_VERSION = "5.1.0(1)-release"
    const val KERNEL_VERSION = "Linux version 5.15.0-generic (just-bash) #1 SMP PREEMPT"

    data class VirtualProcessInfo(
        val pid: Int,
        val ppid: Int,
        val uid: Int,
        val gid: Int,
    )

    fun formatProcStatus(info: VirtualProcessInfo): String {
        val (pid, ppid, uid, gid) = info
        return "Name:\tbash\n" +
            "State:\tR (running)\n" +
            "Pid:\t$pid\n" +
            "PPid:\t$ppid\n" +
            "Uid:\t$uid\t$uid\t$uid\t$uid\n" +
            "Gid:\t$gid\t$gid\t$gid\t$gid\n"
    }
}
