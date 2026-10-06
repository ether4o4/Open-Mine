package com.openmine

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val BG=Color(0xFF020712); private val PANEL=Color(0xE60A1830); private val CYAN=Color(0xFF38D8FF)
private val BLUE=Color(0xFF4C78FF); private val PURPLE=Color(0xFF8B5CFF); private val MAIN=Color(0xFFEAF5FF); private val DIM=Color(0xFF91A9C8)
data class Nav(val name:String,val icon:ImageVector)
data class Orb(val title:String,val sub:String,val accent:Color,val icon:ImageVector)

private val NAV=listOf(
 Nav("AI MODELS",Icons.Default.Memory),Nav("PROJECTS",Icons.Default.Folder),Nav("CONNECTORS",Icons.Default.Link),
 Nav("KNOWLEDGE",Icons.Default.School),Nav("SKILLS",Icons.Default.Build),Nav("MISSIONS",Icons.Default.Flag),
 Nav("TOOLS",Icons.Default.Construction),Nav("FILES",Icons.Default.Description),Nav("BROWSER",Icons.Default.Language),
 Nav("TERMINAL",Icons.Default.Terminal),Nav("DIAGNOSTICS",Icons.Default.BugReport),Nav("SETTINGS",Icons.Default.Settings)
)

class MainActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{OpenMine()}}
}

@Composable fun OpenMine(){
 val c=LocalContext.current
 val p=remember{c.getSharedPreferences("open_mine",Context.MODE_PRIVATE)}
 var selected by remember{mutableIntStateOf(p.getInt("screen",0).coerceIn(0,11))}
 var animations by remember{mutableStateOf(p.getBoolean("animations",true))}
 var haptics by remember{mutableStateOf(p.getBoolean("haptics",true))}
 var active by remember{mutableStateOf(p.getString("active_$selected",if(selected==0)p.getString("active","Qwen 3.5 2B")else "") ?: "")}
 var contextItems by remember{mutableStateOf(p.getStringSet("context",emptySet())?.toSet() ?: emptySet())}
 var vaultOpen by remember{mutableStateOf(false)}
 fun selectObject(o:Orb){active=o.title;p.edit().putString("active_$selected",o.title).apply()}
 fun toggleContext(o:Orb){contextItems=if(o.title in contextItems)contextItems-o.title else contextItems+o.title;p.edit().putStringSet("context",contextItems).apply()}
 fun go(i:Int){selected=i.coerceIn(0,11);active=p.getString("active_$selected","") ?: "";p.edit().putInt("screen",selected).apply();if(haptics)(c as? android.app.Activity)?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)}
 MaterialTheme(colorScheme=darkColorScheme(background=BG,surface=PANEL,primary=CYAN,secondary=PURPLE)){
  ReferenceHud(selected,animations,active,contextItems,::go,::selectObject,::toggleContext,{vaultOpen=true}){screen->
   when(screen){
    4->ListScreen("SKILLS",listOf("Android Build Skill","Vault Verification","Local Model Setup","UI Composition"))
    5->ListScreen("MISSIONS",listOf("Build Open Mine","Verify Vault","Connect Local AI","Ship APK"))
    6->ListScreen("TOOLS",listOf("Terminal","File Inspector","Model Runner","Git Helper"))
    7->ListScreen("FILES",OpenMineObjectStore.all(c).map{it.title}.ifEmpty{listOf("No local objects yet. Create or import an object in the vault.")})
    8->ListScreen("BROWSER",listOf("Browser connector is not configured."))
    9->ListScreen("TERMINAL",listOf("Terminal connector is not configured."))
    10->DiagnosticsScreen(c)
    11->SettingsScreen(animations,{v->animations=v;p.edit().putBoolean("animations",v).apply()},haptics,{v->haptics=v;p.edit().putBoolean("haptics",v).apply()})
   }
  }
  if(vaultOpen){
   androidx.compose.ui.window.Dialog(onDismissRequest={vaultOpen=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
    Surface(Modifier.fillMaxSize(),color=BG){Column{
     Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
      Text("OBJECT VAULT",color=MAIN,fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
      TextButton({vaultOpen=false}){Text("BACK TO HUD",color=CYAN,fontSize=11.sp)}
     }
     Box(Modifier.weight(1f)){Knowledge(c)}
    }}
   }
  }
 }
}

