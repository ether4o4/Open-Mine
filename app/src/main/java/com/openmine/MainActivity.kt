package com.openmine

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BG=Color(0xFF020712); private val PANEL=Color(0xE60A1830); private val CYAN=Color(0xFF38D8FF)
private val BLUE=Color(0xFF4C78FF); private val PURPLE=Color(0xFF8B5CFF); private val MAIN=Color(0xFFEAF5FF); private val DIM=Color(0xFF91A9C8)
data class Nav(val name:String,val icon:ImageVector)
data class Orb(val title:String,val sub:String,val accent:Color,val icon:ImageVector,val id:String=title,val record:StrictObject?=null)

private val NAV=listOf(
 Nav("AI MODELS",Icons.Default.Memory),Nav("PROJECTS",Icons.Default.Folder),Nav("CONNECTORS",Icons.Default.Link),
 Nav("KNOWLEDGE",Icons.Default.School),Nav("SKILLS",Icons.Default.Build),Nav("MISSIONS",Icons.Default.Flag),
 Nav("TOOLS",Icons.Default.Construction),Nav("FILES",Icons.Default.Description),Nav("BROWSER",Icons.Default.Language),
 Nav("TERMINAL",Icons.Default.Terminal),Nav("DIAGNOSTICS",Icons.Default.BugReport),Nav("SETTINGS",Icons.Default.Settings),Nav("AI CHAT",Icons.Default.Chat)
)

class MainActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{OpenMine()}}
}

@Composable fun OpenMine(){
 val c=LocalContext.current; val p=remember{c.getSharedPreferences("open_mine",Context.MODE_PRIVATE)}
 var selected by remember{mutableIntStateOf(p.getInt("screen",3).coerceIn(0,NAV.lastIndex))}
 var animations by remember{mutableStateOf(p.getBoolean("animations",true))}
 var haptics by remember{mutableStateOf(p.getBoolean("haptics",true))}
 var active by remember{mutableStateOf(p.getString("active","") ?: "")}
 var contextItems by remember{mutableStateOf(p.getStringSet("context",emptySet())?.toSet() ?: emptySet())}
 fun selectObject(o:Orb){active=o.id;p.edit().putString("active",o.id).apply()}
 fun toggleContext(o:Orb){contextItems=if(o.id in contextItems)contextItems-o.id else contextItems+o.id;p.edit().putStringSet("context",contextItems).apply()}
 fun go(i:Int){selected=i;p.edit().putInt("screen",i).apply();if(haptics)(c as? android.app.Activity)?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)}
 BackHandler(selected != 3) { go(3) }
 MaterialTheme(colorScheme=darkColorScheme(background=BG,surface=PANEL,primary=CYAN,secondary=PURPLE)){
  Surface(Modifier.fillMaxSize().safeDrawingPadding(),color=BG){Column{Header();Row(Modifier.fillMaxSize()){
   Rail(selected,::go);Box(Modifier.weight(1f).fillMaxHeight()){
    AnimatedContent(selected,transitionSpec={fadeIn(tween(if(animations)180 else 0)) togetherWith fadeOut(tween(if(animations)120 else 0))},label="navigation"){screen->when(screen){
     10->DiagnosticsScreen(c)
     11->SettingsScreen(animations,{v->animations=v;p.edit().putBoolean("animations",v).apply()},haptics,{v->haptics=v;p.edit().putBoolean("haptics",v).apply()})
     12->AssistantScreen(c)
     9->RuntimeScreen(c,true)
     0,1,2,3,4,5,6,7->WorkspaceScreen(screen,animations,active,contextItems,::selectObject,::toggleContext)
     8->CapabilityScreen(NAV[screen].name,"No browser executor is installed. Your library remains available in Knowledge.")
     else->Orbit(screen,animations,active,contextItems,::selectObject,::toggleContext)
    }}
   }
  }}}
 }
}

@Composable fun Header(){Row(Modifier.fillMaxWidth().height(62.dp).padding(9.dp),verticalAlignment=Alignment.CenterVertically){
 Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){
  Box(Modifier.size(31.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF15223B))){Canvas(Modifier.fillMaxSize()){
   val s=size.minDimension/2.8f;drawRect(Color(0xFFFFD438),Offset(2f,2f),Size(s,s));drawRect(CYAN,Offset(s+5,2f),Size(s,s))
   drawRect(PURPLE,Offset(2f,s+5),Size(s,s));drawRect(Color(0xFFFF4EC4),Offset(s+5,s+5),Size(s,s))
  }};Spacer(Modifier.width(8.dp));Column{Text("Open Mine",color=MAIN,fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text("AI WORKSPACE ENVIRONMENT",color=DIM,fontSize=9.sp,letterSpacing=.4.sp,maxLines=1)}
 }
 Pill("LIBRARY","ON DEVICE")
}}

