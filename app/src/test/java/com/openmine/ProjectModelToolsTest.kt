package com.openmine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectModelToolsTest {
    private fun project(id: String, status: String = "VERIFIED") = StrictObject(mapOf("OBJECT_ID" to id, "OBJECT_TYPE" to "PROJECT", "OBJECT_STATUS" to status), emptyMap(), "")
    private fun proposal(name: String, args: JSONObject) = ModelToolProposal.from(JSONObject().put("id", "test-call").put("function", JSONObject().put("name", name).put("arguments", args.toString())))

    @Test fun approvedReadAndCreateStayInSelectedProjectAndNeverOverwrite() {
        val root = kotlin.io.path.createTempDirectory("project-tools").toFile()
        try {
            val records = listOf(project("project.a"), project("project.b"))
            val scope = RetrievalScope("conversation.a", "project.a")
            val a = ProjectStore(root, "project.a")
            val b = ProjectStore(root, "project.b")
            a.createText("shared.txt", "allowed")
            b.createText("shared.txt", "other project's secret")
            fun execute(p: ModelToolProposal) = ProjectModelTools.execute(root, records, scope, p) {}
            val read = proposal("read_project_file", JSONObject().put("name", "shared.txt").put("offset", 0))
            assertEquals("allowed", JSONObject(execute(read)).getString("content"))
            val create = proposal("create_project_file", JSONObject().put("name", "code.py").put("content", "print('hello')\n"))
            ReviewedModelTools.execute(create, { false }) { execute(create) }
            assertEquals(listOf("shared.txt"), a.listFiles().map { it.name })
            assertTrue(JSONObject(ReviewedModelTools.execute(create, { true }) { execute(create) }).getBoolean("created"))
            assertEquals("print('hello')\n", a.readText("code.py"))
            assertThrows(IllegalArgumentException::class.java) { execute(create) }
            assertEquals("other project's secret", b.readText("shared.txt"))
        } finally { root.deleteRecursively() }
    }

    @Test fun missingArchivedExcludedProjectsAndCancellationCannotWrite() {
        val root = kotlin.io.path.createTempDirectory("project-tools").toFile()
        try {
            val p = proposal("create_project_file", JSONObject().put("name", "new.txt").put("content", "text"))
            val scope = RetrievalScope("chat", "project.a")
            for ((records, selected) in listOf(emptyList<StrictObject>() to scope,
                listOf(project("project.a", "ARCHIVED")) to scope,
                listOf(project("project.a")) to scope.copy(projectId = null),
                listOf(project("project.a")) to scope.copy(excludedIds = setOf("project.a")))) {
                assertTrue(runCatching { ProjectModelTools.execute(root, records, selected, p) {} }.isFailure)
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ProjectModelTools.execute(root, listOf(project("project.a")), scope, p) { throw java.util.concurrent.CancellationException() }
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun pathsExtraArgumentsAndNonIntegerOffsetsAreRejectedBeforeApproval() {
        for (name in listOf("../escape", "/sdcard/private.txt", "folder/file", "weights.gguf/../x")) {
            assertTrue(runCatching { proposal("read_project_file", JSONObject().put("name", name).put("offset", 0)) }.isFailure)
        }
        assertTrue(runCatching { proposal("read_project_file", JSONObject().put("name", "ok.txt").put("offset", 0.5)) }.isFailure)
        assertTrue(runCatching { proposal("list_project_files", JSONObject().put("project_id", "project.b")) }.isFailure)
    }
}
