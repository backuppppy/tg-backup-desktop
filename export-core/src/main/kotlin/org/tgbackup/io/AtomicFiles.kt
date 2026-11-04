package org.tgbackup.io

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Moves a freshly written temp file over its target. File.renameTo fails on Windows when the
 * target exists (and returns false silently), so every save after the first was lost there.
 */
object AtomicFiles {
    private const val ATTEMPTS = 5
    private const val FIRST_DELAY_MS = 100L

    fun replace(tmp: File, target: File) {
        var attempt = 1
        while (true) {
            try {
                move(tmp, target)
                return
            } catch (e: NoSuchFileException) {
                throw e
            } catch (e: FileSystemException) {
                // AccessDeniedException is a FileSystemException: antivirus/indexer locks are brief.
                if (attempt >= ATTEMPTS) throw e
                // Backoff 100, 200, 400, 800 ms: about 1.5 s in total.
                Thread.sleep(FIRST_DELAY_MS shl (attempt - 1))
                attempt++
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