@Composable fun Pill(a:String,b:String){Column(Modifier.clip(RoundedCornerShape(8.dp)).background(PANEL).padding(horizontal=9.dp,vertical=5.dp)){Text(a,color=MAIN,fontSize=12.sp);Text(b,color=DIM,fontSize=12.sp)}}

@Composable fun Rail(selected:Int,onSelect:(Int)->Unit){Column(Modifier.width((LocalConfiguration.current.screenWidthDp*.22f).coerceIn(76f,112f).dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
 NAV.forEachIndexed{i,n->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(8.dp)).background(if(i==selected)Color(0x332AD8FF)else Color.Transparent).clickable{onSelect(i)}.padding(6.dp),verticalAlignment=Alignment.CenterVertically){
  Icon(n.icon,null,tint=if(i==selected)CYAN else DIM,modifier=Modifier.size(17.dp));Spacer(Modifier.width(5.dp));Column{Text(n.name,color=if(i==selected)MAIN else DIM,fontSize=10.sp,maxLines=2)}
 }}}}

@Composable fun WorkspaceScreen(screen:Int,animations:Boolean,active:String,contextItems:Set<String>,onSelect:(Orb)->Unit,onToggleContext:(Orb)->Unit){
 var manage by remember(screen){mutableStateOf(false)}
 BackHandler(manage){manage=false}
 val c=LocalContext.current
 if(manage){Column(Modifier.fillMaxSize()){
  TextButton({manage=false}){Icon(Icons.Default.ArrowBack,null);Text("Back to workspace")}
  Box(Modifier.weight(1f)){if(screen==0)RuntimeScreen(c,false)else Knowledge(c,when(screen){1->"PROJECT";2->"CONNECTOR";4->"SKILL";5->"MISSION";6->"TOOL";7->"FILE";else->null})}
 }}else Column(Modifier.fillMaxSize()){
  TextButton({manage=true},Modifier.align(Alignment.End)){Text(if(screen==0)"MODEL RUNTIME + IMPORT" else "CREATE / IMPORT / MANAGE")}
  Box(Modifier.weight(1f)){Orbit(screen,animations,active,contextItems,onSelect,onToggleContext)}
 }
}

@Composable fun Orbit(screen:Int,animations:Boolean,active:String,contextItems:Set<String>,onSelect:(Orb)->Unit,onToggleContext:(Orb)->Unit){
 val c=LocalContext.current
 val type=when(screen){0->"MODEL";1->"PROJECT";2->"CONNECTOR";4->"SKILL";5->"MISSION";6->"TOOL";7->"FILE";else->"KNOWLEDGE"}
 val accents=listOf(CYAN,PURPLE,Color(0xFFFFD438),Color(0xFFFF4EC4),BLUE)
 val items=OpenMineObjectStore.all(c).filter{it.type==type}.mapIndexed{i,o->Orb(o.title,o.status,accents[i%accents.size],NAV[screen].icon,o.id,o)}
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  item{Text(NAV[screen].name,color=MAIN,fontSize=20.sp,fontWeight=FontWeight.Bold)}
  item{OrbitalGallery(items,active,onSelect,animations)}
  if(items.isEmpty())item{Text("No ${type.lowercase()} records yet. Create or import your own records using the management control above. Model weights are available in Model Runtime.",color=DIM,fontSize=14.sp)}
  else{
   item{ContextPanel(screen,items.firstOrNull{it.id==active}?:items.first(),contextItems,onToggleContext)}
   item{Carousel(items,onSelect)}
  }
 }
}

