package org.tgbackup.desktop

const val VERSION = "0.1.0"

fun main() {
    println("TGBackup $VERSION")
    println(AppDirs.dataDir())
}
