package com.openmine

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ModelImportNameTest {
    @Test fun concurrentImportsWithTheReportedNameKeepEveryCompletedFile(){
        val directory=Files.createTempDirectory("openmine-model-concurrent").toFile()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(4)
        try{
            val name="Kiwi App Qwen2.5-Coder-3B-Instruct-abliterated-Q4_K_M.gguf"
            val original=directory.resolve(name).apply{writeText("original")}
            val tasks=(0 until 12).map{index->pool.submit<java.io.File>{
                val partial=directory.resolve("incoming-$index.part").apply{writeText("weights-$index")}
                ModelImportName.publish(partial,directory,name)
            }}
            val published=tasks.map{it.get(5,java.util.concurrent.TimeUnit.SECONDS)}
            assertEquals(12,published.map{it.name}.toSet().size)
            assertEquals((0 until 12).map{"weights-$it"}.toSet(),published.map{it.readText()}.toSet())
            assertEquals("original",original.readText())
            assertEquals(13,directory.listFiles()!!.size)
        }finally{pool.shutdownNow();directory.deleteRecursively()}
    }
    @Test fun existingDestinationSymlinkIsNotReplacedOrFollowed(){
        val directory=Files.createTempDirectory("openmine-model-destination").toFile()
        try{
            val outside=directory.resolve("original-data").apply{writeText("untouched")}
            val destination=directory.resolve("model.gguf")
            Files.createSymbolicLink(destination.toPath(),outside.toPath())
            val partial=directory.resolve("incoming.part").apply{writeText("new model")}
            val published=ModelImportName.publish(partial,directory,"model.gguf")
            assertNotEquals(destination.name,published.name)
            assertTrue(Files.isSymbolicLink(destination.toPath()))
            assertEquals("untouched",outside.readText())
            assertEquals("new model",published.readText())
            assertFalse(partial.exists())
        }finally{directory.deleteRecursively()}
    }
    @Test fun keepsRecognizableNamesWhileRejectingProviderPathAndControlCharacters(){
        assertEquals("Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",ModelImportName.filename("Qwen2.5-0.5B-Instruct-Q4_K_M.GGUF"))
        assertEquals("model.gguf",ModelImportName.filename("../.."))
        assertEquals("my-model.gguf",ModelImportName.filename("../../my-model.gguf"))
        assertEquals("my-model.gguf",ModelImportName.filename("C:\\downloads\\my-model.gguf"))
        assertEquals("model.gguf",ModelImportName.filename(null))
        val sanitized=ModelImportName.filename("a\n;\u0000\u202E'\".gguf")
        assertFalse(sanitized.any{it.code<32 || it=='/' || it=='\\' || it=='\u202E' || it=='\'' || it=='\"' || it==';'})
        val unicode=ModelImportName.filename("模型".repeat(200)+".gguf")
        assertTrue(unicode.toByteArray(Charsets.UTF_8).size<=155)
        assertEquals(unicode,String(unicode.toByteArray(Charsets.UTF_8),Charsets.UTF_8))
    }
    @Test fun duplicateImportsGetUniqueNamesAndNeverModifyExistingOrLegacyWeights(){
        val directory=Files.createTempDirectory("openmine-model-names").toFile()
        try{
            val existing=directory.resolve("qwen.gguf").apply{writeText("original weights")}
            val legacy=directory.resolve("550e8400-e29b-41d4-a716-446655440000.gguf").apply{writeText("legacy weights")}
            val partial=directory.resolve("incoming.part").apply{writeText("new verified bytes")}
            val imported=ModelImportName.publish(partial,directory,"qwen.gguf")
            assertTrue(imported.name.matches(Regex("qwen-[0-9a-f-]{36}\\.gguf")))
            assertEquals("new verified bytes",imported.readText())
            assertEquals("original weights",existing.readText())
            assertEquals("legacy weights",legacy.readText())
            assertFalse(partial.exists())
            val unique=directory.resolve("next.part").apply{writeText("other model")}
            assertEquals("recognizable.gguf",ModelImportName.publish(unique,directory,"recognizable.gguf").name)
        }finally{directory.deleteRecursively()}
    }
    @Test fun publicationCannotMoveAnOutsideFileOrFollowASymlink(){
        val directory=Files.createTempDirectory("openmine-model-safe").toFile()
        val outside=Files.createTempFile("outside-model",".part").toFile().apply{writeText("untouched")}
        try{
            assertTrue(runCatching{ModelImportName.publish(outside,directory,"outside.gguf")}.isFailure)
            val symlink=directory.resolve("link.part")
            Files.createSymbolicLink(symlink.toPath(),outside.toPath())
            assertTrue(runCatching{ModelImportName.publish(symlink,directory,"outside.gguf")}.isFailure)
            assertEquals("untouched",outside.readText())
        }finally{directory.deleteRecursively();outside.delete()}
    }
    @Test fun onlyNewImportsChangeAValidSelectionAndRotationKeepsTheUserChoice(){
        val old=listOf("legacy.gguf","second.gguf")
        assertEquals("legacy.gguf",ModelImportSelection.afterRefresh(old,old,"legacy.gguf"))
        val imported=listOf("legacy.gguf","new-model.gguf","second.gguf")
        assertEquals("new-model.gguf",ModelImportSelection.afterRefresh(old,imported,"legacy.gguf"))
        assertEquals("second.gguf",ModelImportSelection.afterRefresh(imported,imported,"second.gguf"))
        assertEquals("second.gguf",ModelImportSelection.afterRefresh(emptyList(),old,""))
        assertEquals("",ModelImportSelection.afterRefresh(old,emptyList(),"second.gguf"))
    }
}