@Composable fun OrbitalGallery(items:List<Orb>,active:String,onSelect:(Orb)->Unit,animations:Boolean){
 val start=(items.indexOfFirst{it.id==active}.coerceAtLeast(0)/6)*6
 val visible=items.drop(start).take(6)
 val pulse=if(animations)rememberInfiniteTransition(label="orbit").animateFloat(.5f,1f,infiniteRepeatable(tween(1800),RepeatMode.Reverse),label="glow").value else 1f
 val galleryHeight=((LocalConfiguration.current.screenHeightDp-150)*.48f).coerceIn(260f,360f)
 BoxWithConstraints(Modifier.fillMaxWidth().height(galleryHeight.dp)){
  val width=maxWidth.value;val cardWidth=(width-12f)/2;val centerX=width/2
  Canvas(Modifier.fillMaxSize()){
   val center=Offset(size.width/2,size.height/2)
   for(i in 1..4)drawCircle((if(i%2==0)PURPLE else CYAN).copy(alpha=(.12f+i*.025f)*pulse),radius=minOf((12+i*7).dp.toPx(),size.width*.17f),center=center,style=Stroke(2.dp.toPx()))
   drawRoundRect(Brush.linearGradient(listOf(BLUE,PURPLE)),center-Offset(16.dp.toPx(),16.dp.toPx()),Size(32.dp.toPx(),32.dp.toPx()),CornerRadius(8.dp.toPx()))
  }
  visible.forEachIndexed{i,o->
   val petalWidth=if(i/2==1)width*.30f else cardWidth
   val x=if(i%2==0)0f else width-petalWidth
   val y=when(i/2){0->0f;1->galleryHeight/2-48f;else->galleryHeight-96f}
   Surface(Modifier.offset(x.dp,y.dp).width(petalWidth.dp).height(96.dp).clickable{onSelect(o)},color=if(o.id==active)Color(0xFF132C48)else PANEL,shape=RoundedCornerShape(12.dp),shadowElevation=4.dp){
    Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){Icon(o.icon,null,tint=o.accent,modifier=Modifier.size(20.dp));Spacer(Modifier.weight(1f));Text("${start+i+1}",color=DIM,fontSize=11.sp)}
     Text(o.title,color=MAIN,fontSize=13.sp,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
     Text(o.sub,color=o.accent,fontSize=11.sp,maxLines=1)
    }
   }
  }
 }
 if(items.size>6)Text("${start+1}�${minOf(start+6,items.size)} of ${items.size} � select another card below",color=DIM,fontSize=11.sp)
}

@Composable fun ContextPanel(screen:Int,current:Orb?,contextItems:Set<String>,onToggleContext:(Orb)->Unit){
 var inspect by remember(current?.id){mutableStateOf(false)}
 var tab by remember(current?.id){mutableIntStateOf(0)}
 var toolReview by remember(current?.id){mutableStateOf<String?>(null)}
 var actionError by remember(current?.id){mutableStateOf("")}
 val actionContext=LocalContext.current
 val runtime=remember{com.openmine.sandbox.OpenMineRuntime.get(actionContext)}
 val toolBusy by runtime.busy.collectAsState()
 val toolOutput by runtime.terminalOutput.collectAsState()
 val toolStatus by runtime.status.collectAsState()
 val record=current?.record
 toolReview?.let{command->AlertDialog(onDismissRequest={toolReview=null},title={Text("Run ${current?.title}?")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("Runs for up to 30 seconds in your Linux environment, with network access and access to its files. Review the complete command:");Text(command)}},confirmButton={TextButton({toolReview=null;runtime.command(command)}){Text("RUN COMMAND")}},dismissButton={TextButton({toolReview=null}){Text("CANCEL")}})}
 if(inspect && record!=null)AlertDialog(onDismissRequest={inspect=false},confirmButton={TextButton({inspect=false}){Text("Close")}},title={Text(current.title)},text={Column(Modifier.verticalScroll(rememberScrollState())){ObjectInspector(record)}})
 Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PANEL).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text(current?.title.orEmpty(),color=MAIN,fontSize=20.sp,fontWeight=FontWeight.Bold)
  Text(current?.sub.orEmpty()+" � ${contextItems.size} context records",color=DIM,fontSize=12.sp)
  Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){listOf("Overview","Context","Tools","Related").forEachIndexed{i,label->TextButton({tab=i},contentPadding=PaddingValues(horizontal=8.dp)){Text(label,color=if(tab==i)CYAN else DIM,fontSize=12.sp)}}}
  HorizontalDivider(color=DIM.copy(alpha=.2f))
  val content=when(tab){
   0->record?.fields?.get("OBJECT_SUMMARY")
   1->record?.sections?.get("CONTENT")?.get("CONTENT_FACTS")
   2->record?.sections?.get("CONTENT")?.get("CONTENT_PROCEDURE")
   else->record?.sections?.get("RELATIONSHIPS")?.entries?.filter{it.value!="NONE"}?.joinToString("\n"){it.key+": "+it.value}
  }
  Text(content?.takeUnless{it=="NONE" || it.isBlank()} ?: "No ${listOf("overview","context facts","tool procedure","related records")[tab]} recorded.",color=DIM,fontSize=14.sp)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   Button({current?.let{onToggleContext(it)}},Modifier.weight(1f).heightIn(min=48.dp)){Text(if(current?.id in contextItems)if(record?.type=="SKILL")"Deactivate Skill" else "Remove context" else if(record?.type=="SKILL")"Activate Skill" else "Add to Context",fontSize=13.sp)}
   OutlinedIconButton({inspect=true},Modifier.size(48.dp)){Icon(Icons.Default.Description,"Inspect labeled record")}
  }
  if(record?.type=="TOOL"){
   OutlinedButton({runCatching{RecordActions.toolCommand(record)}.onSuccess{toolReview=it;actionError=""}.onFailure{actionError=it.message.orEmpty()}},enabled=!toolBusy && runtime.ready,modifier=Modifier.fillMaxWidth()){Text("REVIEW + RUN TOOL")}
   if(!runtime.ready)Text("Set up Linux in Terminal before running a tool.",color=DIM,fontSize=12.sp)
   if(actionError.isNotBlank())Text(actionError,color=Color(0xFFFF6B6B))
   Text(toolStatus,color=CYAN,fontSize=12.sp)
   if(toolBusy)OutlinedButton({runtime.cancel()}){Text("Cancel running operation")}
   androidx.compose.foundation.text.selection.SelectionContainer{Text(toolOutput.takeLast(6000),color=DIM,fontSize=12.sp)}
  }
 }
}

