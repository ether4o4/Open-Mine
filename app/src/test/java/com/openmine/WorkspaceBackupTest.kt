package com.openmine

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceBackupTest {
    private fun directory()=Files.createTempDirectory("om-backup").toFile()
    private fun write(root:File,path:String,text:String){File(root,path).apply{parentFile!!.mkdirs();writeText(text)}}
    private fun archive(root:File)=ByteArrayOutputStream().also{WorkspaceBackup(root).export(it)}.toByteArray()
    @Test fun modelWeightsAreOptionalAndTheirPrivateDirectoryRoundTrips() {
        val source=directory();val target=directory()
        try {
            val path="linux-sandbox/home/.morsvitaest/llm/models/model.gguf"
            write(source,path,"test weight bytes")
            assertTrue(WorkspaceBackup(target).stage(archive(source).inputStream()).items.isEmpty())
            val bytes=ByteArrayOutputStream().also{WorkspaceBackup(source).export(it,includeModels=true)}.toByteArray()
            val restore=WorkspaceBackup(target);val preview=restore.stage(bytes.inputStream());restore.commit(preview)
            assertEquals("test weight bytes",File(target,path).readText())
        }finally{source.deleteRecursively();target.deleteRecursively()}
    }
    @Test fun checksumMismatchPreservesTheLiveWorkspace() {
        val root=directory()
        try {
            val path="open_mine_projects/project.one/files/note.txt"
            write(root,path,"keep")
            val bytes=ByteArrayOutputStream();ZipOutputStream(bytes).use{zip->
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write(JSONObject().put("format","open-mine-workspace").put("version",1)
                    .put("items",JSONArray().put(JSONObject().put("path",path).put("bytes",4).put("sha256","0".repeat(64)))).toString().toByteArray());zip.closeEntry()
                zip.putNextEntry(ZipEntry(path));zip.write("evil".toByteArray());zip.closeEntry()}
            assertTrue(runCatching{WorkspaceBackup(root).stage(bytes.toByteArray().inputStream())}.isFailure)
            assertEquals("keep",File(root,path).readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun freshRestoreAndConflictsPreserveUnrelatedDataAndExcludeSecrets() {
        val source=directory();val target=directory()
        try {
            write(source,"open_mine_projects/project.one/files/note.txt","日本語 café")
            write(source,"assistant/sessions-v1.json",AssistantSessionStore.encode(AssistantSession()))
            write(source,"linux-sandbox/home/work/output.txt","shell work")
            write(source,"linux-sandbox/home/work/user-data.json","[1,2,3]")
            write(source,"knowledge_documents/document.test/source.json","[\"imported array\"]")
            write(source,"linux-sandbox/home/.ssh/id_ed25519","secret key")
            write(source,"linux-sandbox/home/.morsvitaest/llm/bin/llama-server","runtime")
            write(source,"no_backup/secret","must never export")
            val bytes=archive(source)
            val restore=WorkspaceBackup(target);var preview=restore.stage(bytes.inputStream());restore.commit(preview)
            assertEquals("日本語 café",File(target,"open_mine_projects/project.one/files/note.txt").readText())
            assertFalse(File(target,"no_backup/secret").exists())
            assertEquals("shell work",File(target,"linux-sandbox/home/work/output.txt").readText())
            assertEquals("[1,2,3]",File(target,"linux-sandbox/home/work/user-data.json").readText())
            assertEquals("[\"imported array\"]",File(target,"knowledge_documents/document.test/source.json").readText())
            assertFalse(File(target,"linux-sandbox/home/.ssh").exists())
            assertFalse(File(target,"linux-sandbox/home/.morsvitaest").exists())
            write(target,"open_mine_projects/project.one/files/keep.txt","untouched")
            write(target,"open_mine_projects/project.one/files/note.txt","newer")
            preview=restore.stage(bytes.inputStream())
            assertTrue(preview.conflicts.isNotEmpty())
            assertTrue(runCatching{restore.commit(preview)}.isFailure)
            assertEquals("newer",File(target,"open_mine_projects/project.one/files/note.txt").readText())
            restore.commit(preview,true)
            assertEquals("untouched",File(target,"open_mine_projects/project.one/files/keep.txt").readText())
        }finally{source.deleteRecursively();target.deleteRecursively()}
    }
    @Test fun truncationCancellationAndTraversalNeverReplaceExistingData() {
        val source=directory();val target=directory()
        try {
            write(source,"open_mine_projects/project.one/files/note.txt","original")
            write(target,"open_mine_projects/project.one/files/note.txt","keep")
            val bytes=archive(source);val restore=WorkspaceBackup(target)
            assertTrue(runCatching{restore.stage(bytes.copyOf(bytes.size-20).inputStream())}.isFailure)
            assertTrue(runCatching{restore.stage(bytes.inputStream(),check={throw java.io.InterruptedIOException("cancel")})}.isFailure)
            val malicious=ByteArrayOutputStream();ZipOutputStream(malicious).use{zip->
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write(JSONObject().put("format","open-mine-workspace").put("version",1)
                    .put("items",JSONArray().put(JSONObject().put("path","open_mine_projects/../../escaped").put("bytes",0).put("sha256","0".repeat(64)))).toString().toByteArray());zip.closeEntry()}
            assertTrue(runCatching{restore.stage(malicious.toByteArray().inputStream())}.isFailure)
            assertEquals("keep",File(target,"open_mine_projects/project.one/files/note.txt").readText())
        }finally{source.deleteRecursively();target.deleteRecursively()}
    }
    @Test fun startupRollsBackAnInterruptedMultiStoreCommit() {
        val root=directory()
        try {
            write(root,"open_mine_objects/restored.txt","new")
            write(root,".workspace-restore/rollback/open_mine_objects/old.txt","old")
            write(root,".workspace-restore/journal.json",JSONObject().put("phase","committing").put("stores",JSONArray()
                .put(JSONObject().put("path","open_mine_objects").put("hadOriginal",true))
                .put(JSONObject().put("path","assistant").put("hadOriginal",false))).toString())
            write(root,"assistant/partial","new")
            WorkspaceBackup(root).recover()
            assertEquals("old",File(root,"open_mine_objects/old.txt").readText())
            assertFalse(File(root,"open_mine_objects/restored.txt").exists())
            assertFalse(File(root,"assistant").exists())
        }finally{root.deleteRecursively()}
    }
}
