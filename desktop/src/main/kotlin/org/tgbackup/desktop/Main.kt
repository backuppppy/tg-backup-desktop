package org.tgbackup.desktop

import org.tgbackup.app.BuildConfig

const val VERSION = BuildConfig.VERSION_NAME

fun main(args: Array<String>) {
    // First: TDLib extracts its DLLs to java.io.tmpdir, which must be ASCII.
    NativeTmp.ensureAsciiTmpdir("org.tgbackup.desktop.MainKt", args)
    println("TGBackup $VERSION")
    println(AppDirs.dataDir())
}