@Composable fun Carousel(items:List<Orb>,onSelect:(Orb)->Unit){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){items.forEach{o->Surface(Modifier.width(140.dp).heightIn(min=64.dp).clickable{onSelect(o)},color=PANEL,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(o.icon,null,tint=o.accent,modifier=Modifier.size(20.dp));Text(o.title,color=DIM,fontSize=12.sp,maxLines=2)}}}}}
@Composable fun Knowledge(c:Context, category:String?=null){
 var objects by remember{mutableStateOf(OpenMineObjectStore.all(c))}
 var query by remember{mutableStateOf("")}
 var editor by remember{mutableStateOf(false)}
 var status by remember{mutableStateOf("")}
 var selectedObject by remember{mutableStateOf<StrictObject?>(null)}
 var rawEdit by remember{mutableStateOf<String?>(null)}
 var confirmDelete by remember{mutableStateOf(false)}
 BackHandler(editor || selectedObject != null) { if(editor) editor=false else selectedObject=null }
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
  if(uri==null)return@rememberLauncherForActivityResult
  val raw=runCatching{c.contentResolver.openInputStream(uri)?.use{stream->
   val output=java.io.ByteArrayOutputStream()
   val buffer=ByteArray(8192)
   while(true){val count=stream.read(buffer);if(count<0)break;output.write(buffer,0,count);require(output.size()<=1024*1024){"Import exceeds 1 MiB"}}
   val bytes=output.toByteArray()
   require(bytes.size<=1024*1024){"Import exceeds 1 MiB"}
   Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
  } ?: error("Cannot open selected file")}.getOrElse{status="IMPORT REJECTED: ${it.message}";return@rememberLauncherForActivityResult}
  val checked=OpenMineObjectFormat.validate(raw)
  val result=if(category!=null && checked.valid && checked.normalized?.type!=category)ValidationResult(false,listOf("Expected a $category record for this category; import other types in Knowledge.")) else OpenMineObjectStore.import(c,raw)
  if(result.valid){objects=OpenMineObjectStore.all(c);status="IMPORTED + INDEXED: "+result.normalized!!.id;selectedObject=result.normalized}
  else status="IMPORT REJECTED: "+result.errors.take(3).joinToString(" · ")
 }
 if(editor){CreateObjectScreen(c,{objects=OpenMineObjectStore.all(c);editor=false;status="CREATED + INDEXED"},{editor=false}, {status=it},category ?: "KNOWLEDGE") ;return}
 LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  item{
   Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(category?.let{"${it}S"} ?: "ENGINEERING VAULT",color=MAIN,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("STRICT OBJECTS · VALIDATE · INDEX · RETRIEVE",color=DIM,fontSize=12.sp)}
    Button({editor=true},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)){Text("+ CREATE",fontSize=12.sp)}
   }
   Spacer(Modifier.height(6.dp))
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({picker.launch(arrayOf("text/*","application/octet-stream"))},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)){Text("IMPORT .OMD",fontSize=12.sp)}
    OutlinedButton({OpenMineObjectStore.rebuildIndex(c);objects=OpenMineObjectStore.all(c);status="INDEX REBUILT"},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)){Text("REBUILD INDEX",fontSize=12.sp)}
   }
   Spacer(Modifier.height(5.dp));OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("RETRIEVAL QUERY",fontSize=12.sp)},placeholder={Text("ollama android connection...",fontSize=12.sp)})
   if(status.isNotBlank())Text(status,color=if(status.contains("REJECTED"))Color(0xFFFF6B6B)else CYAN,fontSize=12.sp)
  }
  val shown=(if(query.isBlank())objects else OpenMineObjectStore.search(c,query)).filter{category==null || it.fields["OBJECT_TYPE"]==category}
  if(shown.isEmpty())item{Text(if(objects.isEmpty())"Your library is empty. Create a labeled record or import a UTF-8 .omd file (up to 1 MiB). JSON, PDF and model weights are not supported imports." else "No matching knowledge. Try another keyword.",color=DIM,fontSize=14.sp)}
  items(shown){o->
   Surface(Modifier.fillMaxWidth().clickable{selectedObject=o},color=PANEL,shape=RoundedCornerShape(12.dp)){
    Column(Modifier.padding(11.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Verified,null,tint=CYAN,modifier=Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Column(Modifier.weight(1f)){Text(o.title,color=MAIN,fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text(o.id,color=DIM,fontSize=12.sp)};Text(o.status,color=CYAN,fontSize=12.sp)}
     Text(o.fields["OBJECT_SUMMARY"].orEmpty(),color=DIM,fontSize=12.sp,maxLines=2);Text("INDEXED · labeled chunks · exact-term retrieval",color=BLUE,fontSize=12.sp)
    }
   }
  }
  selectedObject?.let{o->item{
   ObjectInspector(o)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    OutlinedButton({rawEdit=o.raw}){Text("EDIT")}
    OutlinedButton({confirmDelete=true}){Text("DELETE")}
   }
  }}
 }
 rawEdit?.let{raw->AlertDialog(onDismissRequest={rawEdit=null},title={Text("Edit labeled record")},text={OutlinedTextField(raw,{rawEdit=it},Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton({
  selectedObject?.let{o->val result=OpenMineObjectStore.update(c,o.id,raw);if(result.valid){objects=OpenMineObjectStore.all(c);selectedObject=result.normalized;rawEdit=null;status="UPDATED + INDEXED"}else status=result.errors.joinToString("; ")}
 }){Text("VALIDATE + SAVE")}},dismissButton={TextButton({rawEdit=null}){Text("CANCEL")}})}
 if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Delete record?")},text={Text("This removes the selected record and its retrieval index entries.")},confirmButton={TextButton({
  runCatching{selectedObject?.let{OpenMineObjectStore.delete(c,it)}}.onSuccess{objects=OpenMineObjectStore.all(c);selectedObject=null;status="DELETED"}.onFailure{status="Delete failed: ${it.message}"};confirmDelete=false
 }){Text("DELETE")}},dismissButton={TextButton({confirmDelete=false}){Text("CANCEL")}})
}

