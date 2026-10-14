package dev.wearbrdgeadb

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import rikka.shizuku.Shizuku
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class MainActivity : Activity() {
 private lateinit var status: TextView
 private var language="en"
 private val localizedViews=ArrayList<Pair<TextView,String>>()
 private fun localize(view:TextView,english:String){localizedViews.add(view to english);view.text=tr(english)}
 private fun tr(value:String):String{if(language!="ru")return value;return when(value){
  "Watch status"->"Состояние часов";"Shell access and phone link"->"Доступ к shell и связь с телефоном";"SETUP & DIAGNOSTICS"->"НАСТРОЙКА И ДИАГНОСТИКА";"Shizuku permission"->"Разрешение Shizuku";"Shared storage access"->"Доступ к памяти";"Nearby Wi-Fi permission"->"Разрешение Nearby Wi-Fi";"Check shell identity · id"->"Проверить UID shell · id";"Shizuku is unavailable. Start the Shizuku server, then retry."->"Shizuku недоступен. Запустите сервер Shizuku и повторите.";"Shizuku unavailable"->"Shizuku недоступен";"Shizuku permission denied. Grant it first."->"Нет разрешения Shizuku. Сначала выдайте его.";"Binding shell UserService…"->"Подключение shell UserService…";"Phone link: disconnected"->"Телефон: нет связи";"Phone link: connected"->"Телефон: подключён";else->value}}
 private val args by lazy { Shizuku.UserServiceArgs(ComponentName(this, ShellUserService::class.java)).version(2).daemon(false).processNameSuffix("wearbridge") }
 private var backend: ICmdService? = null
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
 private val connection = object : ServiceConnection {
  override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { backend = ICmdService.Stub.asInterface(binder); runId() }
  override fun onServiceDisconnected(name: ComponentName?) { backend = null; refresh() }
 }
 private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  language=getSharedPreferences("wearbridge_ui",MODE_PRIVATE).getString("language","en") ?: "en"
  Shizuku.addRequestPermissionResultListener(permissionListener)
  window.statusBarColor=android.graphics.Color.TRANSPARENT
  window.navigationBarColor=android.graphics.Color.TRANSPARENT
  @Suppress("DEPRECATION")
  run { window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION }
  val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(18)); setBackgroundColor(color(R.color.wb_canvas)) }
  val scroll=ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; addView(body) }
  scroll.setOnApplyWindowInsetsListener { view,insets -> view.setPadding(dp(18)+insets.systemWindowInsetLeft,dp(18)+insets.systemWindowInsetTop,dp(18)+insets.systemWindowInsetRight,dp(18)+insets.systemWindowInsetBottom);insets }
  val brand=TextView(this).apply { text="WEARBRIDGE"; textSize=11f; letterSpacing=.14f; typeface=android.graphics.Typeface.DEFAULT_BOLD; setTextColor(color(R.color.wb_primary)) }
  val heading=TextView(this).apply { text="Watch status"; textSize=23f; typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL); setTextColor(color(R.color.wb_on_surface)); setPadding(0,dp(4),0,dp(3)) }
  body.addView(brand);localize(heading,"Watch status");body.addView(heading);body.addView(TextView(this).apply{textSize=13f;setTextColor(color(R.color.wb_on_surface_variant));setPadding(0,0,0,dp(16));localize(this,"Shell access and phone link")})
  status=TextView(this).apply { textSize=13f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(color(R.color.wb_on_surface)); setPadding(dp(14),dp(14),dp(14),dp(14)); background=rounded(color(R.color.wb_surface),dp(16)); accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
  body.addView(status)
  body.addView(TextView(this).apply{textSize=10f;letterSpacing=.1f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setTextColor(color(R.color.wb_on_surface_variant));setPadding(0,dp(18),0,dp(8));localize(this,"SETUP & DIAGNOSTICS")})
  body.addView(watchButton("Language / Язык") { language=if(language=="ru")"en" else "ru";getSharedPreferences("wearbridge_ui",MODE_PRIVATE).edit().putString("language",language).apply();localizedViews.forEach{(view,english)->view.text=tr(english)};refresh() })
  val perm=watchButton("Shizuku permission") { if(!Shizuku.pingBinder()) status.text=tr("Shizuku is unavailable. Start the Shizuku server, then retry.") else if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1001) else refresh() }
  val storage=watchButton("Shared storage access") { requestStoragePermission() }
  val nearby=watchButton("Nearby Wi-Fi permission") { requestNearbyWifiPermission() }
  val id=watchButton("Check shell identity · id") { runId() }
  body.addView(perm);body.addView(storage);body.addView(nearby);body.addView(id)
  setContentView(scroll);scroll.requestApplyInsets();refresh();scope.launch { try { val nodes=Wearable.getNodeClient(this@MainActivity).connectedNodes.await(); if(nodes.isEmpty()) status.append("\n"+tr("Phone link: disconnected")) else status.append("\n"+tr("Phone link: connected")) } catch(e:Exception) { status.append("\nData Layer: ${e.message}") } }
 }
 private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
 private fun color(id:Int)=getColor(id)
 private fun rounded(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=radius.toFloat()}
 private fun watchButton(title:String,onClick:()->Unit)=Button(this).apply{localize(this,title);textSize=13f;isAllCaps=false;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL);setTextColor(color(R.color.wb_on_surface));minHeight=dp(50);minimumHeight=dp(50);background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(color(R.color.wb_outline)),rounded(color(R.color.wb_surface_raised),dp(14)),rounded(android.graphics.Color.WHITE,dp(14)));setOnClickListener{onClick()}}
 private fun requestStoragePermission() {
  val needed=arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
  val missing=needed.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
  if(missing.isEmpty()){refresh();return}
  requestPermissions(missing.toTypedArray(),1002)
 }
 private fun requestNearbyWifiPermission() {
  val permission="android.permission.NEARBY_WIFI_DEVICES"
  if(Build.VERSION.SDK_INT<33 || checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED){refresh();return}
  requestPermissions(arrayOf(permission),1003)
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==1002||requestCode==1003)refresh()}
 private fun refresh() { if(!::status.isInitialized)return
  val available=Shizuku.pingBinder(); val granted=available && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED
  val uid=if(available && granted) Shizuku.getUid().toString() else "unavailable"
  val storageGranted=checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
  val nearbyGranted=Build.VERSION.SDK_INT<33 || checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")==PackageManager.PERMISSION_GRANTED
  status.text=if(language=="ru")"SHIZUKU     ${if(available)"ДОСТУПЕН" else "НЕДОСТУПЕН"}\nРАЗРЕШЕНИЕ  ${if(granted)"ЕСТЬ" else "НЕТ"}\nUID СЕРВИСА $uid${if(uid=="0")" · ROOT" else ""}\nUSER SERVICE ${if(backend==null)"НЕАКТИВЕН" else "АКТИВЕН"}\nПАМЯТЬ     ${if(storageGranted)"ДОСТУПНА" else "НЕТ ДОСТУПА"}\nNEARBY WI-FI ${if(nearbyGranted)"ДОСТУПЕН" else "НЕТ ДОСТУПА"}" else "SHIZUKU     ${if(available)"AVAILABLE" else "UNAVAILABLE"}\nPERMISSION  ${if(granted)"GRANTED" else "DENIED"}\nBACKEND UID $uid${if(uid=="0")" · ROOT" else ""}\nUSER SERVICE ${if(backend==null)"INACTIVE" else "ACTIVE"}\nSTORAGE     ${if(storageGranted)"GRANTED" else "NOT GRANTED"}\nNEARBY WI-FI ${if(nearbyGranted)"GRANTED" else "NOT GRANTED"}"
 }
 private fun runId() {
  if(!Shizuku.pingBinder()) { status.text=tr("Shizuku unavailable"); return }
  if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) { status.text=tr("Shizuku permission denied. Grant it first."); return }
  try {
   if(backend!=null) { status.text="id\n${backend!!.exec("id",false)}"; return }
   status.text=tr("Binding shell UserService…")
   Shizuku.bindUserService(args,connection)
  } catch(t:Throwable) { status.text="UserService error: ${t.message}" }
 }
 override fun onResume(){ super.onResume(); refresh() }
 override fun onDestroy(){ scope.cancel(); Shizuku.removeRequestPermissionResultListener(permissionListener); try { Shizuku.unbindUserService(args,connection,true) } catch(_:Exception){}; super.onDestroy() }
}
