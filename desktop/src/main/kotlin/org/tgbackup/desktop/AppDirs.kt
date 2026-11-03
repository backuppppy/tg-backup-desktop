package org.tgbackup.desktop

import java.nio.file.Path
import java.nio.file.Paths

object AppDirs {
    /**
     * Windows: %LOCALAPPDATA%\TGBackup (fallback <home>\AppData\Local\TGBackup).
     * Other systems: <home>/.tgbackup.
     * The Windows path is assembled as a string so the result is identical on any host.
     */
    fun dataDir(
        env: Map<String, String> = System.getenv(),
        home: String = System.getProperty("user.home"),
        os: String = System.getProperty("os.name"),
    ): Path {
        if (os.startsWith("Windows", ignoreCase = true)) {
            val base = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
                ?: (home.trimEnd('\\', '/') + "\\AppData\\Local")
            return Paths.get(base.trimEnd('\\', '/') + "\\TGBackup")
        }
        return Paths.get(home.trimEnd('/') + "/.tgbackup")
    }
}
