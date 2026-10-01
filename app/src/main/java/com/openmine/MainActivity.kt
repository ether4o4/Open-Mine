package com.openmine

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
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
 val c=LocalContext.current; val p=remember{c.getSharedPreferences("open_mine",Context.MODE_PRIVATE)}
 var selected by remember{mutableIntStateOf(p.getInt("screen",0))}
 var animations by remember{mutableStateOf(p.getBoolean("animations",true))}
 var haptics by remember{mutableStateOf(p.getBoolean("haptics",true))}
 fun go(i:Int){selected=i;p.edit().putInt("screen",i).apply();if(haptics)(c as? android.app.Activity)?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)}
 MaterialTheme(colorScheme=darkColorScheme(background=BG,surface=PANEL,primary=CYAN,secondary=PURPLE)){
  Surface(Modifier.fillMaxSize(),color=BG){Column{Header();Row(Modifier.fillMaxSize()){
   Rail(selected,::go);Box(Modifier.weight(1f).fillMaxHeight()){
    when(selected){
     11->SettingsScreen(animations,{v->animations=v;p.edit().putBoolean("animations",v).apply()},haptics,{v->haptics=v;p.edit().putBoolean("haptics",v).apply()})
     3->Knowledge()
     4->ListScreen("SKILLS",listOf("Android Build Skill","Vault Verification","Local Model Setup","UI Composition"))
     5->ListScreen("MISSIONS",listOf("Build Open Mine","Verify Vault","Connect Local AI","Ship APK"))
     6->ListScreen("TOOLS",listOf("Terminal","File Inspector","Model Runner","Git Helper"))
     else->Orbit(selected,animations)
    }
   }
  }}}}
}

@Composable fun Header(){Row(Modifier.fillMaxWidth().height(62.dp).padding(9.dp),verticalAlignment=Alignment.CenterVertically){
 Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){
  Box(Modifier.size(31.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF15223B))){Canvas(Modifier.fillMaxSize()){
   val s=size.minDimension/2.8f;drawRect(Color(0xFFFFD438),Offset(2f,2f),Size(s,s));drawRect(CYAN,Offset(s+5,2f),Size(s,s))
   drawRect(PURPLE,Offset(2f,s+5),Size(s,s));drawRect(Color(0xFFFF4EC4),Offset(s+5,s+5),Size(s,s))
  }};Spacer(Modifier.width(8.dp));Column{Text("Open Mine",color=MAIN,fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text("AI WORKSPACE ENVIRONMENT",color=DIM,fontSize=8.sp,letterSpacing=1.sp)}
 }
 Pill("10:53:27","THU 2/19/2025");Spacer(Modifier.width(5.dp));Pill("74°F","CLEAR")
}}

@Composable fun Pill(a:String,b:String){Column(Modifier.clip(RoundedCornerShape(8.dp)).background(PANEL).padding(horizontal=9.dp,vertical=5.dp)){Text(a,color=MAIN,fontSize=10.sp);Text(b,color=DIM,fontSize=7.sp)}}