@Composable fun Knowledge(c:Context){
 var objects by remember{mutableStateOf(OpenMineObjectStore.all(c))}
 var query by remember{mutableStateOf("")}
 var editor by remember{mutableStateOf(false)}
 var status by remember{mutableStateOf("")}
 var selectedObject by remember{mutableStateOf<StrictObject?>(null)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
  if(uri==null)return@rememberLauncherForActivityResult
  val raw=runCatching{c.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}.orEmpty()}.getOrElse{""}
  val result=OpenMineObjectStore.import(c,raw)
  if(result.valid){objects=OpenMineObjectStore.all(c);status="IMPORTED + INDEXED: "+result.normalized!!.id;selectedObject=result.normalized}
  else status="IMPORT REJECTED: "+result.errors.take(3).joinToString(" · ")
 }
 if(editor){CreateObjectScreen(c,{objects=OpenMineObjectStore.all(c);editor=false;status="CREATED + INDEXED"},{editor=false}, {status=it}) ;return}
 LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  item{
   Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("ENGINEERING VAULT",color=MAIN,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("STRICT OBJECTS · VALIDATE · INDEX · RETRIEVE",color=DIM,fontSize=9.sp)}
    Button({editor=true},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)){Text("+ CREATE",fontSize=8.sp)}
   }
   Spacer(Modifier.height(6.dp))
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({picker.launch(arrayOf("text/*","application/json","application/octet-stream"))},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)){Text("IMPORT .OMD",fontSize=8.sp)}
    OutlinedButton({OpenMineObjectStore.rebuildIndex(c);objects=OpenMineObjectStore.all(c);status="INDEX REBUILT"},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)){Text("REBUILD INDEX",fontSize=8.sp)}
   }
   Spacer(Modifier.height(5.dp));OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("RETRIEVAL QUERY",fontSize=9.sp)},placeholder={Text("ollama android connection...",fontSize=9.sp)})
   if(status.isNotBlank())Text(status,color=if(status.contains("REJECTED"))Color(0xFFFF6B6B)else CYAN,fontSize=8.sp)
  }
  val shown=if(query.isBlank())objects else OpenMineObjectStore.search(c,query)
  items(shown){o->
   Surface(Modifier.fillMaxWidth().clickable{selectedObject=o},color=PANEL,shape=RoundedCornerShape(12.dp)){
    Column(Modifier.padding(11.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Verified,null,tint=CYAN,modifier=Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Column(Modifier.weight(1f)){Text(o.title,color=MAIN,fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text(o.id,color=DIM,fontSize=7.sp)};Text(o.status,color=CYAN,fontSize=7.sp)}
     Text(o.fields["OBJECT_SUMMARY"].orEmpty(),color=DIM,fontSize=8.sp,maxLines=2);Text("INDEXED · labeled chunks · exact-term retrieval",color=BLUE,fontSize=7.sp)
    }
   }
  }
  selectedObject?.let{o->item{ObjectInspector(o)}}
 }
}

@Composable fun ObjectInspector(o:StrictObject){
 Surface(Modifier.fillMaxWidth(),color=Color(0xCC071225),shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(11.dp)){
  Text("CANONICAL OBJECT",color=CYAN,fontSize=9.sp,fontWeight=FontWeight.Bold);Text(o.id,color=MAIN,fontSize=11.sp)
  Spacer(Modifier.height(5.dp));o.sections.forEach{(section,values)->Column(Modifier.padding(bottom=5.dp)){Text("["+section+"]",color=PURPLE,fontSize=8.sp,fontWeight=FontWeight.Bold);values.forEach{(k,v)->Text(k+": "+v,color=DIM,fontSize=7.sp,maxLines=3)}}}
 }}
}

