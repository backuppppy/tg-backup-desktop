package org.tgbackup.app.platform

/** What the desktop program reports to Telegram in place of Android's Build.MANUFACTURER/MODEL/RELEASE. */
object DeviceInfo {
    val deviceModel: String = "TG Backup Desktop"

    /** For example "Windows 11". */
    val systemVersion: String = System.getProperty("os.name")
}
