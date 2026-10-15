package com.openmine

import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

class CapabilityRuntimeTest {
    @Test fun credentialsPersistWithKeystoreAndAreExcludedFromWorkspaceBackup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint="https://example.invalid/${UUID.randomUUID()}/v1"
        val secret="instrumentation-secret-${UUID.randomUUID()}"
        val store=ProviderCredentials(context)
        try {
            store.save(endpoint,secret)
            assertEquals(secret,ProviderCredentials(context).load(endpoint))
            assertEquals("",store.load("https://other.invalid/v1"))
            val encrypted=File(context.noBackupFilesDir,"provider-credentials").walkTopDown().filter{it.isFile}.toList()
            assertTrue(encrypted.isNotEmpty())
            assertFalse(encrypted.any{it.readText().contains(secret)})
            val bytes=ByteArrayOutputStream();WorkspaceBackup(context.filesDir).export(bytes)
            java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use{zip->while(true){val entry=zip.nextEntry ?: break;assertFalse(entry.name.contains("credential"));assertFalse(String(ProjectStore.readBounded(zip,16*1024*1024)).contains(secret))}}
            store.forget(endpoint)
            assertEquals("",ProviderCredentials(context).load(endpoint))
        }finally{store.forget(endpoint)}
    }
    @Test fun realPdfTextExtractionIsProjectScopedAndForgetRemovesDerivedText() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(target.cacheDir,"document-test-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(target){override fun getFilesDir()=directory}
        try {
            PDFBoxResourceLoader.init(target)
            val file=File(directory,"input.pdf")
            PDDocument().use{pdf->val page=PDPage();pdf.addPage(page);PDPageContentStream(pdf,page).use{out->
                out.beginText();out.setFont(PDType1Font.HELVETICA,12f);out.newLineAtOffset(20f,700f);out.showText("Project alpha searchable evidence");out.endText()
            };pdf.save(file)}
            val documents=DocumentKnowledge(context)
            documents.ingest("project.alpha","evidence.pdf",file.inputStream())
            val sources=documents.sources(RetrievalScope("chat.alpha","project.alpha"),"searchable")
            assertEquals(1,sources.size);assertTrue(sources.single().raw.contains("searchable evidence"))
            assertTrue(documents.sources(RetrievalScope("chat.beta","project.beta"),"searchable").isEmpty())
            assertTrue(runCatching{documents.ingest("project.alpha","evidence.pdf",file.inputStream())}.isFailure)
            assertTrue(runCatching{documents.ingest("project.alpha","bad.pdf","broken".byteInputStream())}.isFailure)
            assertEquals(1,documents.sources(RetrievalScope("chat.alpha","project.alpha"),"searchable").size)
            documents.forget("project.alpha","evidence.pdf")
            assertTrue(documents.sources(RetrievalScope("chat.alpha","project.alpha"),"searchable").isEmpty())
            assertTrue("Original file must survive forgetting",file.exists())
        }finally{directory.deleteRecursively()}
    }
}
