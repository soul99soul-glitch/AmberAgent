package app.amber.feature.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspacePathsTest {
    @Test
    fun normalizeAcceptsWorkspaceRelativePaths() {
        assertEquals(".", WorkspacePaths.normalize("/workspace"))
        assertEquals(".", WorkspacePaths.normalize("workspace"))
        assertEquals("notes/todo.txt", WorkspacePaths.normalize("/workspace/notes/todo.txt"))
        assertEquals("notes/todo.txt", WorkspacePaths.normalize("workspace/notes/./todo.txt"))
        assertEquals("notes/todo.txt", WorkspacePaths.normalize("notes//todo.txt"))
    }

    @Test
    fun normalizeRejectsTraversalAndNonWorkspaceAbsolutePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePaths.normalize("../secret.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePaths.normalize("/workspace/../secret.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePaths.normalize("/etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePaths.normalize("/workspacefoo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePaths.normalize("/workspace2/foo")
        }
    }
}
