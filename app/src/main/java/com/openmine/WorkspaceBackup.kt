package com.openmine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable authoritative data only. Runtime executables, indexes, logs and credentials
 * are excluded. Staged validation precedes any change to the live workspace. */
class WorkspaceBackup(private val root:File) {
    data class Item(val path:String,val bytes:Long,val sha256:String)
    data class Preview(val items:List<Item>,val conflicts:List<String>,val totalBytes:Long)
    private val transaction=File(root,".workspace-restore")
    private val staged=File(transaction,"staged")
    private val rollback=File(transaction,"rollback")
    private val journal=File(transaction,"journal.json")
    private val shellHome="linux-sandbox/home"
    private val standardRoots=setOf("open_mine_objects","open_mine_projects","assistant","ai_memory","knowledge_documents","library-sessions","workspace_preferences",shellHome)
    private val modelRoot="linux-sandbox/home/.morsvitaest/llm/models"
    private fun portable(path:String,includeModels:Boolean):Boolean {
        if(!path.startsWith("$shellHome/"))return true
        val relative=path.removePrefix("$shellHome/")
        if(path.startsWith("$modelRoot/"))return includeModels
        return relative.substringBefore('/') !in setOf(".morsvitaest","morsllm.sh",".ssh",".gnupg",".netrc",".git-credentials",".bash_history",".ash_history")
    }
    private fun owner(path:String):String = (standardRoots+modelRoot).firstOrNull { path.startsWith("$it/") }
        ?: error("Backup contains a forbidden store: $path")
    private fun safe(base:File,path:String):File {
        require(path.length<=512 && !path.startsWith('/') && '\\' !in path && ':' !in path && path.none{it.code<32}){"Unsafe backup path"}
        require(path.split('/').none { it.isEmpty() || it=="." || it==".." }){"Unsafe backup path"}
        owner(path)
        val file=File(base,path)
        require(file.canonicalPath.startsWith(base.canonicalPath+File.separator)){"Path escapes workspace"}
        var current:File?=file
        while(current!=null && current!=base){require(!Files.isSymbolicLink(current.toPath())){"Symlinks cannot enter backups"};current=current.parentFile}
        return file
    }
    private fun digest(file:File,check:()->Unit={}):String {
        val sha=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(65536);while(true){check();val n=input.read(buffer);if(n<0)break;sha.update(buffer,0,n)}}
        return sha.digest().joinToString(""){"%02x".format(it)}
    }
    fun export(output:OutputStream,includeModels:Boolean=false,check:()->Unit={},progress:(Long)->Unit={}) {
        require(!journal.exists()){"Finish or recover the pending restore first"}
        val files=standardRoots.flatMap { store ->
            val directory=File(root,store)
            if(!directory.exists())emptyList()else directory.walkTopDown().filter{it.isFile && portable(it.relativeTo(root).invariantSeparatorsPath,includeModels)}.map{safe(root,it.relativeTo(root).invariantSeparatorsPath)}.toList()
        }.sortedBy{it.path}
        require(files.size<=10000){"Workspace has too many files for this backup version"}
        val items=files.map{check();Item(it.relativeTo(root).invariantSeparatorsPath,it.length(),digest(it,check))}
        val total=items.sumOf{it.bytes};require(total<=MAX_TOTAL){"Backup exceeds 16 GiB"}
        val manifest=JSONObject().put("format","open-mine-workspace").put("version",1).put("modelsIncluded",includeModels)
            .put("excluded",JSONArray(listOf("app credentials","shell .ssh/.gnupg/.netrc/.git-credentials","shell history","managed runtime executables","rebuildable indexes","runtime logs","phone storage")))
            .put("items",JSONArray(items.map{JSONObject().put("path",it.path).put("bytes",it.bytes).put("sha256",it.sha256)}))
        var copied=0L
        ZipOutputStream(output).use{zip->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray());zip.closeEntry()
            items.forEach{item->
                check();zip.putNextEntry(ZipEntry(item.path));val sha=MessageDigest.getInstance("SHA-256");var count=0L
                safe(root,item.path).inputStream().use{input->val buffer=ByteArray(65536);while(true){check();val n=input.read(buffer);if(n<0)break;count+=n;require(count<=item.bytes){"Source changed during backup; retry"};sha.update(buffer,0,n);zip.write(buffer,0,n);copied+=n;progress(copied)}}
                require(count==item.bytes && sha.digest().joinToString(""){"%02x".format(it)}==item.sha256){"Source changed during backup; retry"};zip.closeEntry()
            }
        }
    }
    fun stage(input:InputStream,check:()->Unit={},progress:(Long)->Unit={}):Preview {
        require(!journal.exists()){"Pending restore must be recovered first"}
        transaction.deleteRecursively();check(staged.mkdirs()){"Cannot stage restore"}
        try {
            val archive=File(transaction,"archive.zip")
            input.use{source->archive.outputStream().use{out->val buffer=ByteArray(65536);var bytes=0L
                while(true){check();val n=source.read(buffer);if(n<0)break;bytes+=n;require(bytes<=MAX_TOTAL && root.usableSpace>n+64L*1024*1024){"Backup exceeds storage limit"};out.write(buffer,0,n);progress(bytes)};out.fd.sync()}}
            // ZipInputStream alone accepts a missing central directory. ZipFile rejects
            // this common truncated-backup case before any authoritative data changes.
            java.util.zip.ZipFile(archive).use{require(it.size() in 1..10001){"Invalid archive directory"}}
            return ZipInputStream(archive.inputStream()).use{zip->
                require(zip.nextEntry?.name=="manifest.json"){"Missing workspace manifest"}
                val manifest=JSONObject(String(ProjectStore.readBounded(zip,2L*1024*1024),Charsets.UTF_8))
                require(manifest.getString("format")=="open-mine-workspace" && manifest.getInt("version")==1){"Unsupported backup version"}
                val rows=manifest.getJSONArray("items");require(rows.length()<=10000){"Too many backup entries"}
                val items=(0 until rows.length()).map{val r=rows.getJSONObject(it);Item(r.getString("path"),r.getLong("bytes"),r.getString("sha256"))}
                require(items.map{it.path}.toSet().size==items.size){"Duplicate backup paths"}
                var total=0L
                items.forEach{safe(staged,it.path);require(portable(it.path,manifest.optBoolean("modelsIncluded",false))){"Backup contains excluded credentials or runtime files"};require(it.bytes in 0..MAX_TOTAL && Regex("[a-f0-9]{64}").matches(it.sha256)){"Invalid manifest entry"};require(total<=MAX_TOTAL-it.bytes){"Backup exceeds 16 GiB"};total+=it.bytes}
                require(total+64L*1024*1024<root.usableSpace){"Insufficient storage to stage backup; current data preserved"}
                val expected=items.associateBy{it.path};val seen=mutableSetOf<String>();var copied=0L
                while(true){check();val entry=zip.nextEntry ?: break
                    val item=expected[entry.name] ?: error("Unlisted archive entry")
                    require(!entry.isDirectory && seen.add(entry.name)){"Duplicate or directory archive entry"}
                    val target=safe(staged,entry.name);target.parentFile!!.mkdirs();val sha=MessageDigest.getInstance("SHA-256");var count=0L
                    target.outputStream().use{out->val buffer=ByteArray(65536);while(true){check();val n=zip.read(buffer);if(n<0)break;count+=n;require(count<=item.bytes){"Archive size exceeds manifest"};out.write(buffer,0,n);sha.update(buffer,0,n);copied+=n;progress(copied)};out.fd.sync()}
                    require(count==item.bytes && sha.digest().joinToString(""){"%02x".format(it)}==item.sha256){"Checksum mismatch or truncated backup: ${item.path}"}
                    validateData(target,item.path)
                }
                require(seen==expected.keys){"Truncated backup: entries missing"}
                Preview(items,items.filter{safe(root,it.path).exists()}.map{it.path},total)
            }
        } catch(e:Exception){transaction.deleteRecursively();throw e}
    }
    private fun validateData(file:File,path:String) {
        when {
            path.startsWith("open_mine_objects/") && path.endsWith(".omd") -> {
                require(file.length()<=OpenMineObjectFormat.MAX_RECORD_BYTES){"OMD exceeds restore limit"}
                require(OpenMineObjectFormat.validate(file.readText()).valid){"Invalid OMD in backup"}
            }
            path=="assistant/sessions-v1.json" -> {require(file.length()<=8L*1024*1024){"Conversation metadata exceeds restore limit"};AssistantSessionStore.decode(file.readText())}
            path.endsWith(".json") && (
                path.startsWith("ai_memory/") || path.startsWith("library-sessions/") || path.startsWith("workspace_preferences/") ||
                (path.startsWith("open_mine_projects/") && !path.contains("/files/")) ||
                (path.startsWith("knowledge_documents/") && path.endsWith("/metadata.json"))
            ) -> {require(file.length()<=16L*1024*1024){"Metadata exceeds restore limit"};JSONObject(file.readText())}
        }
    }
    /** Replace named stores only after explicit preview approval; rollback survives process death.
     * A fail-on-conflict restore never changes existing data. */
    fun commit(preview:Preview,replaceConflicts:Boolean=false) {
        require(!journal.exists()){"Restore already pending"}
        if(!replaceConflicts)require(preview.conflicts.isEmpty()){"Conflicts require explicit replacement review"}
        // Verify staged bytes again: a stale preview cannot authorize different data.
        preview.items.forEach{val f=safe(staged,it.path);require(f.length()==it.bytes && digest(f)==it.sha256){"Staged data changed"}}
        val stores=preview.items.map{owner(it.path)}.distinct()
        // Keep files absent from the backup. "Replace conflicts" replaces only the
        // reviewed matching paths; it must never erase the rest of an existing store.
        for(store in stores) {
            val existing=File(root,store)
            if(existing.exists())existing.walkTopDown().filter{it.isFile}.forEach{file->
                val path=file.relativeTo(root).invariantSeparatorsPath
                val from=safe(root,path);val to=safe(staged,path)
                if(!to.exists()){require(root.usableSpace>from.length()+16L*1024*1024){"Insufficient merge storage"};to.parentFile!!.mkdirs();from.copyTo(to)}
            }
        }
        val state=JSONObject().put("phase","committing").put("stores",JSONArray(stores.map{JSONObject().put("path",it).put("hadOriginal",File(root,it).exists())}))
        transaction.mkdirs();rollback.mkdirs();writeJournal(state)
        try {
            for(store in stores){
                val current=File(root,store);val old=File(rollback,store);val next=File(staged,store)
                old.parentFile!!.mkdirs();current.parentFile!!.mkdirs()
                if(current.exists())move(current,old)
                move(next,current)
            }
            File(root,"open_mine_index.json").delete()
            File(root,"knowledge_document_index").deleteRecursively()
            state.put("phase","committed");writeJournal(state)
            transaction.deleteRecursively()
        }catch(e:Exception){recover();throw e}
    }
    fun discard(){require(!journal.exists());transaction.deleteRecursively()}
    fun recover() {
        if(!journal.exists())return
        val state=JSONObject(journal.readText())
        if(state.getString("phase")!="committed") {
            val stores=state.getJSONArray("stores")
            for(i in stores.length()-1 downTo 0){val row=stores.getJSONObject(i);val store=row.getString("path")
                require(store in standardRoots || store==modelRoot){"Invalid restore journal"}
                val current=File(root,store);val old=File(rollback,store)
                if(old.exists()){current.deleteRecursively();current.parentFile!!.mkdirs();move(old,current)}
                else if(!row.getBoolean("hadOriginal"))current.deleteRecursively()
            }
        }
        transaction.deleteRecursively()
    }
    private fun writeJournal(value:JSONObject){val temp=File(transaction,"journal.pending");temp.outputStream().use{it.write(value.toString().toByteArray());it.fd.sync()};Files.move(temp.toPath(),journal.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
    private fun move(from:File,to:File){Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE)}
    companion object {private const val MAX_TOTAL=16L*1024*1024*1024}
}
