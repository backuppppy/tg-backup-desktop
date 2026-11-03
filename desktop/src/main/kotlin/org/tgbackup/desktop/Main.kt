package org.tgbackup.desktop

const val VERSION = "0.1.0"

fun main(args: Array<String>) {
    // First: TDLib extracts its DLLs to java.io.tmpdir, which must be ASCII.
    NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.MainKt", args)
    println("TGBackup $VERSION")
    println(AppDirs.dataDir())
}