@Composable fun ObjectInspector(o:StrictObject){
 Surface(Modifier.fillMaxWidth(),color=Color(0xCC071225),shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(11.dp)){
  Text("CANONICAL OBJECT",color=CYAN,fontSize=12.sp,fontWeight=FontWeight.Bold);Text(o.id,color=MAIN,fontSize=12.sp)
  Spacer(Modifier.height(5.dp));o.sections.forEach{(section,values)->Column(Modifier.padding(bottom=5.dp)){Text("["+section+"]",color=PURPLE,fontSize=12.sp,fontWeight=FontWeight.Bold);values.forEach{(k,v)->Text(k+": "+v,color=DIM,fontSize=12.sp,maxLines=3)}}}
 }}
}

@Composable fun CreateObjectScreen(c:Context,onDone:()->Unit,onCancel:()->Unit,onStatus:(String)->Unit,initialType:String="KNOWLEDGE"){
 var type by remember{mutableStateOf(initialType)};var title by remember{mutableStateOf("")};var summary by remember{mutableStateOf("")};var tags by remember{mutableStateOf("")};var source by remember{mutableStateOf("")}
 var purpose by remember{mutableStateOf("")};var facts by remember{mutableStateOf("")};var procedure by remember{mutableStateOf("")};var constraints by remember{mutableStateOf("")};var examples by remember{mutableStateOf("")}
 var keywords by remember{mutableStateOf("")};var aliases by remember{mutableStateOf("")};var triggers by remember{mutableStateOf("")};var project by remember{mutableStateOf("NONE")};var model by remember{mutableStateOf("NONE")};var skill by remember{mutableStateOf("NONE")};var tool by remember{mutableStateOf("NONE")};var mission by remember{mutableStateOf("NONE")}
 var query by remember{mutableStateOf("")};var whenText by remember{mutableStateOf("")};var exclude by remember{mutableStateOf("")};var verificationSource by remember{mutableStateOf("")};var notes by remember{mutableStateOf("")};var errors by remember{mutableStateOf(emptyList<String>())}
 LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
  item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CREATE OBJECT",color=MAIN,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("Every label becomes a deterministic index address.",color=DIM,fontSize=12.sp)};Text("V1",color=CYAN,fontSize=12.sp)}}
  item{SectionTitle("[OPEN_MINE_OBJECT]");Field("OBJECT_TYPE",type,{type=it.uppercase()});Field("OBJECT_TITLE",title,{title=it});Field("OBJECT_SUMMARY",summary,{summary=it});Field("OBJECT_TAGS",tags,{tags=it});Field("OBJECT_SOURCE",source,{source=it})}
  item{SectionTitle("[CONTEXT_INDEX]");Field("INDEX_KEYWORDS",keywords,{keywords=it});Field("INDEX_ALIASES",aliases,{aliases=it});Field("INDEX_TRIGGERS",triggers,{triggers=it});Text("INDEX_SCOPE: workspace    INDEX_PRIORITY: 50",color=DIM,fontSize=12.sp)}
  item{SectionTitle("[CONTENT]");Field("CONTENT_PURPOSE",purpose,{purpose=it},false);Field("CONTENT_FACTS",facts,{facts=it},false);Field("CONTENT_PROCEDURE",procedure,{procedure=it},false);Field("CONTENT_CONSTRAINTS",constraints,{constraints=it},false);Field("CONTENT_EXAMPLES",examples,{examples=it},false)}
  item{SectionTitle("[RELATIONSHIPS]");Field("REL_PROJECTS",project,{project=it});Field("REL_MODELS",model,{model=it});Field("REL_SKILLS",skill,{skill=it});Field("REL_TOOLS",tool,{tool=it});Field("REL_MISSIONS",mission,{mission=it})}
  item{SectionTitle("[RETRIEVAL]");Field("RETRIEVAL_QUERY",query,{query=it});Field("RETRIEVAL_WHEN",whenText,{whenText=it});Field("RETRIEVAL_EXCLUDE",exclude,{exclude=it})}
  item{SectionTitle("[VERIFICATION]");Text("VERIFICATION_STATUS: DRAFT",color=CYAN,fontSize=12.sp);Field("VERIFICATION_SOURCE",verificationSource,{verificationSource=it});Field("VERIFICATION_NOTES",notes,{notes=it},false)}
  if(errors.isNotEmpty())item{Surface(color=Color(0x44330000),shape=RoundedCornerShape(9.dp)){Column(Modifier.padding(9.dp)){Text("CREATE REJECTED",color=Color(0xFFFF6B6B),fontSize=12.sp);errors.forEach{Text("• "+it,color=MAIN,fontSize=12.sp)}}}}
  item{Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){OutlinedButton({onCancel()},Modifier.weight(1f),shape=RoundedCornerShape(8.dp)){Text("CANCEL",fontSize=12.sp)};Button({
    val o=OpenMineObjectFormat.template(type,title,summary,tags,source,purpose,facts,procedure,constraints,examples,keywords,aliases,triggers,project,model,skill,tool,mission,query,whenText,exclude,"DRAFT",verificationSource,notes)
    val r=OpenMineObjectStore.create(c,o)
    if(r.valid){onStatus("Created "+r.normalized!!.id);onDone()}else errors=r.errors
  },Modifier.weight(1f),shape=RoundedCornerShape(8.dp)){Text("VALIDATE + CREATE",fontSize=12.sp)} }}
 }
}