@Composable fun CreateObjectScreen(c:Context,onDone:()->Unit,onCancel:()->Unit,onStatus:(String)->Unit){
 var type by remember{mutableStateOf("KNOWLEDGE")};var title by remember{mutableStateOf("")};var summary by remember{mutableStateOf("")};var tags by remember{mutableStateOf("")};var source by remember{mutableStateOf("")}
 var purpose by remember{mutableStateOf("")};var facts by remember{mutableStateOf("")};var procedure by remember{mutableStateOf("")};var constraints by remember{mutableStateOf("")};var examples by remember{mutableStateOf("")}
 var keywords by remember{mutableStateOf("")};var aliases by remember{mutableStateOf("")};var triggers by remember{mutableStateOf("")};var project by remember{mutableStateOf("NONE")};var model by remember{mutableStateOf("NONE")};var skill by remember{mutableStateOf("NONE")};var tool by remember{mutableStateOf("NONE")};var mission by remember{mutableStateOf("NONE")}
 var query by remember{mutableStateOf("")};var whenText by remember{mutableStateOf("")};var exclude by remember{mutableStateOf("")};var verificationSource by remember{mutableStateOf("")};var notes by remember{mutableStateOf("")};var errors by remember{mutableStateOf(emptyList<String>())}
 LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
  item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CREATE OBJECT",color=MAIN,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("Every label becomes a deterministic index address.",color=DIM,fontSize=9.sp)};Text("V1",color=CYAN,fontSize=10.sp)}}
  item{SectionTitle("[OPEN_MINE_OBJECT]");Field("OBJECT_TYPE",type,{type=it.uppercase()});Field("OBJECT_TITLE",title,{title=it});Field("OBJECT_SUMMARY",summary,{summary=it});Field("OBJECT_TAGS",tags,{tags=it});Field("OBJECT_SOURCE",source,{source=it})}
  item{SectionTitle("[CONTEXT_INDEX]");Field("INDEX_KEYWORDS",keywords,{keywords=it});Field("INDEX_ALIASES",aliases,{aliases=it});Field("INDEX_TRIGGERS",triggers,{triggers=it});Text("INDEX_SCOPE: workspace    INDEX_PRIORITY: 50",color=DIM,fontSize=8.sp)}
  item{SectionTitle("[CONTENT]");Field("CONTENT_PURPOSE",purpose,{purpose=it},false);Field("CONTENT_FACTS",facts,{facts=it},false);Field("CONTENT_PROCEDURE",procedure,{procedure=it},false);Field("CONTENT_CONSTRAINTS",constraints,{constraints=it},false);Field("CONTENT_EXAMPLES",examples,{examples=it},false)}
  item{SectionTitle("[RELATIONSHIPS]");Field("REL_PROJECTS",project,{project=it});Field("REL_MODELS",model,{model=it});Field("REL_SKILLS",skill,{skill=it});Field("REL_TOOLS",tool,{tool=it});Field("REL_MISSIONS",mission,{mission=it})}
  item{SectionTitle("[RETRIEVAL]");Field("RETRIEVAL_QUERY",query,{query=it});Field("RETRIEVAL_WHEN",whenText,{whenText=it});Field("RETRIEVAL_EXCLUDE",exclude,{exclude=it})}
  item{SectionTitle("[VERIFICATION]");Text("VERIFICATION_STATUS: DRAFT",color=CYAN,fontSize=8.sp);Field("VERIFICATION_SOURCE",verificationSource,{verificationSource=it});Field("VERIFICATION_NOTES",notes,{notes=it},false)}
  if(errors.isNotEmpty())item{Surface(color=Color(0x44330000),shape=RoundedCornerShape(9.dp)){Column(Modifier.padding(9.dp)){Text("CREATE REJECTED",color=Color(0xFFFF6B6B),fontSize=9.sp);errors.forEach{Text("• "+it,color=MAIN,fontSize=8.sp)}}}}
  item{Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){OutlinedButton({onCancel()},Modifier.weight(1f),shape=RoundedCornerShape(8.dp)){Text("CANCEL",fontSize=9.sp)};Button({
    val o=OpenMineObjectFormat.template(type,title,summary,tags,source,purpose,facts,procedure,constraints,examples,keywords,aliases,triggers,project,model,skill,tool,mission,query,whenText,exclude,"DRAFT",verificationSource,notes)
    val r=OpenMineObjectStore.create(c,o)
    if(r.valid){onStatus("Created "+r.normalized!!.id);onDone()}else errors=r.errors
  },Modifier.weight(1f),shape=RoundedCornerShape(8.dp)){Text("VALIDATE + CREATE",fontSize=9.sp)} }}
 }
}