@Composable fun Rail(selected:Int,onSelect:(Int)->Unit){Column(Modifier.width(91.dp).fillMaxHeight().padding(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
 NAV.forEachIndexed{i,n->Row(Modifier.fillMaxWidth().height(41.dp).clip(RoundedCornerShape(8.dp)).background(if(i==selected)Color(0x332AD8FF)else Color.Transparent).clickable{onSelect(i)}.padding(6.dp),verticalAlignment=Alignment.CenterVertically){
 Icon(n.icon,null,tint=if(i==selected)CYAN else DIM,modifier=Modifier.size(17.dp));Spacer(Modifier.width(5.dp));Column{Text(n.name,color=if(i==selected)MAIN else DIM,fontSize=7.sp);Text("%02d".format(i+1),color=DIM,fontSize=6.sp)}
}}}}

@Composable fun Orbit(screen:Int,animations:Boolean){
 val pulse=if(animations)rememberInfiniteTransition(label="orbit").animateFloat(.72f,1f,infiniteRepeatable(tween(1600),RepeatMode.Reverse),label="pulse").value else 1f
 val label=NAV[screen].name
 val items=when(screen){
  0->listOf(Orb("Qwen 3.5 2B","LOCAL · GGUF",PURPLE,Icons.Default.Memory),Orb("Gemma 4 2B","LITE RT · ANDROID",BLUE,Icons.Default.Memory),Orb("Llama 3.2 3B","OLLAMA · Q4",CYAN,Icons.Default.Memory),Orb("Hermes 3 3B","TOOL CALLING",BLUE,Icons.Default.Build),Orb("RefinedToolCall","CODING · AGENT",PURPLE,Icons.Default.Code),Orb("Suno","MUSIC · CREATIVE",Color(0xFFFF5A8A),Icons.Default.MusicNote),Orb("Claude 3.5","API · CLOUD",Color(0xFFFF7D5A),Icons.Default.AutoAwesome),Orb("GPT-4o","API · CLOUD",Color.White,Icons.Default.Psychology),Orb("Mixtral 8x7B","EXPERIMENTAL",Color(0xFFFFC22E),Icons.Default.Memory))
  1->listOf(Orb("Open Mine","AI Workspace",CYAN,Icons.Default.Workspaces),Orb("NeverSoft","Agent Runtime",PURPLE,Icons.Default.AutoAwesome),Orb("File Organizer","Utilities",BLUE,Icons.Default.Folder),Orb("OSINT Tools","Research",CYAN,Icons.Default.Search),Orb("Ghost Key","File Explorer",PURPLE,Icons.Default.Key),Orb("MVE","Kernel",Color(0xFFFF4EC4),Icons.Default.Terminal))
  2->listOf(Orb("GitHub MCP","Dev Tools",Color.White,Icons.Default.Code),Orb("Google Drive","File Storage",BLUE,Icons.Default.Cloud),Orb("Dropbox","File Storage",CYAN,Icons.Default.Cloud),Orb("Notion","Docs",Color.White,Icons.Default.Description),Orb("Browser","Web",CYAN,Icons.Default.Language),Orb("Termux","Android",PURPLE,Icons.Default.Terminal))
  else->listOf(Orb("Engineering Vault","PROVEN KNOWLEDGE",CYAN,Icons.Default.Storage),Orb("Personal Notes","PRIVATE",Color(0xFFFFB24A),Icons.Default.Description),Orb("Music Knowledge","LYRICS · AUDIO",Color(0xFFFF5A8A),Icons.Default.MusicNote),Orb("Work Knowledge","VERIFIED",BLUE,Icons.Default.Build),Orb("MVE Docs","ARCHITECTURE",PURPLE,Icons.Default.Code),Orb("Device Data","TELEMETRY",CYAN,Icons.Default.PhoneAndroid))
 }
 Column(Modifier.fillMaxSize()){
  Box(Modifier.fillMaxWidth().weight(1f)){Canvas(Modifier.fillMaxSize()){
   val center=Offset(size.width/2,size.height*.39f);val r=min(size.width,size.height)*.31f
   for(k in 1..4)drawCircle(CYAN.copy(alpha=.08f),r*k/4,center,style=Stroke(1f))
   for(i in 0 until 32){val a=i*PI/16;val q=Offset(center.x+cos(a).toFloat()*r*1.23f,center.y+sin(a).toFloat()*r*1.23f);drawCircle(if(i%4==0)CYAN.copy(alpha=.55f)else BLUE.copy(alpha=.16f),if(i%4==0)2.5f else 1f,q)}
   drawCircle(CYAN.copy(alpha=.07f*pulse),r*.48f,center);drawCircle(BLUE.copy(alpha=.12f),r*.22f,center)
   drawRoundRect(Brush.linearGradient(listOf(Color(0xFF101D55),Color(0xFF14103D))),center-Offset(38f,38f),Size(76f,76f),CornerRadius(14.dp.toPx()),style=androidx.compose.ui.graphics.drawscope.Fill)
   drawRoundRect(CYAN.copy(alpha=.7f),center-Offset(38f,38f),Size(76f,76f),CornerRadius(14.dp.toPx()),style=Stroke(2f))
   rotate(-45f,center){drawRoundRect(PURPLE.copy(alpha=.85f),center-Offset(18f,18f),Size(36f,36f),CornerRadius(7.dp.toPx()),style=Stroke(4f))}
  };Column(Modifier.align(Alignment.Center).padding(top=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(label,color=MAIN,fontSize=12.sp);Text("01",color=BLUE,fontSize=22.sp);Text("WORKSPACE",color=DIM,fontSize=7.sp,letterSpacing=2.sp)}
   BoxWithConstraints(Modifier.fillMaxSize()){val cx=maxWidth.value/2;val cy=maxHeight.value*.39f;val rr=minOf(maxWidth.value,maxHeight.value)*.29f
    items.forEachIndexed { i, o ->
     val a = Math.toRadians(-90 + i * 360.0 / items.size)
     val x = cx + cos(a) * rr - 58
     val y = cy + sin(a) * rr - 34
     Surface(
      Modifier.offset(x.dp, y.dp).width(116.dp).height(68.dp)
       .clip(RoundedCornerShape(10.dp)).clickable{},
      color=Color(0xDD091526), shadowElevation=7.dp
     ){
      Column(Modifier.padding(7.dp)){
       Row(verticalAlignment=Alignment.CenterVertically){
        Icon(o.icon,null,tint=o.accent,modifier=Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text("%02d".format(i+1),color=DIM,fontSize=7.sp)
       }
       Text(o.title,color=MAIN,fontSize=9.sp,fontWeight=FontWeight.SemiBold)
       Text(o.sub,color=DIM,fontSize=6.sp)
      }
     }
    }}
   }
  }
  ContextPanel(screen);Carousel(items.take(6))
 }
}

@Composable fun ContextPanel(screen:Int){val title=when(screen){0->"Qwen 3.5 2B";1->"Open Mine";2->"GitHub MCP";else->"Engineering Vault"};val sub=when(screen){0->"Abliterated · GGUF · Local";1->"Android AI Workspace Environment";2->"Repository & Development Tools";else->"Project Specs & Documentation"}
 Column(Modifier.fillMaxWidth().padding(7.dp).clip(RoundedCornerShape(14.dp)).background(PANEL).padding(12.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,color=MAIN,fontSize=20.sp,fontWeight=FontWeight.Bold);Text(sub,color=DIM,fontSize=9.sp)};Icon(Icons.Default.AutoAwesome,null,tint=CYAN,modifier=Modifier.size(36.dp))}
  Spacer(Modifier.height(7.dp));Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){listOf("OVERVIEW","CONTEXT","TOOLS","RELATED").forEach{Text(it,color=if(it=="OVERVIEW")CYAN else DIM,fontSize=7.sp)}}
  HorizontalDivider(color=Color(0x334C78FF));Spacer(Modifier.height(5.dp));Text("Active workspace object. Inspect it, add it to context, or open the full object view.",color=DIM,fontSize=8.sp,lineHeight=11.sp)
  Spacer(Modifier.height(6.dp));Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()){Button({},Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=CYAN.copy(alpha=.18f),contentColor=CYAN),shape=RoundedCornerShape(8.dp)){Text("ADD TO CONTEXT",fontSize=8.sp)};OutlinedButton({},Modifier.width(48.dp),shape=RoundedCornerShape(8.dp)){Text("…")}}
 }
}

