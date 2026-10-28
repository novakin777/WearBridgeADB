package dev.wearbrdgeadb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import rikka.shizuku.Shizuku
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class DataLayerListenerService : WearableListenerService() {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private val bindMutex=Mutex()
 private val activeOperations=AtomicInteger(0)
 private val backendBindExecutor=java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task,"WearBridge-Shizuku-Bind").apply{isDaemon=true} }
 private val backendBindTimeouts=java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"WearBridge-Shizuku-Bind-Timeout").apply{isDaemon=true} }
 private val activePtyFds=java.util.concurrent.ConcurrentHashMap<String,Int>()
 private val activePtyNodes=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
 private data class RootProcess(val process:java.lang.Process,val pidFile:java.io.File,@Volatile var processGroupId:Int?=null)
 private val rootProcesses=java.util.concurrent.ConcurrentHashMap<String,RootProcess>()
 private val rootExecutionSlots=java.util.concurrent.Semaphore(MAX_ROOT_COMMANDS)
 private val apiTransfers=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
 private val cancelledExecIds=java.util.concurrent.ConcurrentHashMap<String,Long>()
 @Volatile private var ptyWakeLock:PowerManager.WakeLock?=null
 private data class ExecWakeLock(val wakeLock:PowerManager.WakeLock,val acquiredAtElapsedMs:Long)
 private val execWakeLockGuard=Any()
 private val execWakeLocks=HashMap<String,ExecWakeLock>()
 @Volatile private var remote: ICmdService?=null
 @Volatile private var pending: CompletableDeferred<ICmdService>?=null
 @Volatile private var pendingBindRequestId:String?=null
 private val args by lazy { Shizuku.UserServiceArgs(ComponentName(this,ShellUserService::class.java)).version(5).tag("wearbridge-exec").daemon(false).processNameSuffix("wearbridge") }
 private val connection=object: ServiceConnection {
  override fun onServiceConnected(n:ComponentName?, b:IBinder?) {
   val service=ICmdService.Stub.asInterface(b)
   remote=service
   val gate=pending;pending=null
   val request=pendingBindRequestId?:"backend";pendingBindRequestId=null
   RequestTrace.record(this@DataLayerListenerService,request,"backend_callback_connected")
   gate?.complete(service)
  }
  override fun onServiceDisconnected(n:ComponentName?) {
   remote=null
   val gate=pending;pending=null
   val request=pendingBindRequestId?:"backend";pendingBindRequestId=null
   RequestTrace.record(this@DataLayerListenerService,request,"backend_callback_disconnected")
   gate?.completeExceptionally(IllegalStateException("Shizuku UserService disconnected while binding"))
  }
 }

 override fun onMessageReceived(e:MessageEvent) { super.onMessageReceived(e)
  if(e.path=="/diagnostics/trace/request") { if(BuildConfig.REQUEST_TRACE_ENABLED)sendRequestTrace(e); return }
  if(e.path=="/exec/cancel") { cancelExecution(String(e.data,Charsets.UTF_8)); return }
  if(e.path=="/terminal/resize") { resizeActivePty(e.sourceNodeId,e.data); return }
  if(e.path=="/file-transfer/list") { handleFileListing(e); return }
  if(e.path=="/file-transfer/mkdir") { handleRemoteMkdir(e); return }
  if(e.path=="/file-transfer/delete") { handleRemoteDelete(e); return }
  if(e.path=="/file-transfer/upload" || e.path=="/file-transfer/download") { handleWifiFileRequest(e); return }
  if(e.path!="/exec" && e.path!="/exec/api") return
  val apiRequest=e.path=="/exec/api"
  val parts=String(e.data,Charsets.UTF_8).split('\n',limit=3); if(parts.size<2)return
  val request=parts[0]; val root=parts.size>=3 && parts[1]=="root"; val command=if(parts.size>=3)parts[2] else parts[1]; val node=e.sourceNodeId
  val power=getSystemService(PowerManager::class.java)
  val powerState="deviceIdle=${if(android.os.Build.VERSION.SDK_INT>=23)power?.isDeviceIdleMode else "unknown"} interactive=${power?.isInteractive ?: "unknown"} batteryExempt=${if(android.os.Build.VERSION.SDK_INT>=23)power?.isIgnoringBatteryOptimizations(packageName) else "unknown"}"
  RequestTrace.record(this,request,"message_received","path=${e.path} payloadBytes=${e.data.size} profile=${if(root)"root" else "shell"} $powerState")
  pruneCancelledExecIds()
  if(consumeCancellation(request)){RequestTrace.record(this,request,"cancelled_before_start");scope.launch{try{sendExecResult(node,request,"exitCode=130\nstdout:\n\nstderr:\nCancelled before start",apiRequest)}catch(_:Throwable){}};return}
  RequestTrace.record(this,request,"foreground_retain_start")
  retainForeground()
  RequestTrace.record(this,request,"foreground_retained")
  try {
   acquireExecWakeLock(request)
  } catch(t:Throwable) {
   RequestTrace.record(this,request,"exec_wakelock_acquire_failed",t.javaClass.simpleName)
   scope.launch {
    try { sendExecResult(node,request,"exitCode=1\nstdout:\n\nstderr:\nCould not keep watch CPU awake: ${t.message?:t.javaClass.simpleName}",apiRequest) }
    catch(sendError:Throwable) { RequestTrace.record(this@DataLayerListenerService,request,"reply_send_failed",RequestTrace.failureDetail(sendError)) }
    finally { cancelledExecIds.remove(request); releaseForeground() }
   }
   return
  }
  scope.launch {
   try {
    val reply=try {
     if(root){RequestTrace.record(this@DataLayerListenerService,request,"root_exec_start");val value=execAsKernelSuApp(request,command);RequestTrace.record(this@DataLayerListenerService,request,"exec_finished","replyBytes=${value.toByteArray(Charsets.UTF_8).size}");value}
     else if(!Shizuku.pingBinder()) {RequestTrace.record(this@DataLayerListenerService,request,"shizuku_unavailable");"Shizuku unavailable"}
     else if(Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED) {RequestTrace.record(this@DataLayerListenerService,request,"shizuku_permission_denied");"Shizuku permission denied"}
     else {
      RequestTrace.record(this@DataLayerListenerService,request,"backend_wait_start")
      val backend=obtainBackend(request)
      RequestTrace.record(this@DataLayerListenerService,request,"backend_ready")
      if(consumeCancellation(request)) {RequestTrace.record(this@DataLayerListenerService,request,"cancelled_after_backend_ready_before_exec");"exitCode=130\nstdout:\n\nstderr:\nCancelled before start"}
      else {RequestTrace.record(this@DataLayerListenerService,request,"exec_start");val value=backend.execJob(request,command,false);RequestTrace.record(this@DataLayerListenerService,request,"exec_finished","replyBytes=${value.toByteArray(Charsets.UTF_8).size} exitCode=${Regex("exitCode=(-?\\d+)").find(value)?.groupValues?.getOrNull(1)?:"unknown"}");value}
     }
    } catch(t:Throwable) { RequestTrace.record(this@DataLayerListenerService,request,"exec_failed",t.javaClass.simpleName);"ERROR: ${t.message}" }
    RequestTrace.record(this@DataLayerListenerService,request,"reply_send_start","replyBytes=${reply.toByteArray(Charsets.UTF_8).size} chunked=$apiRequest")
    sendExecResult(node,request,reply,apiRequest)
    RequestTrace.record(this@DataLayerListenerService,request,"reply_send_done")
   } catch(t:Throwable) { RequestTrace.record(this@DataLayerListenerService,request,"reply_send_failed",RequestTrace.failureDetail(t));Log.e(TAG,"Normal request response failed",t) }
   finally { cancelledExecIds.remove(request); releaseExecWakeLock(request); releaseForeground() }
  }
 }

 private fun sendRequestTrace(event:MessageEvent) {
  val requestId=String(event.data,Charsets.US_ASCII)
  if(requestId.length !in 1..64 || requestId.any { it !in '0'..'9' && it !in 'a'..'z' && it !in 'A'..'Z' && it!='-' }) return
  val trace=RequestTrace.read(this).ifBlank { "No watch trace entries yet" }
  val payload="$requestId\n$trace".toByteArray(Charsets.UTF_8)
  scope.launch {
   try {
    Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(event.sourceNodeId,"/diagnostics/trace/result",payload).await()
    RequestTrace.record(this@DataLayerListenerService,requestId,"trace_export_sent","bytes=${payload.size}")
   } catch(t:Throwable) {
    RequestTrace.record(this@DataLayerListenerService,requestId,"trace_export_failed",RequestTrace.failureDetail(t))
    Log.w(TAG,"Watch trace response failed",t)
   }
  }
 }

 private suspend fun sendExecResult(node:String,request:String,reply:String,chunked:Boolean){
  val client=Wearable.getMessageClient(this)
  if(!chunked){val payload="[$request]\n$reply\n".toByteArray();RequestTrace.record(this,request,"single_result_send_start","messageBytes=${payload.size}");client.sendMessage(node,"/result",payload).await();return}
  val bytes=reply.toByteArray(Charsets.UTF_8);val chunkSize=48*1024;val total=maxOf(1,(bytes.size+chunkSize-1)/chunkSize)
  for(index in 0 until total){
   val start=index*chunkSize;val end=minOf(bytes.size,start+chunkSize)
   val header="$request\n$index\n$total\n".toByteArray(Charsets.US_ASCII)
   val payload=ByteArray(header.size+end-start);header.copyInto(payload);if(end>start)bytes.copyInto(payload,header.size,start,end)
   RequestTrace.record(this,request,"result_chunk_send_start","index=$index total=$total bytes=${payload.size}")
   client.sendMessage(node,"/result/chunk",payload).await()
   RequestTrace.record(this,request,"result_chunk_send_ack","index=$index total=$total")
  }
 }

 override fun onDataChanged(events:DataEventBuffer) { super.onDataChanged(events)
  try { events.forEach { event ->
   if(event.type!=DataEvent.TYPE_CHANGED)return@forEach
   val path=event.dataItem.uri.path?:return@forEach
   if(!path.startsWith("/wearbridge/api-transfer/request/"))return@forEach
   val data=DataMapItem.fromDataItem(event.dataItem).dataMap
   val id=data.getString("id")?:path.substringAfterLast('/')
   if(!isUuid(id)||!apiTransfers.add(id))return@forEach
   handleApiTransfer(data,id)
  } } finally { events.release() }
 }

 private fun handleApiTransfer(request:DataMap,id:String){
  val operation=request.getString("operation")?:"";val path=request.getString("path")?:"";val root=request.getString("mode")=="root"
  if(operation !in setOf("upload","download")||!validTransferPath(path)) { publishTransferError(id,"Invalid operation or absolute path");apiTransfers.remove(id);return }
  try{retainForeground()}catch(t:Throwable){apiTransfers.remove(id);Log.e(TAG,"Could not retain file transfer service",t);publishTransferError(id,t.message?:"Could not start file transfer service");return}
  scope.launch {
   val response=PutDataMapRequest.create("/wearbridge/api-transfer/response/$id")
   try {
    response.dataMap.putString("id",id)
    if(operation=="upload"){
     val asset=request.getAsset("asset")?:throw java.io.IOException("upload request has no asset")
     val assetResponse=Wearable.getDataClient(this@DataLayerListenerService).getFdForAsset(asset).await()
     val stage=java.io.File(cacheDir,".wearbridge-api-$id.part")
     try{
      assetResponse.getInputStream().use{input->stage.outputStream().use{output->input.copyTo(output,64*1024)}}
      val expected=request.getLong("size",-1L);if(expected<0||stage.length()!=expected)throw java.io.IOException("uploaded asset size mismatch")
      val code:Int;val message:String
      if(root){val result=runFileCommand(true,"cp -f ${quote(stage.absolutePath)} ${quote(path)}");code=result.first;message=result.second}
      else{requireShizukuForTransfer();val descriptor=ParcelFileDescriptor.open(stage,ParcelFileDescriptor.MODE_READ_ONLY);try{val result=obtainBackend().copyToFile(descriptor,path);code=Regex("exitCode=(-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()?:1;message=result.substringAfter('\n',"")}finally{descriptor.close()}}
      response.dataMap.putBoolean("ok",code==0);response.dataMap.putString("message",message);response.dataMap.putLong("size",stage.length())
     }finally{assetResponse.release();stage.delete()}
    }else{
     val stage=java.io.File(cacheDir,".wearbridge-api-$id.part")
     val code:Int;val message:String
     if(root){val process=startFileCommand(true,"cat ${quote(path)}");val errors=ByteArrayOutputStream();val errorThread=Thread{try{process.errorStream.copyTo(errors)}catch(_:Exception){}}.apply{start()};code=try{process.inputStream.use{input->stage.outputStream().use{output->input.copyTo(output,64*1024)}};process.waitFor()}catch(t:Throwable){process.destroy();throw t};errorThread.join();message=errors.toByteArray().toString(Charsets.UTF_8)}
     else{requireShizukuForTransfer();val descriptor=ParcelFileDescriptor.open(stage,ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY);try{val result=obtainBackend().copyFromFile(path,descriptor);code=Regex("exitCode=(-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()?:1;message=result.substringAfter('\n',"")}finally{try{descriptor.close()}catch(_:Exception){}}}
     response.dataMap.putBoolean("ok",code==0);response.dataMap.putString("message",message);response.dataMap.putLong("size",if(code==0)stage.length() else 0L)
     if(code==0){val fd=ParcelFileDescriptor.open(stage,ParcelFileDescriptor.MODE_READ_ONLY);try{response.dataMap.putAsset("asset",Asset.createFromFd(fd));Wearable.getDataClient(this@DataLayerListenerService).putDataItem(response.asPutDataRequest().setUrgent()).await()}finally{fd.close()};stage.delete();return@launch}
     stage.delete()
    }
    Wearable.getDataClient(this@DataLayerListenerService).putDataItem(response.asPutDataRequest().setUrgent()).await()
   }catch(t:Throwable){
    Log.e(TAG,"Data Layer file transfer $id failed",t)
    try{response.dataMap.putBoolean("ok",false);response.dataMap.putString("message",t.message?:t.javaClass.simpleName);Wearable.getDataClient(this@DataLayerListenerService).putDataItem(response.asPutDataRequest().setUrgent()).await()}catch(send:Throwable){Log.e(TAG,"Could not return Data Layer file transfer error",send)}
   }finally{
    try{Wearable.getDataClient(this@DataLayerListenerService).deleteDataItems(android.net.Uri.parse("wear://*/wearbridge/api-transfer/request/$id"),DataClient.FILTER_LITERAL).await()}catch(_:Throwable){}
    apiTransfers.remove(id);releaseForeground()
   }
 }
 }

 private fun publishTransferError(id:String,message:String){
  scope.launch{try{val response=PutDataMapRequest.create("/wearbridge/api-transfer/response/$id");response.dataMap.putString("id",id);response.dataMap.putBoolean("ok",false);response.dataMap.putString("message",message);Wearable.getDataClient(this@DataLayerListenerService).putDataItem(response.asPutDataRequest().setUrgent()).await()}catch(t:Throwable){Log.e(TAG,"Could not publish file transfer error",t)}}
 }

 private fun validTransferPath(path:String)=validFilePath(path)&&!path.split('/').any{it==".."||it=="."}

 private fun requireShizukuForTransfer(){if(!Shizuku.pingBinder())throw IllegalStateException("Shizuku unavailable");if(Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw SecurityException("Shizuku permission denied")}

 private fun resizeActivePty(node:String,data:ByteArray){
  val fd=activePtyFds[node]?:return
  try{val request=JSONObject(String(data,Charsets.UTF_8));val rows=request.optInt("rows",0);val cols=request.optInt("columns",0);if(rows in 2..300&&cols in 20..500){val result=Pty.resizePty(fd,rows,cols);if(result!=0)Log.w(TAG,"PTY resize failed for $node: $result ($rows x $cols)")}}
  catch(t:Throwable){Log.w(TAG,"Invalid PTY resize from $node",t)}
 }

 private fun handleFileListing(event:MessageEvent) {
  val request=try { JSONObject(String(event.data,Charsets.UTF_8)) } catch(_:Throwable) { return }
  val id=request.optString("id");val path=request.optString("path");val root=request.optBoolean("root",false);val offset=request.optInt("offset",0).coerceIn(0,100000)
  val response=JSONObject().put("id",id)
  try {
   if(!isUuid(id)||!validDirectoryPath(path))throw IllegalArgumentException("Invalid directory path")
   retainForeground()
   scope.launch {
    try {
     val script="""test -d ${quote(path)} && test -r ${quote(path)} || exit 4
count=0
shown=0
for p in ${quote(path)}/* ${quote(path)}/.[!.]* ${quote(path)}/..?*; do
  [ -e "${'$'}p" ] || [ -L "${'$'}p" ] || continue
  count=${'$'}((count+1)); [ "${'$'}count" -gt $offset ] || continue
  shown=${'$'}((shown+1)); [ "${'$'}shown" -le 46 ] || break
  name=${'$'}{p##*/}
  if [ -d "${'$'}p" ]; then kind=D; size=0
  elif [ -f "${'$'}p" ]; then kind=F; size=${'$'}(stat -c %s "${'$'}p" 2>/dev/null || echo 0)
  else continue; fi
  modified=${'$'}(stat -c %Y "${'$'}p" 2>/dev/null || echo 0)
  printf '%s\000%s\000%s\000%s\000' "${'$'}kind" "${'$'}name" "${'$'}size" "${'$'}modified"
done"""
     val process=startFileCommand(root,script)
     val stdout=ByteArrayOutputStream();val stderr=ByteArrayOutputStream()
     val outTruncated=java.util.concurrent.atomic.AtomicBoolean();val errTruncated=java.util.concurrent.atomic.AtomicBoolean()
     val outThread=Thread{try{copyBounded(process.inputStream,stdout,outTruncated)}catch(_:Exception){}}.apply{start()}
     val errThread=Thread{try{copyBounded(process.errorStream,stderr,errTruncated)}catch(_:Exception){}}.apply{start()}
     val code=process.waitFor();joinProcessReader(outThread,process.inputStream);joinProcessReader(errThread,process.errorStream)
     if(code!=0)throw java.io.IOException(stderr.toByteArray().toString(Charsets.UTF_8).ifBlank{"Cannot read folder (exit $code)"})
     val fields=stdout.toByteArray().toString(Charsets.UTF_8).split('\u0000')
     val entries=org.json.JSONArray();var i=0
     while(i+3<fields.size && entries.length()<46){
      val kind=fields[i++];val name=fields[i++]
      if(name.isEmpty()||name=="."||name=="..")continue
      val directory=kind=="D";val size=fields[i++].toLongOrNull()?:0L;val modified=fields[i++].toLongOrNull()?:0L
      entries.put(JSONObject().put("name",name).put("directory",directory).put("size",size).put("modified",modified))
     }
     val hasMore=entries.length()>45
     if(hasMore)entries.remove(45)
     response.put("entries",entries).put("hasMore",hasMore)
    } catch(t:Throwable) { response.put("error",t.message?:t.javaClass.simpleName) }
    finally {
     try{Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(event.sourceNodeId,"/file-transfer/list-result",response.toString().toByteArray(Charsets.UTF_8)).await()}catch(t:Throwable){Log.e(TAG,"Folder listing response failed",t)}
     releaseForeground()
    }
   }
  } catch(t:Throwable) {
   response.put("error",t.message?:"Could not list folder")
   scope.launch{try{Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(event.sourceNodeId,"/file-transfer/list-result",response.toString().toByteArray(Charsets.UTF_8)).await()}catch(_:Throwable){}}
  }
 }

 private fun handleRemoteMkdir(event:MessageEvent){
  val request=try{JSONObject(String(event.data,Charsets.UTF_8))}catch(_:Throwable){return}
  val id=request.optString("id");val path=request.optString("path");val root=request.optBoolean("root",false)
  if(!isUuid(id)||!validDirectoryPath(path)){scope.launch{sendDirectoryResult(event.sourceNodeId,JSONObject().put("id",id).put("ok",false).put("error","Invalid folder path"))};return}
  try{retainForeground()}catch(t:Throwable){scope.launch{sendDirectoryResult(event.sourceNodeId,JSONObject().put("id",id).put("ok",false).put("error",t.message?:"Could not start watch service"))};return}
  scope.launch{
   try{val result=runFileCommand(root,"mkdir -p ${quote(path)}");sendDirectoryResult(event.sourceNodeId,JSONObject().put("id",id).put("ok",result.first==0).put("error",if(result.first==0)"" else result.second.ifBlank{"mkdir exited ${result.first}"}))}
   catch(t:Throwable){sendDirectoryResult(event.sourceNodeId,JSONObject().put("id",id).put("ok",false).put("error",t.message?:"Could not create folder"))}
   finally{releaseForeground()}
  }
 }

 private suspend fun sendDirectoryResult(node:String,result:JSONObject){
  try{Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(node,"/file-transfer/mkdir-result",result.toString().toByteArray(Charsets.UTF_8)).await()}catch(t:Throwable){Log.e(TAG,"Folder creation response failed",t)}
 }

 private fun handleRemoteDelete(event:MessageEvent){
  val request=try{JSONObject(String(event.data,Charsets.UTF_8))}catch(_:Throwable){return}
  val id=request.optString("id");val paths=request.optJSONArray("paths");val root=request.optBoolean("root",false)
  val response=JSONObject().put("id",id);val deleted=org.json.JSONArray();val errors=org.json.JSONArray()
  if(!isUuid(id)||paths==null||paths.length()==0||paths.length()>200){response.put("error","Invalid deletion request");scope.launch{sendDeleteResult(event.sourceNodeId,response)};return}
  val requested=ArrayList<String>()
  for(i in 0 until paths.length()){
   val path=paths.optString(i)
   if(!validDeletablePath(path)){response.put("error","Refusing unsafe path: $path");scope.launch{sendDeleteResult(event.sourceNodeId,response)};return}
   requested.add(path)
  }
  try{retainForeground()}catch(t:Throwable){response.put("error",t.message?:"Could not start watch service");scope.launch{sendDeleteResult(event.sourceNodeId,response)};return}
  scope.launch{
   try{
    for(path in requested){
     val quoted=quote(path)
     val command="if [ -d $quoted ] && [ ! -L $quoted ]; then rm -rf -- $quoted; else rm -f -- $quoted; fi"
     try{val result=runFileCommand(root,command);if(result.first==0)deleted.put(path) else errors.put("$path: ${result.second.ifBlank{"rm exited ${result.first}"}.take(1000)}")}
     catch(t:Throwable){errors.put("$path: ${t.message?:t.javaClass.simpleName}".take(1000))}
    }
    response.put("deleted",deleted).put("errors",errors).put("ok",errors.length()==0)
   }catch(t:Throwable){response.put("error",t.message?:"Deletion failed")}
   finally{try{sendDeleteResult(event.sourceNodeId,response)}finally{releaseForeground()}}
  }
 }

 private suspend fun sendDeleteResult(node:String,result:JSONObject){
  try{Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(node,"/file-transfer/delete-result",result.toString().toByteArray(Charsets.UTF_8)).await()}catch(t:Throwable){Log.e(TAG,"Delete response failed",t)}
 }

 private fun handleWifiFileRequest(event:MessageEvent) {
  val request=try { JSONObject(String(event.data,Charsets.UTF_8)) } catch(t:Throwable) {
   sendFileReady(event.sourceNodeId, JSONObject().put("ok",false).put("error","Invalid transfer request")); return
  }
  val id=request.optString("id")
  val path=request.optString("path")
  val key=try { android.util.Base64.decode(request.getString("key"),android.util.Base64.NO_WRAP) } catch(_:Throwable) { byteArrayOf() }
  val root=request.optBoolean("root",false)
  val upload=event.path=="/file-transfer/upload"
  if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
   sendFileReady(event.sourceNodeId,JSONObject().put("id",id).put("ok",false).put("error","Grant Nearby Wi-Fi permission in WearBridgeADB on the watch first"));return
  }
  if(!isUuid(id) || key.size!=32 || !validFilePath(path)) {
   sendFileReady(event.sourceNodeId, JSONObject().put("id",id).put("ok",false).put("error","Invalid transfer id, key, or absolute file path")); return
  }
  try { retainForeground() } catch(t:Throwable) {
   sendFileReady(event.sourceNodeId, JSONObject().put("id",id).put("ok",false).put("error",t.message?:"Could not start foreground service")); return
  }
  scope.launch {
   var server:ServerSocket?=null
   var socket:Socket?=null
   try {
    val addr=wifiIpv4() ?: throw java.io.IOException("Watch is not connected to Wi-Fi")
    server=ServerSocket().apply { reuseAddress=false; bind(InetSocketAddress(addr,0),1); soTimeout=120_000 }
    sendFileReady(event.sourceNodeId,JSONObject().put("id",id).put("ok",true).put("host",addr.hostAddress).put("port",server.localPort))
    socket=server.accept().apply { soTimeout=20_000 }
    val input=DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output=DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    WifiFileProtocol.authenticateServer(input,output,key,id)
    socket.soTimeout=300_000
    if(upload) receiveWifiFile(input,output,key,id,path,root) else sendWifiFile(input,output,key,id,path,root)
   } catch(t:Throwable) {
    Log.e(TAG,"Wi-Fi file transfer $id failed",t)
    if(t !is CancellationException) sendFileReady(event.sourceNodeId,JSONObject().put("id",id).put("ok",false).put("error",t.message?:t.javaClass.simpleName))
   } finally {
    try { socket?.close() } catch(_:Exception){}; try { server?.close() } catch(_:Exception){}; releaseForeground()
   }
  }
 }

 private fun sendFileReady(node:String,payload:JSONObject) {
  scope.launch { try { Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(node,"/file-transfer/ready",payload.toString().toByteArray(Charsets.UTF_8)).await() } catch(t:Throwable) { Log.e(TAG,"File transfer response failed",t) } }
 }

 private fun sendApiStreamStatus(node:String,id:String,ok:Boolean,error:String?){
  val path=if(ok)"/stream/api-ready" else "/stream/api-error"
  val payload=JSONObject().put("sessionId",id).put("ok",ok).put("error",error).toString().toByteArray(Charsets.UTF_8)
  scope.launch{try{Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(node,path,payload).await()}catch(t:Throwable){Log.e(TAG,"API stream status delivery failed",t)}}
 }

 private fun wifiIpv4():Inet4Address? {
  val cm=getSystemService(ConnectivityManager::class.java) ?: return null
  for(network in cm.allNetworks) {
   val caps=cm.getNetworkCapabilities(network) ?: continue
   if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
   val props=cm.getLinkProperties(network) ?: continue
   val address=props.linkAddresses.firstOrNull { it.address is Inet4Address }?.address
   if(address is Inet4Address && !address.isLoopbackAddress) return address
  }
  return null
 }

 private suspend fun receiveWifiFile(input:DataInputStream,output:DataOutputStream,key:ByteArray,id:String,path:String,root:Boolean) {
  val stage=java.io.File.createTempFile(".wearbridge-$id-",".part",cacheDir)
  try {
   val reader=WifiFileProtocol.reader(input,key,id)
   stage.outputStream().use { stagedBytes ->
    while(true) {
     val record=reader.read()
     when(record.type) {
      WifiFileProtocol.DATA -> stagedBytes.write(record.payload)
      WifiFileProtocol.END -> { if(record.payload.isNotEmpty()) throw java.io.IOException("Invalid upload end record"); break }
      else -> throw java.io.IOException("Unexpected upload record")
     }
    }
   }
   val (code,message)=if(root) runFileCommand(true,"cp -f ${quote(stage.absolutePath)} ${quote(path)}") else {
    requireShizukuForTransfer()
    val descriptor=ParcelFileDescriptor.open(stage,ParcelFileDescriptor.MODE_READ_ONLY)
    try {
     val result=obtainBackend().copyToFile(descriptor,path)
     (Regex("exitCode=(-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()?:1) to result.substringAfter('\n',"")
    } finally { try{descriptor.close()}catch(_:Exception){} }
   }
   writeFileResult(output,key,id,code,message)
  } catch(cancelled:CancellationException) {
   throw cancelled
  } catch(t:Throwable) {
   val detail=(t.message?:t.javaClass.simpleName).take(4000)
   try{writeFileResult(output,key,id,1,detail)}catch(writeError:Throwable){t.addSuppressed(writeError);throw t}
  } finally {
   try{stage.delete()}catch(_:Exception){}
  }
 }

 private fun sendWifiFile(input:DataInputStream,output:DataOutputStream,key:ByteArray,id:String,path:String,root:Boolean) {
  val process=startFileCommand(root,"cat ${quote(path)}")
  val errors=ByteArrayOutputStream();val errorsTruncated=java.util.concurrent.atomic.AtomicBoolean()
  val errorThread=Thread { try { copyBounded(process.errorStream,errors,errorsTruncated,MAX_ERROR_BYTES) } catch(_:Exception){} }.apply { start() }
  val writer=WifiFileProtocol.writer(output,key,id)
  val buffer=ByteArray(WifiFileProtocol.CHUNK_SIZE)
  try {
   while(true) { val n=process.inputStream.read(buffer); if(n<0) break; if(n>0) writer.write(WifiFileProtocol.DATA,buffer.copyOf(n)) }
   val code=process.waitFor(); joinProcessReader(errorThread,process.errorStream)
   writeFileResult(writer,code,errors.toByteArray().toString(Charsets.UTF_8)+(if(errorsTruncated.get())"\n[WearBridge error output truncated]\n" else ""))
  } catch(t:Throwable) { process.destroy(); process.destroyForcibly(); throw t }
  finally { closeQuietly(process.inputStream);closeQuietly(process.errorStream);closeQuietly(process.outputStream) }
 }

 private fun writeFileResult(output:DataOutputStream,key:ByteArray,id:String,code:Int,message:String) {
  val msg=message.toByteArray(Charsets.UTF_8).let { if(it.size>8192) it.copyOf(8192) else it }
  val payload=ByteArray(4+msg.size)
  java.nio.ByteBuffer.wrap(payload).putInt(code).put(msg)
  WifiFileProtocol.writer(output,key,id).write(WifiFileProtocol.RESULT,payload)
 }

 private fun writeFileResult(writer:WifiFileProtocol.RecordWriter,code:Int,message:String) {
  val msg=message.toByteArray(Charsets.UTF_8).let { if(it.size>8192) it.copyOf(8192) else it }
  val payload=ByteArray(4+msg.size)
  java.nio.ByteBuffer.wrap(payload).putInt(code).put(msg)
  writer.write(WifiFileProtocol.RESULT,payload)
 }

 private fun startFileCommand(root:Boolean,command:String):java.lang.Process =
  ProcessBuilder(*(if(root) arrayOf("/system/bin/su","-c",command) else arrayOf("/system/bin/sh","-c",command))).start()

 private fun runFileCommand(root:Boolean,command:String):Pair<Int,String> {
  val process=startFileCommand(root,command)
  val out=ByteArrayOutputStream(); val err=ByteArrayOutputStream();val outTruncated=java.util.concurrent.atomic.AtomicBoolean();val errTruncated=java.util.concurrent.atomic.AtomicBoolean()
  val t1=Thread { try { copyBounded(process.inputStream,out,outTruncated) } catch(_:Exception){} }.apply { start() }
  val t2=Thread { try { copyBounded(process.errorStream,err,errTruncated) } catch(_:Exception){} }.apply { start() }
  try {
   val code=process.waitFor();joinProcessReader(t1,process.inputStream);joinProcessReader(t2,process.errorStream)
   val message=err.toByteArray().toString(Charsets.UTF_8).ifEmpty { out.toByteArray().toString(Charsets.UTF_8) }
   return code to (message+(if(outTruncated.get()||errTruncated.get())"\n[WearBridge output truncated]\n" else ""))
  } finally { if(process.isAlive){process.destroy();process.destroyForcibly()};closeQuietly(process.inputStream);closeQuietly(process.errorStream);closeQuietly(process.outputStream) }
 }

 private fun copyBounded(input:java.io.InputStream,output:ByteArrayOutputStream,truncated:java.util.concurrent.atomic.AtomicBoolean,limit:Int=MAX_OUTPUT_BYTES){
  val buffer=ByteArray(8192);var kept=0
  while(true){val n=input.read(buffer);if(n<0)break;val allowed=(limit-kept).coerceAtLeast(0);if(allowed>0){val count=minOf(n,allowed);output.write(buffer,0,count);kept+=count};if(n>allowed)truncated.set(true)}
 }
 private fun joinProcessReader(thread:Thread,input:java.io.InputStream){thread.join(1000);if(thread.isAlive){closeQuietly(input);thread.join(250)}}
 private fun closeQuietly(closeable:java.io.Closeable?){try{closeable?.close()}catch(_:Exception){}}

 private fun quote(value:String):String="'"+value.replace("'","'\\''")+"'"
 private fun validDirectoryPath(path:String)=path=="/"||validFilePath(path)
 private fun validFilePath(path:String)=path.startsWith("/") && path.length>1 && !path.contains('\u0000') && !path.contains('\n') && !path.contains('\r')
 private fun validDeletablePath(path:String)=validFilePath(path)&&path!="/"&&!path.split('/').any{it==".."||it=="."}
 private fun isUuid(value:String)=try { java.util.UUID.fromString(value); true } catch(_:Exception) { false }

 override fun onChannelOpened(channel:ChannelClient.Channel) { super.onChannelOpened(channel)
  val root=channel.path=="/terminal/root" || channel.path.startsWith("/terminal/root/api/")
  val apiSessionId=when {
   channel.path.startsWith("/terminal/api/")->channel.path.removePrefix("/terminal/api/")
   channel.path.startsWith("/terminal/root/api/")->channel.path.removePrefix("/terminal/root/api/")
   else->null
  }
  val allowed=channel.path=="/terminal" || channel.path=="/terminal/root" || (apiSessionId!=null && isUuid(apiSessionId))
  if(!allowed) { scope.launch { try { Wearable.getChannelClient(this@DataLayerListenerService).close(channel).await() } catch(_:Exception){} }; return }
  if(!activePtyNodes.add(channel.nodeId)) {
   if(apiSessionId!=null)sendApiStreamStatus(channel.nodeId,apiSessionId,false,"A terminal session is already active for this phone")
   scope.launch { try { Wearable.getChannelClient(this@DataLayerListenerService).close(channel,1).await() } catch(_:Exception){} }
   return
  }
  retainForeground()
  scope.launch {
   var pfd:ParcelFileDescriptor?=null
   var ptyBackend:ICmdService?=null
   val ptySessionId=java.util.UUID.randomUUID().toString()
   var fromPhone:java.io.InputStream?=null
   var toPhone:java.io.OutputStream?=null
   var inputPump:Job?=null
   var outputPump:Job?=null
   try {
    if(root) {
     val fd=Pty.openPty(true,ptySessionId)
     if(fd<0) throw java.io.IOException("root PTY open failed: errno=${-fd}")
     pfd=ParcelFileDescriptor.adoptFd(fd)
    } else {
     if(!Shizuku.pingBinder() || Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("Shizuku permission required")
     ptyBackend=obtainBackend()
     pfd=ptyBackend!!.openPty(false,ptySessionId)
    }
    val client=Wearable.getChannelClient(this@DataLayerListenerService)
    fromPhone=client.getInputStream(channel).await(); toPhone=client.getOutputStream(channel).await()
    val fd=pfd.fd
    activePtyFds[channel.nodeId]=fd
    acquirePtyWakeLock()
    if(apiSessionId!=null)sendApiStreamStatus(channel.nodeId,apiSessionId,true,null)
    val inputJob=launch(Dispatchers.IO) {
     val b=ByteArray(4096)
     while(isActive) { val n=fromPhone.read(b); if(n<0)break; var off=0; while(off<n) { val w=Pty.writePty(fd,b,off,n-off); if(w<=0) throw java.io.IOException("PTY write failed: $w"); off+=w } }
    }
    inputPump=inputJob
    val outputJob=launch(Dispatchers.IO) {
     val b=ByteArray(4096)
     while(isActive) { val n=Pty.readPty(fd,b,0,b.size); if(n<0)throw java.io.IOException("PTY read failed: $n"); if(n==0)continue; toPhone.write(b,0,n); toPhone.flush() }
    }
    outputPump=outputJob
    select<Unit> { inputJob.onJoin { }; outputJob.onJoin { } }
    try { fromPhone.close() } catch(_:Exception){}; try { toPhone.close() } catch(_:Exception){}
    inputJob.cancelAndJoin(); outputJob.cancelAndJoin()
   } catch(t:Throwable) {
    if(t !is CancellationException) {
     Log.e(TAG,"Stream channel ${channel.nodeId} failed",t)
     if(apiSessionId!=null)sendApiStreamStatus(channel.nodeId,apiSessionId,false,t.message?:t.javaClass.simpleName)
     try { Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(channel.nodeId,"/stream-error",(t.javaClass.simpleName+": "+(t.message?:"no detail")).toByteArray()).await() } catch(_:Exception){}
    }
    try { Wearable.getChannelClient(this@DataLayerListenerService).close(channel,1).await() } catch(_:Exception){}
   } finally {
    // Channel InputStream.read() blocks outside coroutine cancellation. Close the streams
    // first, then join both pumps and close the PTY/channel even during service shutdown.
    try { fromPhone?.close() } catch(_:Exception){}; try { toPhone?.close() } catch(_:Exception){}
    try { pfd?.close() } catch(_:Exception){}
    withContext(NonCancellable) {
     inputPump?.cancel();outputPump?.cancel()
     try { withTimeoutOrNull(1500){Wearable.getChannelClient(this@DataLayerListenerService).close(channel,1).await()} } catch(_:Throwable){}
     inputPump?.let{withTimeoutOrNull(1000){it.join()}};outputPump?.let{withTimeoutOrNull(1000){it.join()}}
     try { if(root)Pty.closePty(ptySessionId) else ptyBackend?.closePty(ptySessionId) } catch(t:Throwable){Log.w(TAG,"PTY child cleanup failed for ${channel.nodeId}",t)}
    }
    activePtyFds.remove(channel.nodeId);activePtyNodes.remove(channel.nodeId);releasePtyWakeLock();releaseForeground()
   }
  }
 }

 private fun acquirePtyWakeLock(){
  synchronized(this){
   val current=ptyWakeLock
   if(current?.isHeld==true)return
   val power=getSystemService(PowerManager::class.java) ?: throw IllegalStateException("PowerManager unavailable")
   ptyWakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"WearBridgeADB:PTY").apply{
    setReferenceCounted(false)
    acquire()
   }
  }
 }
 private fun acquireExecWakeLock(request:String){
  synchronized(execWakeLockGuard){
   check(!execWakeLocks.containsKey(request)){"Execution wake lock already exists for request"}
   val power=getSystemService(PowerManager::class.java) ?: throw IllegalStateException("PowerManager unavailable")
   val lock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"WearBridgeADB:EXEC").apply{
    setReferenceCounted(false)
    acquire()
   }
   execWakeLocks[request]=ExecWakeLock(lock,SystemClock.elapsedRealtime())
  }
  RequestTrace.record(this,request,"exec_wakelock_acquired")
 }
 private fun releaseExecWakeLock(request:String){
  val tracked=synchronized(execWakeLockGuard){execWakeLocks.remove(request)} ?: return
  try { if(tracked.wakeLock.isHeld)tracked.wakeLock.release() }
  catch(t:Throwable){RequestTrace.record(this,request,"exec_wakelock_release_failed",t.javaClass.simpleName)}
  RequestTrace.record(this,request,"exec_wakelock_released","heldMs=${SystemClock.elapsedRealtime()-tracked.acquiredAtElapsedMs}")
 }
 private fun releaseAllExecWakeLocks(){
  val tracked=synchronized(execWakeLockGuard){execWakeLocks.values.toList().also{execWakeLocks.clear()}}
  tracked.forEach {
   try { if(it.wakeLock.isHeld)it.wakeLock.release() } catch(_:Throwable){}
  }
 }
 private fun releasePtyWakeLock(){
  synchronized(this){val current=ptyWakeLock;ptyWakeLock=null;if(current?.isHeld==true)current.release()}
 }

 private fun cancelExecution(request:String) {
  val expiresAt=SystemClock.elapsedRealtime()+CANCELLED_EXEC_TTL_MS
  pruneCancelledExecIds()
  cancelledExecIds[request]=expiresAt
  while(cancelledExecIds.size>MAX_CANCELLED_EXEC_IDS){val oldest=cancelledExecIds.entries.minByOrNull{it.value}?:break;cancelledExecIds.remove(oldest.key,oldest.value)}
  val backend=remote
  RequestTrace.record(this,request,"cancel_received","backendAvailable=${backend!=null} rootProcess=${rootProcesses.containsKey(request)}")
  rootProcesses[request]?.let { running ->
   RequestTrace.record(this,request,"root_process_cancel_start")
   stopRootProcess(running)
   RequestTrace.record(this,request,"root_process_cancel_sent","alive=${running.process.isAlive} group=${running.processGroupId}")
   return
  }
  if(backend==null){RequestTrace.record(this,request,"cancel_deferred_until_backend_ready");return}
  scope.launch { try { val stopped=backend.cancel(request);RequestTrace.record(this@DataLayerListenerService,request,"backend_cancel_call_return","processStopped=$stopped") } catch(t:Throwable) { RequestTrace.record(this@DataLayerListenerService,request,"backend_cancel_call_failed",t.javaClass.simpleName);Log.w(TAG,"Could not cancel command $request",t) } }
 }

 private fun execAsKernelSuApp(request:String,command:String):String {
  if(!rootExecutionSlots.tryAcquire())return "exitCode=75\nstdout:\n\nstderr:\nWearBridge watch is busy (maximum $MAX_ROOT_COMMANDS concurrent ROOT commands)"
  try {
  val pidFile=java.io.File.createTempFile("wearbridge-root-pgid-",".tmp",cacheDir)
  val hasSetsid=java.io.File("/system/bin/setsid").canExecute()
  if(!hasSetsid){pidFile.delete();return "exitCode=69\nstdout:\n\nstderr:\nROOT command was not started: /system/bin/setsid is required for bounded process-group cleanup"}
  val sessionScript="printf '%s\\n' \"\$\$\" > ${quote(pidFile.absolutePath)}; exec /system/bin/sh -c ${quote(command)}"
  val rootCommand="/system/bin/setsid /system/bin/sh -c ${quote(sessionScript)}"
  val p=try { ProcessBuilder("/system/bin/su","-c",rootCommand).start() }
   catch(e:java.io.IOException) {
    pidFile.delete()
    if(e.message?.contains("error=2") == true) throw java.io.IOException(
     "KernelSU did not resolve its virtual /system/bin/su for app uid=${Process.myUid()}. " +
      "Check that KernelSU jailbreak is active and dev.wearbrdgeadb is allowed in KernelSU.",e)
    throw e
   }
  val running=RootProcess(p,pidFile)
  rootProcesses[request]=running
  running.processGroupId=readRootProcessGroupId(running,ROOT_PIDFILE_WAIT_MS)
  if(consumeCancellation(request))stopRootProcess(running)
  val out=java.io.ByteArrayOutputStream(); val err=java.io.ByteArrayOutputStream();val outTruncated=java.util.concurrent.atomic.AtomicBoolean();val errTruncated=java.util.concurrent.atomic.AtomicBoolean()
  val t1=Thread{try{copyBounded(p.inputStream,out,outTruncated)}catch(_:Exception){}}; val t2=Thread{try{copyBounded(p.errorStream,err,errTruncated)}catch(_:Exception){}}
  t1.start(); t2.start()
  try {
   val completed=try{p.waitFor(ROOT_COMMAND_TIMEOUT_MS,java.util.concurrent.TimeUnit.MILLISECONDS)}catch(interrupted:InterruptedException){stopRootProcess(running);Thread.currentThread().interrupt();throw interrupted}
   if(!completed){RequestTrace.record(this,request,"root_process_timeout","timeoutMs=$ROOT_COMMAND_TIMEOUT_MS group=${running.processGroupId}");stopRootProcess(running)}
   joinProcessReader(t1,p.inputStream);joinProcessReader(t2,p.errorStream)
   val code=if(completed)p.exitValue() else ROOT_COMMAND_TIMEOUT_EXIT_CODE
   val timeoutMessage=if(completed)"" else "ROOT command exceeded ${ROOT_COMMAND_TIMEOUT_MS/1000} seconds and was stopped.\n"
   return "exitCode=$code\nstdout:\n${out.toString("UTF-8")}${if(outTruncated.get())"\n[WearBridge output truncated at 1 MiB]\n" else ""}stderr:\n$timeoutMessage${err.toString("UTF-8")}${if(errTruncated.get())"\n[WearBridge output truncated at 1 MiB]\n" else ""}"
  }
  finally { if(p.isAlive)stopRootProcess(running);closeQuietly(p.inputStream);closeQuietly(p.errorStream);closeQuietly(p.outputStream);rootProcesses.remove(request,running);pidFile.delete() }
  } finally { rootExecutionSlots.release() }
 }

 private fun readRootProcessGroupId(running:RootProcess,waitMs:Long):Int? {
  val deadline=android.os.SystemClock.elapsedRealtime()+waitMs
  while(android.os.SystemClock.elapsedRealtime()<deadline&&running.process.isAlive){
   val id=try{running.pidFile.takeIf{it.isFile}?.readText()?.trim()?.toIntOrNull()?.takeIf{it>1}}catch(_:Exception){null}
   if(id!=null)return id
   try{Thread.sleep(10)}catch(_:InterruptedException){Thread.currentThread().interrupt();return null}
  }
  return try{running.pidFile.takeIf{it.isFile}?.readText()?.trim()?.toIntOrNull()?.takeIf{it>1}}catch(_:Exception){null}
 }

 private fun stopRootProcess(running:RootProcess) {
  val process=running.process
  var groupId=running.processGroupId
  if(groupId==null){groupId=readRootProcessGroupId(running,ROOT_PIDFILE_WAIT_MS);running.processGroupId=groupId}
  if(groupId!=null)signalRootProcessGroup(groupId,"TERM") else process.destroy()
  waitForProcessBounded(process,ROOT_TERM_GRACE_MS)
  if(groupId!=null)signalRootProcessGroup(groupId,"KILL")
  if(process.isAlive)process.destroyForcibly()
  waitForProcessBounded(process,ROOT_KILL_GRACE_MS)
  closeQuietly(process.inputStream);closeQuietly(process.errorStream);closeQuietly(process.outputStream)
 }

 private fun signalRootProcessGroup(groupId:Int,signal:String):Boolean {
  if(groupId<=1)return false
  val killer=try{ProcessBuilder("/system/bin/su","-c","kill -$signal -- -$groupId").start()}catch(_:Exception){return false}
  return try{killer.waitFor(ROOT_SIGNAL_WAIT_MS,java.util.concurrent.TimeUnit.MILLISECONDS)&&killer.exitValue()==0}
  catch(_:InterruptedException){Thread.currentThread().interrupt();false}
  finally{if(killer.isAlive){killer.destroy();if(killer.isAlive)killer.destroyForcibly();waitForProcessBounded(killer,ROOT_KILL_GRACE_MS)};closeQuietly(killer.inputStream);closeQuietly(killer.errorStream);closeQuietly(killer.outputStream)}
 }

 private fun waitForProcessBounded(process:java.lang.Process,waitMs:Long){
  try{process.waitFor(waitMs,java.util.concurrent.TimeUnit.MILLISECONDS)}catch(_:InterruptedException){Thread.currentThread().interrupt()}
 }

 private fun consumeCancellation(request:String):Boolean {
  val expiresAt=cancelledExecIds.remove(request)?:return false
  return expiresAt>=SystemClock.elapsedRealtime()
 }

 private fun pruneCancelledExecIds(){
  val now=SystemClock.elapsedRealtime();cancelledExecIds.entries.removeIf{it.value<now}
  while(cancelledExecIds.size>MAX_CANCELLED_EXEC_IDS){val oldest=cancelledExecIds.entries.minByOrNull{it.value}?:break;cancelledExecIds.remove(oldest.key,oldest.value)}
 }

 private fun copyBounded(input:java.io.InputStream,output:java.io.ByteArrayOutputStream,truncated:java.util.concurrent.atomic.AtomicBoolean){
  copyBounded(input,output,truncated,MAX_OUTPUT_BYTES)
 }

 private suspend fun obtainBackend(requestId:String?=null):ICmdService {
  RequestTrace.record(this,requestId?:"backend","backend_mutex_wait_start")
  val gate=bindMutex.withLock {
   remote?.let { return@withLock CompletableDeferred<ICmdService>().also{gate->gate.complete(it)} }
   pending?.let { return@withLock it }
   val created=CompletableDeferred<ICmdService>()
   pending=created
   pendingBindRequestId=requestId?:"backend"
   RequestTrace.record(this,pendingBindRequestId?:"backend","backend_mutex_acquired")
   try {
    backendBindExecutor.execute {
     val traceId=requestId?:"backend"
     RequestTrace.record(this@DataLayerListenerService,traceId,"shizuku_bind_call_start")
     try {
      Shizuku.bindUserService(args,connection)
      RequestTrace.record(this@DataLayerListenerService,traceId,"shizuku_bind_call_return")
      backendBindTimeouts.schedule({
       if(remote==null && pending===created) {
        pending=null;pendingBindRequestId=null
        RequestTrace.record(this@DataLayerListenerService,traceId,"backend_callback_timeout","waitMs=15000")
        created.completeExceptionally(java.util.concurrent.TimeoutException("Shizuku UserService connection callback timed out"))
       }
      },15000,java.util.concurrent.TimeUnit.MILLISECONDS)
     } catch(t:Throwable) {
      RequestTrace.record(this@DataLayerListenerService,traceId,"shizuku_bind_call_failed",t.javaClass.simpleName)
      if(pending===created){pending=null;pendingBindRequestId=null}
      created.completeExceptionally(t)
     }
    }
   } catch(t:Throwable) {
    if(pending===created){pending=null;pendingBindRequestId=null}
    created.completeExceptionally(t)
   }
   created
  }
  RequestTrace.record(this,requestId?:"backend","backend_callback_wait_start")
  return try {
   withTimeout(15000) { gate.await() }
  } catch(t:kotlinx.coroutines.TimeoutCancellationException) {
   RequestTrace.record(this,requestId?:"backend","backend_wait_timeout","waitMs=15000")
   throw java.util.concurrent.TimeoutException("Shizuku UserService was not ready within 15000 ms")
  }
 }

 @Synchronized private fun retainForeground() {
  if(activeOperations.getAndIncrement()!=0)return
  try {
   val manager=getSystemService(NotificationManager::class.java)
   if(Build.VERSION.SDK_INT>=26) {
    manager.createNotificationChannel(NotificationChannel(CHANNEL,"Remote shell",NotificationManager.IMPORTANCE_LOW))
    startForegroundService(Intent(this,DataLayerListenerService::class.java).setAction(ACTION_KEEP_ALIVE))
   } else startService(Intent(this,DataLayerListenerService::class.java).setAction(ACTION_KEEP_ALIVE))
   val notification=Notification.Builder(this,CHANNEL)
   startForeground(NOTIFICATION_ID,notification.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("WearBridgeADB").setContentText("Remote shell session active").setOngoing(true).build())
  } catch(t:Throwable) { activeOperations.decrementAndGet(); Log.e(TAG,"Could not keep shell service alive",t); throw t }
 }
 @Synchronized private fun releaseForeground() { if(activeOperations.decrementAndGet()<=0) { activeOperations.set(0); try { stopForeground(true) } catch(_:Exception){}; stopSelf() } }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int { super.onStartCommand(intent,flags,startId); return START_NOT_STICKY }
 override fun onDestroy(){ releasePtyWakeLock(); releaseAllExecWakeLocks(); scope.cancel();backendBindExecutor.shutdownNow();backendBindTimeouts.shutdownNow();try { Shizuku.unbindUserService(args,connection,true) } catch(_:Exception){}; super.onDestroy() }
 companion object { private const val TAG="WearBridgeADB"; private const val CHANNEL="wearbridge-shell"; private const val NOTIFICATION_ID=41; private const val ACTION_KEEP_ALIVE="dev.wearbrdgeadb.KEEP_ALIVE"; private const val CANCELLED_EXEC_TTL_MS=120_000L; private const val MAX_CANCELLED_EXEC_IDS=256; private const val MAX_ROOT_COMMANDS=3; private const val MAX_OUTPUT_BYTES=1024*1024; private const val MAX_ERROR_BYTES=64*1024; private const val ROOT_COMMAND_TIMEOUT_MS=120_000L; private const val ROOT_COMMAND_TIMEOUT_EXIT_CODE=124; private const val ROOT_PIDFILE_WAIT_MS=500L; private const val ROOT_SIGNAL_WAIT_MS=500L; private const val ROOT_TERM_GRACE_MS=750L; private const val ROOT_KILL_GRACE_MS=750L }
}
