package org.tgbackup.desktop

const val VERSION = "0.1.0"

fun main() {
    // Before anything creates a temp file: TDLib extracts its DLLs to java.io.tmpdir.
    NativeTmp.apply()
    println("TGBackup $VERSION")
    println(AppDirs.dataDir())
}