@Composable fun Carousel(items:List<Orb>){Row(Modifier.fillMaxWidth().height(61.dp).padding(6.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){items.forEach{o->Surface(Modifier.weight(1f).fillMaxHeight(),color=PANEL,shape=RoundedCornerShape(8.dp)){Column(Modifier.padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(o.icon,null,tint=o.accent,modifier=Modifier.size(17.dp));Text(o.title,color=DIM,fontSize=5.sp,maxLines=1)}}}}}

@Composable fun ListScreen(title:String,items:List<String>){LazyColumn(Modifier.fillMaxSize().padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text(title,color=MAIN,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Reusable Open Mine workspace objects",color=DIM,fontSize=10.sp)};items(items){x->Surface(Modifier.fillMaxWidth(),color=PANEL,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AutoAwesome,null,tint=CYAN);Spacer(Modifier.width(9.dp));Text(x,color=MAIN,fontSize=13.sp)}}}}}

@Composable fun Knowledge(){val data=listOf("Engineering Vault","Ollama Android connection","OneHitWonder architecture","Open Mine UI system","Verified Termux build recipe");LazyColumn(Modifier.fillMaxSize().padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text("ENGINEERING VAULT",color=MAIN,fontSize=23.sp,fontWeight=FontWeight.Bold);Text("Reusable, verified knowledge for future context",color=DIM,fontSize=10.sp)};items(data){x->Surface(Modifier.fillMaxWidth(),color=PANEL,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(13.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Verified,null,tint=CYAN);Spacer(Modifier.width(8.dp));Text(x,color=MAIN,fontSize=12.sp,fontWeight=FontWeight.SemiBold)};Text("PROVEN · searchable · reusable",color=CYAN,fontSize=7.sp)}}}}}

@Composable fun SettingsScreen(a:Boolean,setA:(Boolean)->Unit,h:Boolean,setH:(Boolean)->Unit){Column(Modifier.fillMaxSize().padding(15.dp)){Text("SETTINGS",color=MAIN,fontSize=25.sp,fontWeight=FontWeight.Bold);Text("Open Mine workspace configuration",color=DIM,fontSize=10.sp);Spacer(Modifier.height(14.dp));Setting("Animations","Orbital motion and interface transitions",a,setA);Setting("Haptic feedback","Touch confirmation for navigation",h,setH);Setting("Proven knowledge only","Prefer verified vault entries for context",true,{})}}
@Composable fun Setting(t:String,s:String,v:Boolean,on:(Boolean)->Unit){Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),color=PANEL,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(t,color=MAIN,fontSize=12.sp);Text(s,color=DIM,fontSize=8.sp)};Switch(v,on)}}}
