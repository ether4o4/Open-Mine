package com.openmine.sandbox

import android.content.Context
import android.net.Uri
import android.os.Build
import com.openmine.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val downloader=RootfsDownloader()
    private val _status=MutableStateFlow(if(marker.exists())"Linux shell installed" else "Linux shell needs setup")
    val status:StateFlow<String> = _status
    val busy=MutableStateFlow(false)
    val output=MutableStateFlow("")
    val models=MutableStateFlow<List<String>>(emptyList())
    private var job:Job?=null
    private var handle:ProotHandle?=null
    val ready get()=marker.exists()
    private val modelDir get()=File(home,".morsvitaest/llm/models").apply{mkdirs()}
    init{refreshModels()}
    private fun executor()=ProotExecutor(File(context.applicationInfo.nativeLibraryDir,"libproot.so").absolutePath,base.absolutePath,rootfs.absolutePath,home.absolutePath,tmp.absolutePath)
    private fun run(label:String,action:suspend ()->Unit){
        if(busy.value)return
        busy.value=true;_status.value=label;output.value=""
        job=scope.launch{try{action()}catch(e:CancellationException){_status.value="Cancelled"}catch(e:Exception){_status.value="Failed: ${e.message}"}finally{handle=null;busy.value=false}}
    }
    fun cancel(){handle?.cancel();job?.cancel()}
    fun setup()=run("Setting up Linux shell"){
        check(BuildConfig.DEBUG){"Runtime downloads are disabled in release builds pending Play-compatible packaging."}
        check(File(context.applicationInfo.nativeLibraryDir,"libproot.so").canExecute()){"No executable PRoot binary for this device ABI."}
        val arch=when(Build.SUPPORTED_ABIS.first()){ "arm64-v8a"->"aarch64";"x86_64"->"x86_64";"armeabi-v7a"->"armhf";else->error("Unsupported device ABI")}
        base.mkdirs()
        val talloc=File(context.applicationInfo.nativeLibraryDir,"libtalloc.so")
        talloc.copyTo(File(base,"libtalloc.so.2"),overwrite=true)
        if(!File(base,"rootfs.valid").exists()){
            val archive=File(base,"rootfs.tar.gz")
            val staging=File(base,"rootfs-staging")
            staging.deleteRecursively()
            try{
                downloader.download(arch,archive){_status.value="Downloading Linux: ${(it*100).toInt()}%"}
                downloader.extractTarGz(archive,staging)
                check(File(staging,"bin/busybox").isFile){"Incomplete rootfs archive"}
                rootfs.deleteRecursively()
                check(staging.renameTo(rootfs)){"Cannot finish Linux installation"}
                File(base,"rootfs.valid").writeText("verified")
            }finally{archive.delete();staging.deleteRecursively()}
        }
        currentCoroutineContext().ensureActive()
        downloader.writeResolvConf(rootfs)
        downloader.writeRepositories(rootfs,downloader.mirrors.first())
        _status.value="Installing shell and engine prerequisites"
        val result=executor().execute("apk add --no-cache bash curl jq git",180)
        output.value=result.toString();check(result["success"]==true){"Linux prerequisites failed. See output and retry setup."}
        val script=File(home,"morsllm.sh")
        context.assets.open("sandbox/morsllm.sh").use{input->script.outputStream().use{input.copyTo(it)}}
        marker.writeText("ready")
        _status.value="Linux shell ready"
    }
    fun command(command:String)=run("Running command"){
        check(ready){"Set up Linux shell first."}
        val result=executor().execute(command,30)
        output.value=result.toString()
        _status.value=if(result["success"]==true)"Command finished" else "Command failed; inspect exit code and output"
    }
    fun systemInfo():String {
        check(ready){"Linux shell needs setup; no command ran."}
        val result=executor().execute("uname -a",10)
        check(result["success"]==true){"System information command failed: ${result["exit_code"]}"}
        return result["stdout"].toString()
    }
    fun engine(action:String,model:String="")=run("Engine: $action"){
        check(ready){"Set up Linux shell first."}
        check(action in setOf("provision","serve","stop","status")){"Unsupported engine action"}
        check(action!="provision" || BuildConfig.DEBUG){"Engine downloads are disabled in release builds."}
        val argument=if(action=="serve")" '${model.replace("'","'\\''")}'" else ""
        var tail=""
        val running=executor().executeStreaming("bash /root/morsllm.sh $action$argument",onStdout={line->synchronized(this){tail=(tail+line+"\n").takeLast(20000);output.value=tail}},onStderr={line->_status.value=line.takeLast(240)})
        handle=running
        val exit=withContext(Dispatchers.IO){running.awaitExit()}
        val json=tail.lines().lastOrNull{it.trim().startsWith("{")}.orEmpty()
        val parsed=runCatching{JSONObject(json)}.getOrNull()
        check(exit==0 && parsed?.optBoolean("ok")==true){"Engine action failed. Inspect output/logs; no success was assumed."}
        _status.value=if(action=="serve")"Model health check passed; AI Chat can use http://127.0.0.1:8080/v1" else "Engine $action completed"
    }
    fun importModel(uri:Uri)=run("Importing GGUF model"){
        val partial=File(modelDir,UUID.randomUUID().toString()+".part")
        try{
            context.contentResolver.openInputStream(uri)?.use{input->partial.outputStream().use{out->
                val header=ByteArray(4);var received=0
                while(received<4){val n=input.read(header,received,4-received);check(n>0){"Empty or truncated model"};received+=n}
                check(String(header,Charsets.US_ASCII)=="GGUF"){"Selected file is not GGUF model data"}
                out.write(header)
                val buffer=ByteArray(65536);var total=4L
                while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;total+=count;check(total<=8L*1024*1024*1024){"Model exceeds 8 GiB limit"};check(base.usableSpace>count+16L*1024*1024){"Not enough free storage"};out.write(buffer,0,count);_status.value="Imported ${total/(1024*1024)} MiB"}
            }} ?: error("Cannot open model file")
            check(partial.length()>32){"Truncated GGUF file"}
            val final=File(modelDir,partial.nameWithoutExtension+".gguf")
            check(partial.renameTo(final)){"Could not finish model import"}
            refreshModels();_status.value="GGUF imported. Start it to verify model architecture and memory requirements."
        }finally{partial.delete()}
    }
    private fun refreshModels(){models.value=modelDir.listFiles()?.filter{it.extension=="gguf"}?.map{it.name}?.sorted().orEmpty()}
    companion object{
        @Volatile private var instance:OpenMineRuntime?=null
        fun get(c:Context)=instance ?: synchronized(this){instance ?: OpenMineRuntime(c.applicationContext).also{instance=it}}
    }
}
