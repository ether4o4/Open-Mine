package com.openmine.sandbox

import android.content.Context
import android.net.Uri
import android.os.Build
import com.openmine.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Open Mine-owned glue around selected MVE runtime code; no MVE app dependency. */
class OpenMineRuntime private constructor(private val context:Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val base=File(context.filesDir,"linux-sandbox")
    private val home=File(base,"home").apply{mkdirs()}
    private val rootfs=File(base,"rootfs")
    private val tmp=File(base,"tmp").apply{mkdirs()}
    private val marker=File(base,"ready")
    private val engineMarker=File(base,"engine-ready")
    private val initialInstallation=RuntimeInstallation.inspect(base)
    private val downloader=RootfsDownloader()
    private val _status=MutableStateFlow(if(initialInstallation.shellInstalled)"Linux shell installed" else "Linux shell needs setup")
    val status:StateFlow<String> = _status
    val busy=MutableStateFlow(false)
    private val transcript=File(base,"terminal-transcript.txt")
    val output=MutableStateFlow(runCatching{transcript.readText().takeLast(20000)}.getOrDefault(""))
    val terminalOutput=MutableStateFlow(output.value)
    private val session by lazy { PersistentSandboxShell(executor(),tmp.absolutePath,runCatching{File(base,"terminal-cwd.txt").readText()}.getOrDefault("/root")) }
    val models=MutableStateFlow<List<String>>(emptyList())
    private var job:Job?=null
    private var handle:ProotHandle?=null
    private var modelServerHandle:ProotHandle?=null
    val shellHealthy=MutableStateFlow(false)
    val shellInstalled=MutableStateFlow(initialInstallation.shellInstalled)
    val engineInstalled=MutableStateFlow(initialInstallation.engineInstalled)
    val engineHealthy=MutableStateFlow(false)
    val modelHealthy=MutableStateFlow(false)
    val ready get()=shellHealthy.value
    private val shellFilesPresent get()=File(rootfs,"bin/bash").isFile && File(rootfs,"bin/busybox").isFile
    private val engineFile get()=File(home,".morsvitaest/llm/bin/llama-server")
    private val modelDir get()=File(home,".morsvitaest/llm/models").apply{mkdirs()}
    init{
        refreshModels()
        if(shellFilesPresent) verifyShell() else _status.value="Linux shell needs setup or repair"
    }
    private fun refreshInstallation(){
        val installation=RuntimeInstallation.inspect(base)
        shellInstalled.value=installation.shellInstalled
        engineInstalled.value=installation.engineInstalled
    }
    fun verifyShell()=run("Checking Linux shell"){
        engineHealthy.value=false
        modelHealthy.value=false
        probeShell()
        marker.writeText("ready")
        refreshInstallation()
        _status.value="Shell installed successfully · execution check passed"
        // Reconcile older installations without turning a remembered install into
        // a fake live status. Each executable and model status is checked afresh.
        if(engineFile.isFile){
            val result=runInterruptible(Dispatchers.IO){executor().execute("/root/.morsvitaest/llm/bin/llama-server --version",10)}
            if(result["success"]==true){
                engineHealthy.value=true
                engineMarker.writeText("execution verified")
                refreshEngineScript()
                val model=runInterruptible(Dispatchers.IO){executor().execute("bash /root/morsllm.sh status",10)}
                val json=model["stdout"]?.toString()?.lines()?.lastOrNull{it.trim().startsWith("{")}
                modelHealthy.value=model["success"]==true && runCatching{JSONObject(json.orEmpty()).optBoolean("ready")}.getOrDefault(false)
            }else{
                _status.value="Shell ready · engine execution check failed. Use Check / repair engine."
                output.value=TerminalResultFormatter.format(result)
            }
            refreshInstallation()
        }
    }
    private suspend fun probeShell(){
        shellHealthy.value=false
        val result=runInterruptible(Dispatchers.IO){executor().execute("bash --noprofile --norc -c 'printf OPENMINE_SHELL_OK'",10)}
        check(result["success"]==true && result["stdout"]=="OPENMINE_SHELL_OK") {
            "Linux shell execution failed: ${result["stderr"] ?: result["error"]}. Existing files were preserved; retry setup to repair prerequisites."
        }
        shellHealthy.value=true
    }
    private fun refreshEngineScript(){context.assets.open("sandbox/morsllm.sh").use{ShellScriptInstaller.install(File(home,"morsllm.sh"),it.readBytes())}}
    private fun executor()=ProotExecutor(File(context.applicationInfo.nativeLibraryDir,"libproot.so").absolutePath,base.absolutePath,rootfs.absolutePath,home.absolutePath,tmp.absolutePath)
    @Synchronized private fun run(label:String,action:suspend ()->Unit){
        if(busy.value)return
        busy.value=true;_status.value=label;if(label!="Running command") output.value=""
        job=scope.launch{try{action()}catch(e:CancellationException){_status.value="Cancelled"}catch(e:Exception){_status.value="Failed: ${e.message}"}finally{refreshInstallation();handle=null;busy.value=false}}
    }
    fun cancel(){job?.cancel();downloader.cancel();session.cancelForeground();handle?.cancel()}
    fun setup()=run("Setting up Linux shell"){
        check(BuildConfig.DEBUG){"Runtime downloads are disabled in release builds pending Play-compatible packaging."}
        check(File(context.applicationInfo.nativeLibraryDir,"libproot.so").canExecute()){"No executable PRoot binary for this device ABI."}
        val arch=when(Build.SUPPORTED_ABIS.first()){ "arm64-v8a"->"aarch64";"x86_64"->"x86_64";"armeabi-v7a"->"armhf";else->error("Unsupported device ABI")}
        base.mkdirs()
        val talloc=File(context.applicationInfo.nativeLibraryDir,"libtalloc.so")
        talloc.copyTo(File(base,"libtalloc.so.2"),overwrite=true)
        if (!File(base,"rootfs.valid").exists() && File(rootfs,"bin/busybox").isFile && File(rootfs,"etc/alpine-release").isFile) {
            // Older installs may have a missing marker. Adopt their rootfs; never
            // erase installed packages or shell files just because metadata is lost.
            File(base,"rootfs.valid").writeText("recovered existing installation")
        }
        if(!File(base,"rootfs.valid").exists()){
            check(!rootfs.exists() || rootfs.listFiles().isNullOrEmpty()) {
                "Existing Linux files need repair; automatic setup will not replace or delete them."
            }
            val archive=File(base,"rootfs.tar.gz")
            val staging=File(base,"rootfs-staging")
            staging.deleteRecursively()
            try{
                downloader.download(arch,archive){_status.value="Downloading Linux: ${(it*100).toInt()}%"}
                downloader.extractTarGz(archive,staging)
                check(File(staging,"bin/busybox").isFile){"Incomplete rootfs archive"}
                // Only an empty target may be removed to make the rename possible.
                if (rootfs.exists()) check(rootfs.delete()) { "Cannot replace empty installation directory" }
                check(staging.renameTo(rootfs)){"Cannot finish Linux installation"}
                File(base,"rootfs.valid").writeText("verified")
            }finally{archive.delete();staging.deleteRecursively()}
        }
        currentCoroutineContext().ensureActive()
        downloader.writeResolvConf(rootfs)
        downloader.writeRepositories(rootfs,downloader.mirrors.first())
        _status.value="Installing shell and engine prerequisites"
        val running=executor().executeStreaming("apk add --no-cache bash curl jq git libstdc++ libgcc",onStdout={line->output.value=(output.value+"\n"+line).takeLast(20000)},onStderr={line->output.value=(output.value+"\n"+line).takeLast(20000)})
        handle=running
        val exit=try { withTimeout(180000) { runInterruptible(Dispatchers.IO) { running.awaitExit() } } }
            finally { if (!currentCoroutineContext().isActive) running.cancel() }
        check(exit==0){"Linux prerequisites failed (exit $exit). See output and retry setup."}
        refreshEngineScript()
        probeShell()
        marker.writeText("ready")
        _status.value="Shell installed successfully · execution check passed"
    }
    fun command(command:String)=run("Running command"){
        check(ready){"Set up Linux shell first."}
        val history=(terminalOutput.value+"\n$ "+command+"\n").takeLast(20000)
        var live=""
        val result=try {
            session.run(command,30,onStdout={line->synchronized(this){live=(live+line+"\n").takeLast(15000);terminalOutput.value=(history+live).takeLast(20000)}},onStderr={line->synchronized(this){live=(live+"stderr: "+line+"\n").takeLast(15000);terminalOutput.value=(history+live).takeLast(20000)}})
        } catch (e: CancellationException) {
            terminalOutput.value=(history+live+"\nCommand cancelled; shell session reset.\n").takeLast(20000)
            transcript.writeText(terminalOutput.value)
            output.value=terminalOutput.value
            throw e
        }
        output.value=(history+TerminalResultFormatter.format(result)).takeLast(20000)
        terminalOutput.value=output.value
        transcript.writeText(output.value)
        if(result["shell_died"]!=true) File(base,"terminal-cwd.txt").writeText(result["cwd"].toString())
        _status.value=if(result["success"]==true)"Command finished" else "Command failed; inspect exit code and output"
    }
    fun systemInfo(checkCancelled:()->Unit = {}):String {
        check(ready){"Linux shell needs setup; no command ran."}
        val result=executor().execute("uname -a",10,checkCancelled=checkCancelled)
        check(result["success"]==true){"System information command failed: ${result["exit_code"]}"}
        return result["stdout"].toString()
    }
    fun engine(action:String,model:String="")=run("Engine: $action"){
        check(ready){"Set up Linux shell first."}
        check(action in setOf("provision","serve","stop","status")){"Unsupported engine action"}
        check(action!="provision" || BuildConfig.DEBUG){"Engine downloads are disabled in release builds."}
        if(action=="provision")engineHealthy.value=false
        refreshEngineScript()
        if(action=="provision" && Build.SUPPORTED_ABIS.first()=="arm64-v8a") {
            _status.value="Installing bundled ARM64 engine"
            val operation=currentCoroutineContext()
            BundledEngine.install(context.assets.open("sandbox/llama-server-aarch64-musl"),
                File(home,".morsvitaest/llm/bundled/llama-server")) { operation.ensureActive() }
        }
        if(action=="serve") {
            val weights=File(modelDir,model)
            require(weights.canonicalFile.parentFile==modelDir.canonicalFile && weights.isFile){"Select an imported model"}
            val info=com.openmine.GgufMetadata.inspect(weights)
            val memory=android.app.ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
            check(!memory.lowMemory && info.estimatedMemory<memory.availMem-memory.threshold){"Insufficient available memory. This model needs approximately ${info.estimatedMemory/(1024*1024)} MiB plus Android's reserve. Choose smaller weights or close other apps."}
        }
        val argument=if(action=="serve")" '${model.replace("'","'\\''")}'" else ""
        var tail=""
        val serving=CompletableDeferred<JSONObject>()
        if(action=="serve" || action=="stop")modelHealthy.value=false
        val running=executor().executeStreaming("bash /root/morsllm.sh $action$argument",onStdout={line->synchronized(this){
            tail=(tail+line+"\n").takeLast(20000);output.value=tail
            if(action=="serve")runCatching{JSONObject(line)}.getOrNull()?.let{if(it.optBoolean("ok") && it.optBoolean("ready"))serving.complete(it)}
        }},onStderr={line->synchronized(this){tail=(tail+"stderr: "+line+"\n").takeLast(20000);output.value=tail}})
        handle=running
        // PRoot may keep its tracer alive while the served model is a tracee.
        // Readiness is the script's real HTTP health result, not tracer exit.
        val exited=scope.async { runInterruptible(Dispatchers.IO){running.awaitExit(timeoutMinutes=if(action=="serve")0 else 30)} }
        var parsed:JSONObject?=null
        val exit=try {
            if(action=="serve")withTimeout(330000){select<Int>{
                serving.onAwait{parsed=it;modelServerHandle=running;0}
                exited.onAwait{it}
            }} else exited.await()
        } catch(e:CancellationException){
            running.cancel()
            exited.cancel()
            if(action=="serve")withContext(NonCancellable+Dispatchers.IO){
                executor().execute("bash /root/morsllm.sh stop",10)
                modelHealthy.value=false
            }
            throw e
        }
        if(parsed==null){val json=synchronized(this){tail.lines().lastOrNull{it.trim().startsWith("{")}.orEmpty()};parsed=runCatching{JSONObject(json)}.getOrNull()}
        if(action=="serve" && parsed?.optBoolean("ready")==true){
            modelHealthy.value=true
            scope.launch { exited.await();if(modelServerHandle===running){modelServerHandle=null;modelHealthy.value=false} }
        }
        if(exit!=0 || parsed?.optBoolean("ok")!=true){
            val logPath=parsed?.optString("log_path").orEmpty().ifBlank{parsed?.optString("log").orEmpty()}
            val prefix="/root/.morsvitaest/llm/"
            if(logPath.startsWith(prefix)){
                val log=File(home,logPath.removePrefix("/root/"))
                if(log.canonicalPath.startsWith(File(home,".morsvitaest/llm").canonicalPath+File.separator) && log.isFile){
                    output.value=(tail+"\nBUILD LOG:\n"+java.io.RandomAccessFile(log,"r").use{file->val size=minOf(file.length(),12000L).toInt();file.seek(file.length()-size);val bytes=ByteArray(size);file.readFully(bytes);String(bytes,Charsets.UTF_8)}).takeLast(32000)
                }
            }
            val explanation=listOf("detail","hint").mapNotNull { key -> parsed?.optString(key)?.takeIf { it.isNotBlank() } }.joinToString(" ")
            error("${parsed?.optString("error").orEmpty().ifBlank{"engine_exit_$exit"}}: $explanation See retained output.")
        }
        if(action=="stop"){modelServerHandle?.cancel();modelServerHandle=null;modelHealthy.value=false}
        if(action=="status"){
            modelHealthy.value=parsed?.optBoolean("ready")==true
            engineHealthy.value=parsed?.optBoolean("provisioned")==true
        }
        if(action=="provision"){
            engineHealthy.value=true
            engineMarker.writeText("execution verified")
        }
        _status.value=when(action){
            "serve"->"Model health check passed; AI Chat can use http://127.0.0.1:8080/v1"
            "status"->if(modelHealthy.value)"Model HTTP health check passed" else if(parsed?.optBoolean("running")==true)"Model process exists but is not healthy" else "Model is stopped"
            "stop"->"Model stopped"
            else->"Engine execution verified"
        }
    }
    fun importModel(uri:Uri)=run("Importing GGUF model"){
        val displayName=runCatching{
            context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->
                val column=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if(column>=0 && cursor.moveToFirst())cursor.getString(column)else null
            }
        }.getOrNull()?.takeIf{it.isNotBlank()} ?: uri.lastPathSegment
        val partial=File(modelDir,UUID.randomUUID().toString()+".part")
        try{
            context.contentResolver.openInputStream(uri)?.use{input->partial.outputStream().use{out->
                val header=ByteArray(4);var received=0
                while(received<4){val n=input.read(header,received,4-received);check(n>0){"Empty or truncated model"};received+=n}
                check(String(header,Charsets.US_ASCII)=="GGUF"){"Selected file is not GGUF model data"}
                out.write(header)
                val buffer=ByteArray(65536);var total=4L;var nextStorageCheck=4L
                while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;total+=count;check(total<=8L*1024*1024*1024){"Model exceeds 8 GiB limit"};if(total>=nextStorageCheck){check(base.usableSpace>2L*1024*1024+16L*1024*1024){"Not enough free storage"};nextStorageCheck=total+1024*1024};out.write(buffer,0,count);_status.value="Imported ${total/(1024*1024)} MiB"}
                out.fd.sync()
            }} ?: error("Cannot open model file")
            val info=com.openmine.GgufMetadata.inspect(partial)
            currentCoroutineContext().ensureActive()
            val imported=com.openmine.ModelImportName.publish(partial,modelDir,displayName)
            refreshModels();_status.value="Imported ${imported.name}: ${info.architecture}, ${info.tensors} tensors. Inference has not been tested."
        }finally{partial.delete()}
    }
    private fun refreshModels(){models.value=modelDir.listFiles()?.filter{it.extension=="gguf"}?.map{it.name}?.sorted().orEmpty()}
    fun deleteModel(name:String)=run("Deleting selected weights") {
        check(!modelHealthy.value){"Stop the model before deleting weights"}
        val file=File(modelDir,name)
        require(file.canonicalFile.parentFile==modelDir.canonicalFile && file.extension=="gguf"){"Invalid model path"}
        check(file.delete()){"Could not delete weights"};refreshModels();_status.value="Deleted $name; the original phone file is unchanged"
    }
    companion object{
        @Volatile private var instance:OpenMineRuntime?=null
        fun get(c:Context)=instance ?: synchronized(this){instance ?: OpenMineRuntime(c.applicationContext).also{instance=it}}
    }
}
