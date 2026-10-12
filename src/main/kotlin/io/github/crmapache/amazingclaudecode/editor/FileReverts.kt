package io.github.crmapache.amazingclaudecode.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * The disk half of [ChangeRevert]: one file read, its turn undone - or made again - and the result written
 * back.
 *
 * Through the file's document and as a command of the IDE, rather than straight onto the disk. That way an
 * editor showing the file changes in place, and the revert is one more step on that editor's undo - a person
 * who pressed the wrong row takes it back with the key they already know. A file the turn created is deleted
 * the same way, so the IDE's local history keeps it, and is written anew when the changes are brought back.
 *
 * Runs off the event thread: the file is refreshed from the disk first (the agent wrote it from outside, and
 * the IDE's picture may be a step behind), and that is a walk of the disk. The edit itself goes to the event
 * thread, as every write to a document must.
 */
internal object FileReverts {

    private const val REVERT = "Revert Claude's Changes"
    private const val RESTORE = "Bring Back Claude's Changes"

    /** [edits] undone, or with [restore] made again after an undo. */
    fun apply(project: Project, path: String, edits: List<ChangeRevert.Edit>, restore: Boolean): ChangeRevert.Outcome {
        val nio = runCatching { Path.of(path) }.getOrNull()?.takeIf { it.isAbsolute }
            ?: return ChangeRevert.Outcome.FAILED
        val created = edits.firstOrNull() is ChangeRevert.Edit.Create

        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nio)
        if (file == null || !file.isValid || file.isDirectory) {
            return when {
                // A file the turn created and somebody has since removed is already what a revert would make it.
                !restore -> if (created) ChangeRevert.Outcome.REVERTED else ChangeRevert.Outcome.MISSING
                // And bringing it back is writing it anew.
                created -> onEventThread(project, ChangeRevert.Outcome.RESTORED) { recreate(project, nio, edits) }
                else -> ChangeRevert.Outcome.MISSING
            }
        }
        file.refresh(false, false)

        return onEventThread(project, if (restore) ChangeRevert.Outcome.RESTORED else ChangeRevert.Outcome.REVERTED) {
            rewrite(project, file, edits, restore)
        }
    }

    /** [work] on the event thread, with anything it throws said as a failure rather than lost. */
    private fun onEventThread(project: Project, done: ChangeRevert.Outcome, work: () -> ChangeRevert.Outcome): ChangeRevert.Outcome {
        var outcome = ChangeRevert.Outcome.FAILED
        ApplicationManager.getApplication().invokeAndWait(
            {
                if (project.isDisposed) return@invokeAndWait
                outcome = runCatching { work() }
                    .onFailure { thisLogger().warn("Couldn't put a file ${if (done == ChangeRevert.Outcome.RESTORED) "forward" else "back"}", it) }
                    .getOrDefault(ChangeRevert.Outcome.FAILED)
            },
            ModalityState.defaultModalityState(),
        )
        return outcome
    }

    private fun rewrite(project: Project, file: VirtualFile, edits: List<ChangeRevert.Edit>, restore: Boolean): ChangeRevert.Outcome {
        val manager = FileDocumentManager.getInstance()
        // No document means a file the IDE does not hold as text - nothing the CLI edited line by line.
        val document = manager.getDocument(file) ?: return ChangeRevert.Outcome.FAILED
        val result = if (restore) ChangeRevert.bringBack(document.text, edits) else ChangeRevert.revert(document.text, edits)

        return when (result) {
            ChangeRevert.Result.Changed -> ChangeRevert.Outcome.CHANGED

            ChangeRevert.Result.Delete -> {
                WriteCommandAction.writeCommandAction(project).withName(REVERT).run<Throwable> { file.delete(this) }
                ChangeRevert.Outcome.REVERTED
            }

            is ChangeRevert.Result.Text -> {
                if (!document.isWritable) return ChangeRevert.Outcome.FAILED
                val before = document.text
                val after = result.text
                // Only the stretch that differs is replaced: the caret, the folds and the scroll of an editor
                // showing the file stay where they were everywhere else.
                val head = before.commonPrefixWith(after).length
                val tail = minOf(before.length, after.length).let { shorter ->
                    var same = 0
                    while (same < shorter - head && before[before.length - 1 - same] == after[after.length - 1 - same]) same++
                    same
                }
                if (head < before.length - tail || head < after.length - tail) {
                    WriteCommandAction.writeCommandAction(project).withName(if (restore) RESTORE else REVERT).run<Throwable> {
                        document.replaceString(head, before.length - tail, after.substring(head, after.length - tail))
                    }
                    manager.saveDocument(document)
                }
                if (restore) ChangeRevert.Outcome.RESTORED else ChangeRevert.Outcome.REVERTED
            }
        }
    }

    /** A created file the revert deleted, written again as the turn left it - its folders too, if they went with it. */
    private fun recreate(project: Project, path: Path, edits: List<ChangeRevert.Edit>): ChangeRevert.Outcome {
        val made = ChangeRevert.redo(null, edits) as? ChangeRevert.Result.Text ?: return ChangeRevert.Outcome.CHANGED
        val parent = path.parent ?: return ChangeRevert.Outcome.FAILED

        WriteCommandAction.writeCommandAction(project).withName(RESTORE).run<Throwable> {
            val folder = VfsUtil.createDirectoryIfMissing(parent.toString()) ?: error("no folder")
            val file = folder.findChild(path.fileName.toString()) ?: folder.createChildData(this, path.fileName.toString())
            VfsUtil.saveText(file, made.text)
        }
        return ChangeRevert.Outcome.RESTORED
    }
}
