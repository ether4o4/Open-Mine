package com.openmine

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Explicit project-file ingestion. Originals and extracted text are separate from
 * conversational history, memory and phone storage. No directory scanning. */
class DocumentKnowledge(private val context:Context) {
    private val root=File(context.filesDir,"knowledge_documents")
    fun ingest(projectId:String,name:String,input:InputStream,check:()->Unit={}):String {
        require(Regex("[a-z0-9][a-z0-9._-]{2,100}").matches(projectId)){"Choose a project"}
        ProjectStore.validateName(name)
        val extension=name.substringAfterLast('.',"").lowercase()
        require(extension in setOf("txt","md","csv","json","pdf","docx")){"Unsupported ingestion format. Supported: UTF-8 TXT, Markdown, CSV, JSON, text PDF and DOCX. File storage remains available."}
        root.mkdirs();val staging=File(root,".import-${UUID.randomUUID()}").apply{mkdirs()}
        try {
            val source=File(staging,"source.$extension");var bytes=0L;val sha=MessageDigest.getInstance("SHA-256")
            input.use{stream->source.outputStream().use{out->val buffer=ByteArray(65536);while(true){check();val n=stream.read(buffer);if(n<0)break;bytes+=n;require(bytes<=64L*1024*1024 && root.usableSpace>n+16L*1024*1024){"Document exceeds 64 MiB or available storage"};out.write(buffer,0,n);sha.update(buffer,0,n)};out.fd.sync()}}
            val hash=sha.digest().joinToString(""){"%02x".format(it)}
            val id="document."+MessageDigest.getInstance("SHA-256").digest("$projectId:$hash".toByteArray()).joinToString(""){"%02x".format(it)}.take(32)
            require(!File(root,id).exists()){"This document is already ingested in this project"}
            val text=when(extension) {
                "pdf" -> {
                    PDFBoxResourceLoader.init(context)
                    PDDocument.load(source,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(16L*1024*1024)).use{pdf->require(!pdf.isEncrypted){"Encrypted PDFs must be unlocked before ingestion"};require(pdf.numberOfPages<=1000){"PDF exceeds 1000 pages"}
                        val result=StringBuilder();val stripper=PDFTextStripper()
                        for(page in 1..pdf.numberOfPages){check();stripper.startPage=page;stripper.endPage=page;result.append(stripper.getText(pdf));require(result.length<=MAX_TEXT){"Extracted PDF text exceeds 4 MiB"}}
                        result.toString()}
                }
                "docx" -> java.util.zip.ZipFile(source).use{zip->
                    val entry=zip.getEntry("word/document.xml") ?: error("DOCX document body is missing")
                    val xml=zip.getInputStream(entry).use{ProjectStore.readBounded(it,8L*1024*1024)}
                    val parser=android.util.Xml.newPullParser();parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES,true)
                    parser.setInput(xml.inputStream(),"UTF-8");val result=StringBuilder()
                    while(parser.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){check();
                        if(parser.eventType==org.xmlpull.v1.XmlPullParser.DOCDECL)error("DOCX declarations are unsupported")
                        if(parser.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && parser.name=="t")result.append(parser.nextText())
                        if(parser.eventType==org.xmlpull.v1.XmlPullParser.END_TAG && parser.name=="p")result.append('\n')
                        require(result.length<=MAX_TEXT){"Extracted DOCX text exceeds 4 MiB"};parser.next()}
                    result.toString()}
                else -> {require(source.length()<=MAX_TEXT){"Text ingestion exceeds 4 MiB"};Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source.readBytes())).toString()}
            }
            require(text.isNotBlank()){"No searchable text was extracted. Scanned PDFs require OCR, which is not supported; original file preserved."}
            require('\u0000' !in text){"Binary data cannot be indexed as text"}
            check()
            File(staging,"text.txt").writeText(text)
            File(staging,"metadata.json").writeText(JSONObject().put("schema",1).put("id",id).put("projectId",projectId).put("name",name).put("sha256",hash).put("bytes",bytes).put("source",source.name).put("enabled",true).toString())
            check(staging.renameTo(File(root,id))){"Cannot publish extracted document"}
            return "$name extracted and indexed for $projectId (${text.length} characters)."
        }catch(error:OutOfMemoryError){throw IllegalStateException("Not enough memory to extract this document. Try a smaller document; original preserved.")}
        finally{staging.deleteRecursively()}
    }
    fun sources(scope:RetrievalScope,query:String):List<StrictObject> {
        if(scope.projectId==null)return emptyList()
        val terms=query.lowercase().split(Regex("\\W+")).filter{it.length>2}
        return root.listFiles().orEmpty().filter{it.isDirectory && it.name.startsWith("document.")}.mapNotNull{directory->
            val meta=JSONObject(File(directory,"metadata.json").readText())
            if(meta.getString("projectId")!=scope.projectId || !meta.optBoolean("enabled",false) || directory.name in scope.excludedIds)return@mapNotNull null
            val textFile=File(directory,"text.txt");require(textFile.length()<=MAX_TEXT*4L){"Extracted source exceeds limit"}
            val text=textFile.readText();val chunks=text.chunked(2000)
            val matching=chunks.sortedByDescending{chunk->terms.count{chunk.contains(it,true)}}.take(3).joinToString("\n")
            val id=meta.getString("id")
            StrictObject(mapOf("OBJECT_ID" to id,"OBJECT_TITLE" to meta.getString("name"),"OBJECT_TYPE" to "KNOWLEDGE","OBJECT_STATUS" to "VERIFIED"),emptyMap(),"SOURCE $id (${meta.getString("name")}):\n$matching")
        }.take(5)
    }
    fun forget(projectId:String,name:String) {
        root.listFiles().orEmpty().filter{it.isDirectory && it.name.startsWith("document.")}.forEach{directory->
            val meta=JSONObject(File(directory,"metadata.json").readText())
            if(meta.getString("projectId")==projectId && meta.getString("name")==name)check(directory.deleteRecursively()){"Could not forget extracted document"}
        }
    }
    companion object {private const val MAX_TEXT=4*1024*1024}
}
