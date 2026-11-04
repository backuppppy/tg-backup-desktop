package org.tgbackup.io

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Moves a freshly written temp file over its target. File.renameTo fails on Windows when the
 * target exists (and returns false silently), so every save after the first was lost there.
 */
object AtomicFiles {
    private const val ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 100L

    fun replace(tmp: File, target: File) {
        var attempt = 1
        while (true) {
            try {
                move(tmp, target)
                return
            } catch (e: FileSystemException) {
                // AccessDeniedException is a FileSystemException: antivirus/indexer locks are brief.
                if (attempt >= ATTEMPTS) throw e
                attempt++
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    private fun move(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }
}
