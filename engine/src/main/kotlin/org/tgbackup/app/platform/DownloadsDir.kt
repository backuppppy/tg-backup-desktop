package org.tgbackup.app.platform

import com.sun.jna.Platform
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import java.io.File

/** The user's Downloads folder: the Windows Known Folder (may be relocated), else `<home>/Downloads`. */
object DownloadsDir {
    fun resolve(
        knownFolder: () -> String? = ::windowsKnownFolder,
        home: String = System.getProperty("user.home"),
    ): File {
        val known = knownFolder()
        return if (!known.isNullOrBlank()) File(known) else File(home, "Downloads")
    }

    fun windowsKnownFolder(): String? =
        if (Platform.isWindows()) {
            runCatching { Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads) }.getOrNull()
        } else {
            null
        }
}