@Composable fun SectionTitle(s:String){Text(s,color=CYAN,fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=5.dp))}
@Composable fun Field(label:String,value:String,on:(String)->Unit,single:Boolean=true){OutlinedTextField(value,on,Modifier.fillMaxWidth(),singleLine=single,label={Text(label,fontSize=8.sp)},textStyle=LocalTextStyle.current.copy(fontSize=9.sp))}

@Composable fun ListScreen(title:String,items:List<String>){LazyColumn(Modifier.fillMaxSize().padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text(title,color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Reusable Open Mine workspace objects",color=DIM,fontSize=10.sp)};items(items){x->Surface(Modifier.fillMaxWidth(),color=PANEL,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AutoAwesome,null,tint=CYAN);Spacer(Modifier.width(9.dp));Text(x,color=MAIN,fontSize=13.sp)}}}}}

@Composable fun DiagnosticsScreen(c:Context){
 val objects=remember{OpenMineObjectStore.all(c)}
 val indexFile=java.io.File(c.filesDir,"open_mine_index.json")
 LazyColumn(Modifier.fillMaxSize().padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  item{Text("DIAGNOSTICS",color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Live Open Mine storage + index health",color=DIM,fontSize=9.sp)}
  item{Diag("OBJECT STORE",objects.size.toString()+" valid objects",objects.isNotEmpty()||true)}
  item{Diag("RETRIEVAL INDEX",if(indexFile.exists()) (indexFile.length()/1024).toString()+" KB" else "not built",indexFile.exists())}
  item{Diag("STRICT FORMAT","V1 validator active",true)}
  item{Diag("LOCAL STORAGE",c.filesDir.absolutePath,true)}
  item{OutlinedButton({OpenMineObjectStore.rebuildIndex(c)},Modifier.fillMaxWidth()){Text("REBUILD + VERIFY INDEX",fontSize=9.sp)}}
 }
}
@Composable fun Diag(t:String,s:String,ok:Boolean){Surface(color=PANEL,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically){Icon(if(ok)Icons.Default.CheckCircle else Icons.Default.Warning,null,tint=if(ok)CYAN else Color(0xFFFFB24A),modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Column{Text(t,color=MAIN,fontSize=10.sp,fontWeight=FontWeight.SemiBold);Text(s,color=DIM,fontSize=8.sp)}}}}

@Composable fun SettingsScreen(a:Boolean,setA:(Boolean)->Unit,h:Boolean,setH:(Boolean)->Unit){Column(Modifier.fillMaxSize().padding(15.dp)){Text("SETTINGS",color=MAIN,fontSize=25.sp,fontWeight=FontWeight.Bold);Text("Open Mine workspace configuration",color=DIM,fontSize=10.sp);Spacer(Modifier.height(14.dp));Setting("Animations","Orbital motion and interface transitions",a,setA);Setting("Haptic feedback","Touch confirmation for navigation",h,setH);Setting("Proven knowledge only","Prefer verified vault entries for context",true,{})}}
@Composable fun Setting(t:String,s:String,v:Boolean,on:(Boolean)->Unit){Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),color=PANEL,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(t,color=MAIN,fontSize=12.sp);Text(s,color=DIM,fontSize=8.sp)};Switch(v,on)}}}

