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
     3->Knowledge(c)
     6->LibraryTools(c)
     8,9->CapabilityScreen(NAV[screen].name,"No browser or terminal executor is installed. Your library remains available in Knowledge.")
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
  }};Spacer(Modifier.width(8.dp));Column{Text("Open Mine",color=MAIN,fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text("AI WORKSPACE ENVIRONMENT",color=DIM,fontSize=12.sp,letterSpacing=1.sp)}
 }
 Pill("LIBRARY","ON DEVICE")
}}

@Composable fun Pill(a:String,b:String){Column(Modifier.clip(RoundedCornerShape(8.dp)).background(PANEL).padding(horizontal=9.dp,vertical=5.dp)){Text(a,color=MAIN,fontSize=12.sp);Text(b,color=DIM,fontSize=12.sp)}}

@Composable fun Rail(selected:Int,onSelect:(Int)->Unit){Column(Modifier.width(91.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
 NAV.forEachIndexed{i,n->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(8.dp)).background(if(i==selected)Color(0x332AD8FF)else Color.Transparent).clickable{onSelect(i)}.padding(6.dp),verticalAlignment=Alignment.CenterVertically){
  Icon(n.icon,null,tint=if(i==selected)CYAN else DIM,modifier=Modifier.size(17.dp));Spacer(Modifier.width(5.dp));Column{Text(n.name,color=if(i==selected)MAIN else DIM,fontSize=12.sp)}
 }}}}

@Composable fun Orbit(screen:Int,animations:Boolean,active:String,contextItems:Set<String>,onSelect:(Orb)->Unit,onToggleContext:(Orb)->Unit){
 val c=LocalContext.current
 val type=when(screen){0->"MODEL";1->"PROJECT";2->"CONNECTOR";4->"SKILL";5->"MISSION";7->"FILE";else->"KNOWLEDGE"}
 val items=remember(screen){OpenMineObjectStore.all(c).filter{it.type==type}.map{Orb(it.title,it.status,CYAN,NAV[screen].icon,it.id,it)}}
 if(items.isEmpty()){CapabilityScreen(NAV[screen].name,"No ${type.lowercase()} records yet. Create or import labeled records in Knowledge. "+when(screen){0->"GGUF inference is not installed. A model record does not load weights.";2->"Service authentication and remote actions are not implemented.";else->"Your records stay on this device across model switches."});return}
 LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Text(NAV[screen].name,color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold)}
  item{OrbitalGallery(items,active,onSelect,animations)}
  item{ContextPanel(screen,items.firstOrNull{it.id==active}?:items.first(),contextItems,onToggleContext)}
  item{Carousel(items,onSelect)}
 }
}

@Composable fun OrbitalGallery(items:List<Orb>,active:String,onSelect:(Orb)->Unit,animations:Boolean){
 val start=(items.indexOfFirst{it.id==active}.coerceAtLeast(0)/4)*4
 val visible=items.drop(start).take(4)
 val pulse=if(animations)rememberInfiniteTransition(label="orbit").animateFloat(.5f,1f,infiniteRepeatable(tween(1800),RepeatMode.Reverse),label="glow").value else 1f
 BoxWithConstraints(Modifier.fillMaxWidth().height(380.dp)){
  val width=maxWidth.value;val cardWidth=minOf(120f,width*.44f);val centerX=width/2;val centerY=190f
  Canvas(Modifier.fillMaxSize()){
   val center=Offset(size.width/2,size.height/2)
   for(i in 1..4)drawOval(CYAN.copy(alpha=.08f*pulse),Offset(size.width*(.5f-i*.1f),size.height*(.5f-i*.1f)),Size(size.width*i*.2f,size.height*i*.2f),style=Stroke(1.dp.toPx()))
   drawRoundRect(Brush.linearGradient(listOf(BLUE,PURPLE)),center-Offset(20.dp.toPx(),20.dp.toPx()),Size(40.dp.toPx(),40.dp.toPx()),CornerRadius(10.dp.toPx()))
  }
  visible.forEachIndexed{i,o->val x=when(i){1->width-cardWidth;3->0f;else->centerX-cardWidth/2};val y=when(i){0->8f;2->280f;else->centerY-46f}
   Surface(Modifier.offset(x.dp,y.dp).width(cardWidth.dp).heightIn(min=92.dp).clickable{onSelect(o)},color=if(o.id==active)Color(0xFF132C48)else PANEL,shape=RoundedCornerShape(16.dp),shadowElevation=6.dp){
    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Icon(o.icon,null,tint=CYAN,modifier=Modifier.size(24.dp));Text(o.title,color=MAIN,fontSize=14.sp,maxLines=2);Text(o.sub,color=DIM,fontSize=12.sp)}
   }
  }
 }
}

@Composable fun ContextPanel(screen:Int,current:Orb?,contextItems:Set<String>,onToggleContext:(Orb)->Unit){
 var inspect by remember(current?.id){mutableStateOf(false)}
 if(inspect && current?.record!=null)AlertDialog(onDismissRequest={inspect=false},confirmButton={TextButton({inspect=false}){Text("Close")}},title={Text(current.title)},text={Column(Modifier.verticalScroll(rememberScrollState())){ObjectInspector(current.record)}})
 Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PANEL).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text(current?.title.orEmpty(),color=MAIN,fontSize=20.sp,fontWeight=FontWeight.Bold)
  Text("${contextItems.size} library records selected for context",color=DIM,fontSize=14.sp)
  Button({current?.let{onToggleContext(it)}},Modifier.fillMaxWidth()){Text(if(current?.id in contextItems)"Remove from context" else "Add to context")}
  OutlinedButton({inspect=true},Modifier.fillMaxWidth()){Text("Inspect record")}
 }
}