@Composable fun SectionTitle(s:String){Text(s,color=CYAN,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=5.dp))}
@Composable fun Field(label:String,value:String,on:(String)->Unit,single:Boolean=true){OutlinedTextField(value,on,Modifier.fillMaxWidth(),singleLine=single,label={Text(label,fontSize=12.sp)},textStyle=LocalTextStyle.current.copy(fontSize=16.sp))}

@Composable fun ListScreen(title:String,items:List<String>){LazyColumn(Modifier.fillMaxSize().padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text(title,color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Reusable Open Mine workspace objects",color=DIM,fontSize=12.sp)};items(items){x->Surface(Modifier.fillMaxWidth(),color=PANEL,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AutoAwesome,null,tint=CYAN);Spacer(Modifier.width(9.dp));Text(x,color=MAIN,fontSize=13.sp)}}}}}

@Composable fun DiagnosticsScreen(c:Context){
 val objects=remember{OpenMineObjectStore.all(c)}
 val indexFile=java.io.File(c.filesDir,"open_mine_index.json")
 LazyColumn(Modifier.fillMaxSize().padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  item{Text("DIAGNOSTICS",color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Live Open Mine storage + index health",color=DIM,fontSize=12.sp)}
  item{Diag("OBJECT STORE",objects.size.toString()+" valid objects",true)}
  item{Diag("RETRIEVAL INDEX",if(indexFile.exists()) (indexFile.length()/1024).toString()+" KB" else "not built",indexFile.exists())}
  item{Diag("STRICT FORMAT","V1 validator active",true)}
  item{Diag("LOCAL STORAGE",c.filesDir.absolutePath,true)}
  item{OutlinedButton({OpenMineObjectStore.rebuildIndex(c)},Modifier.fillMaxWidth()){Text("REBUILD + VERIFY INDEX",fontSize=12.sp)}}
 }
}
@Composable fun Diag(t:String,s:String,ok:Boolean){Surface(color=PANEL,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically){Icon(if(ok)Icons.Default.CheckCircle else Icons.Default.Warning,null,tint=if(ok)CYAN else Color(0xFFFFB24A),modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Column{Text(t,color=MAIN,fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text(s,color=DIM,fontSize=12.sp)}}}}

@Composable fun SettingsScreen(a:Boolean,setA:(Boolean)->Unit,h:Boolean,setH:(Boolean)->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp)){Text("SETTINGS",color=MAIN,fontSize=25.sp,fontWeight=FontWeight.Bold);Text("Open Mine workspace configuration",color=DIM,fontSize=14.sp);Spacer(Modifier.height(14.dp));Setting("Animations","Orbital motion and interface transitions",a,setA);Setting("Haptic feedback","Touch confirmation for navigation",h,setH)}}

@Composable fun CapabilityScreen(title:String,description:String){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
 Icon(Icons.Default.Workspaces,null,tint=CYAN,modifier=Modifier.size(48.dp))
 Text(title,color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold)
 Text(description,color=DIM,fontSize=16.sp,lineHeight=24.sp)
}}

@Composable fun LibraryTools(c:Context){
 var query by remember{mutableStateOf("")}
 var result by remember{mutableStateOf("")}
 LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Text("LIBRARY TOOLS",color=MAIN,fontSize=24.sp);Text("Retrieve labeled source context from your persistent library. These tools do not run a language model or external commands.",color=DIM,fontSize=16.sp)}
  item{OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Knowledge query")})}
  item{Button({val matches=OpenMineObjectStore.search(c,query);result=matches.take(5).joinToString("\n\n"){it.raw}.ifBlank{"No matching sources."}},enabled=query.isNotBlank()){Text("Retrieve source context")}}
  item{androidx.compose.foundation.text.selection.SelectionContainer { Text(result,color=MAIN,fontSize=14.sp) }}
 }
}

@Composable fun AssistantScreen(c:Context){
 val prefs=remember{c.getSharedPreferences("assistant",Context.MODE_PRIVATE)}
 var endpoint by remember{mutableStateOf(prefs.getString("endpoint","").orEmpty())}
 var model by remember{mutableStateOf(prefs.getString("model","").orEmpty())}
 var key by remember{mutableStateOf("")}
 var question by remember{mutableStateOf("")}
 var answer by remember{mutableStateOf(prefs.getString("last_answer","").orEmpty())}
 var status by remember{mutableStateOf("")}
 var sources by remember{mutableStateOf(emptyList<StrictObject>())}
 var busy by remember{mutableStateOf(false)}
 val scope=rememberCoroutineScope()
 LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Text("AI IN YOUR LIBRARY",color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Start an imported GGUF in AI Models, then connect its on-device API. Matching library sources stay on your phone for loopback inference. HTTPS endpoints receive source context. Activated skills are sent as workflow context. The model can search the library and read fixed system information; custom tools run only after your review in Tools.",color=DIM,fontSize=14.sp);OutlinedButton({endpoint="http://127.0.0.1:8080/v1";model="local"},enabled=!busy){Text("Use on-device model")}}
  item{OutlinedTextField(endpoint,{endpoint=it},Modifier.fillMaxWidth(),enabled=!busy,label={Text("HTTPS API base URL")},singleLine=true)}
  item{OutlinedTextField(model,{model=it},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Server model ID")},singleLine=true)}
  item{OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),enabled=!busy,label={Text("API key (this session only)")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())}
  item{OutlinedTextField(question,{question=it},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Ask your library")})}
  item{Button({busy=true;answer="";sources=emptyList();status="Retrieving sources and contacting model server…";scope.launch{
   val result=withContext(Dispatchers.IO){runCatching{ModelClient.ask(c,endpoint,model,key,question)}}
   result.onSuccess{reply->answer=reply.text;sources=reply.sources;status=if(reply.toolResults.isEmpty())"Model answered with ${sources.size} retrieved source records." else reply.toolResults.joinToString("\n");prefs.edit().putString("endpoint",endpoint).putString("model",model).putString("last_answer",answer).apply()}
    .onFailure{status="Request failed: ${it.message ?: "Connection or response error"}. No external actions ran."}
   busy=false
  }},enabled=!busy && question.isNotBlank() && endpoint.isNotBlank() && model.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(if(busy)"Working…" else "Ask model with library context")}}
  item{Text(status,color=CYAN,fontSize=14.sp)}
  item{androidx.compose.foundation.text.selection.SelectionContainer{Text(answer,color=MAIN,fontSize=16.sp,lineHeight=24.sp)}}
  items(sources){source->ObjectInspector(source)}
 }
}

@Composable fun RuntimeScreen(c:Context,terminal:Boolean){
 val runtime=remember{com.openmine.sandbox.OpenMineRuntime.get(c)}
 val status by runtime.status.collectAsState()
 val busy by runtime.busy.collectAsState()
 val output by (if(terminal)runtime.terminalOutput else runtime.output).collectAsState()
 val models by runtime.models.collectAsState()
 var command by remember{mutableStateOf("")}
 var confirm by remember{mutableStateOf(false)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)runtime.importModel(uri)}
 if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Run this command?")},text={Text("This command runs inside Open Mine's Linux environment with network access and access to its files.\n\n$command")},confirmButton={TextButton({confirm=false;runtime.command(command)}){Text("Run")}},dismissButton={TextButton({confirm=false}){Text("Cancel")}})
 LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Text(if(terminal)"LINUX SHELL" else "ON-DEVICE GGUF",color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Open Mine contains reused MVE Linux runtime code. Development setup downloads an Alpine environment and engine prerequisites; engine setup can take 10–30 minutes. No other app or computer is required. Keep Open Mine open during setup.",color=DIM,fontSize=14.sp)}
  item{Text(status,color=CYAN,fontSize=14.sp);if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())}
  item{Button({runtime.setup()},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Set up Linux shell")}}
  if(terminal){
   item{OutlinedTextField(command,{command=it},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Shell command")})}
   item{Button({confirm=true},enabled=!busy && runtime.ready && command.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Review and run command")}}
  }else{
   item{Button({runtime.engine("provision")},enabled=!busy && runtime.ready,modifier=Modifier.fillMaxWidth()){Text("Set up GGUF engine")}}
   item{OutlinedButton({picker.launch(arrayOf("application/octet-stream","*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Import GGUF weights")}}
   if(models.isEmpty())item{Text("No model weights imported. Choose a small quantized GGUF that fits your phone's RAM. Model loading verifies compatibility; a valid file header alone does not.",color=DIM,fontSize=14.sp)}
   items(models){model->Surface(color=PANEL,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(12.dp)){Text(model,color=MAIN,fontSize=12.sp);Button({runtime.engine("serve",model)},enabled=!busy && runtime.ready){Text("Load and start model")}}}}
   item{OutlinedButton({runtime.engine("status")},enabled=!busy && runtime.ready){Text("Check engine status")}}
   item{OutlinedButton({runtime.engine("stop")},enabled=!busy && runtime.ready){Text("Stop model")}}
  }
  if(busy)item{OutlinedButton({runtime.cancel()}){Text("Cancel operation")}}
  item{androidx.compose.foundation.text.selection.SelectionContainer{Text(output,color=MAIN,fontSize=12.sp)}}
 }
}
@Composable fun Setting(t:String,s:String,v:Boolean,on:(Boolean)->Unit){Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),color=PANEL,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(t,color=MAIN,fontSize=12.sp);Text(s,color=DIM,fontSize=12.sp)};Switch(v,on)}}}