@Composable fun Carousel(items:List<Orb>,onSelect:(Orb)->Unit){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){items.forEach{o->Surface(Modifier.width(140.dp).heightIn(min=64.dp).clickable{onSelect(o)},color=PANEL,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(o.icon,null,tint=o.accent,modifier=Modifier.size(20.dp));Text(o.title,color=DIM,fontSize=12.sp,maxLines=2)}}}}}
@Composable fun Knowledge(c:Context){
 var objects by remember{mutableStateOf(OpenMineObjectStore.all(c))}
 var query by remember{mutableStateOf("")}
 var editor by remember{mutableStateOf(false)}
 var status by remember{mutableStateOf("")}
 var selectedObject by remember{mutableStateOf<StrictObject?>(null)}
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
  val result=OpenMineObjectStore.import(c,raw)
  if(result.valid){objects=OpenMineObjectStore.all(c);status="IMPORTED + INDEXED: "+result.normalized!!.id;selectedObject=result.normalized}
  else status="IMPORT REJECTED: "+result.errors.take(3).joinToString(" · ")
 }
 if(editor){CreateObjectScreen(c,{objects=OpenMineObjectStore.all(c);editor=false;status="CREATED + INDEXED"},{editor=false}, {status=it}) ;return}
 LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  item{
   Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("ENGINEERING VAULT",color=MAIN,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("STRICT OBJECTS · VALIDATE · INDEX · RETRIEVE",color=DIM,fontSize=12.sp)}
    Button({editor=true},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)){Text("+ CREATE",fontSize=12.sp)}
   }
   Spacer(Modifier.height(6.dp))
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({picker.launch(arrayOf("text/*","application/octet-stream"))},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)){Text("IMPORT .OMD",fontSize=12.sp)}
    OutlinedButton({OpenMineObjectStore.rebuildIndex(c);objects=OpenMineObjectStore.all(c);status="INDEX REBUILT"},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)){Text("REBUILD INDEX",fontSize=12.sp)}
   }
   Spacer(Modifier.height(5.dp));OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("RETRIEVAL QUERY",fontSize=12.sp)},placeholder={Text("ollama android connection...",fontSize=12.sp)})
   if(status.isNotBlank())Text(status,color=if(status.contains("REJECTED"))Color(0xFFFF6B6B)else CYAN,fontSize=12.sp)
  }
  val shown=if(query.isBlank())objects else OpenMineObjectStore.search(c,query)
  if(shown.isEmpty())item{Text(if(objects.isEmpty())"Your library is empty. Create a labeled record or import a UTF-8 .omd file (up to 1 MiB). JSON, PDF and model weights are not supported imports." else "No matching knowledge. Try another keyword.",color=DIM,fontSize=14.sp)}
  items(shown){o->
   Surface(Modifier.fillMaxWidth().clickable{selectedObject=o},color=PANEL,shape=RoundedCornerShape(12.dp)){
    Column(Modifier.padding(11.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Verified,null,tint=CYAN,modifier=Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Column(Modifier.weight(1f)){Text(o.title,color=MAIN,fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text(o.id,color=DIM,fontSize=12.sp)};Text(o.status,color=CYAN,fontSize=12.sp)}
     Text(o.fields["OBJECT_SUMMARY"].orEmpty(),color=DIM,fontSize=12.sp,maxLines=2);Text("INDEXED · labeled chunks · exact-term retrieval",color=BLUE,fontSize=12.sp)
    }
   }
  }
  selectedObject?.let{o->item{ObjectInspector(o)}}
 }
}

@Composable fun ObjectInspector(o:StrictObject){
 Surface(Modifier.fillMaxWidth(),color=Color(0xCC071225),shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(11.dp)){
  Text("CANONICAL OBJECT",color=CYAN,fontSize=12.sp,fontWeight=FontWeight.Bold);Text(o.id,color=MAIN,fontSize=12.sp)
  Spacer(Modifier.height(5.dp));o.sections.forEach{(section,values)->Column(Modifier.padding(bottom=5.dp)){Text("["+section+"]",color=PURPLE,fontSize=12.sp,fontWeight=FontWeight.Bold);values.forEach{(k,v)->Text(k+": "+v,color=DIM,fontSize=12.sp,maxLines=3)}}}
 }}
}

@Composable fun CreateObjectScreen(c:Context,onDone:()->Unit,onCancel:()->Unit,onStatus:(String)->Unit){
 var type by remember{mutableStateOf("KNOWLEDGE")};var title by remember{mutableStateOf("")};var summary by remember{mutableStateOf("")};var tags by remember{mutableStateOf("")};var source by remember{mutableStateOf("")}
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
  item{Text("AI IN YOUR LIBRARY",color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Connect a real model server. Matching local records are sent to that endpoint as source context. Only library search can be executed. On-device GGUF loading is not installed.",color=DIM,fontSize=14.sp)}
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
@Composable fun Setting(t:String,s:String,v:Boolean,on:(Boolean)->Unit){Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),color=PANEL,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(t,color=MAIN,fontSize=12.sp);Text(s,color=DIM,fontSize=12.sp)};Switch(v,on)}}}
