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
 private val activePtyFds=java.util.concurrent.ConcurrentHashMap<String,Int>()
 private var remote: ICmdService?=null
 private var pending: CompletableDeferred<ICmdService>?=null
 private val args by lazy { Shizuku.UserServiceArgs(ComponentName(this,ShellUserService::class.java)).version(2).daemon(false).processNameSuffix("wearbridge") }
 private val connection=object: ServiceConnection {
  override fun onServiceConnected(n:ComponentName?, b:IBinder?) { remote=ICmdService.Stub.asInterface(b); pending?.complete(remote!!) }
  override fun onServiceDisconnected(n:ComponentName?) { remote=null }
 }

 override fun onMessageReceived(e:MessageEvent) { super.onMessageReceived(e)
  if(e.path=="/terminal/resize") { resizeActivePty(e.sourceNodeId,e.data); return }
  if(e.path=="/file-transfer/list") { handleFileListing(e); return }
  if(e.path=="/file-transfer/mkdir") { handleRemoteMkdir(e); return }
  if(e.path=="/file-transfer/delete") { handleRemoteDelete(e); return }
  if(e.path=="/file-transfer/upload" || e.path=="/file-transfer/download") { handleWifiFileRequest(e); return }
  if(e.path!="/exec") return
  val parts=String(e.data,Charsets.UTF_8).split('\n',limit=3); if(parts.size<2)return
  val request=parts[0]; val root=parts.size>=3 && parts[1]=="root"; val command=if(parts.size>=3)parts[2] else parts[1]; val node=e.sourceNodeId
  retainForeground()
  scope.launch {
   try {
    val reply=try {
     if(root) execAsKernelSuApp(command)
     else if(!Shizuku.pingBinder()) "Shizuku unavailable" else if(Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED) "Shizuku permission denied" else obtainBackend().exec(command,false)
    } catch(t:Throwable) { "ERROR: ${t.message}" }
    Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(node,"/result","[$request]\n$reply\n".toByteArray()).await()
   } catch(t:Throwable) { Log.e(TAG,"Normal request response failed",t) }
   finally { releaseForeground() }
  }
 }

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
     val outThread=Thread{try{process.inputStream.copyTo(stdout)}catch(_:Exception){}}.apply{start()}
     val errThread=Thread{try{process.errorStream.copyTo(stderr)}catch(_:Exception){}}.apply{start()}
     val code=process.waitFor();outThread.join();errThread.join()
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

 private fun receiveWifiFile(input:DataInputStream,output:DataOutputStream,key:ByteArray,id:String,path:String,root:Boolean) {
  val stage=java.io.File(cacheDir,".wearbridge-$id.part").absolutePath
  val prepared=runFileCommand(root,"umask 077; set -C; : > ${quote(stage)} && chmod 0644 ${quote(stage)}")
  if(prepared.first!=0){writeFileResult(output,key,id,prepared.first,prepared.second.ifBlank{"Could not create upload staging file in app cache"});return}
  val stageProcess=startFileCommand(root,"cat > ${quote(stage)}")
  val errorBytes=ByteArrayOutputStream()
  val errorThread=Thread { try { stageProcess.errorStream.copyTo(errorBytes) } catch(_:Exception){} }.apply { start() }
  try {
   val reader=WifiFileProtocol.reader(input,key,id)
   while(true) {
    val record=reader.read()
    when(record.type) {
     WifiFileProtocol.DATA -> stageProcess.outputStream.write(record.payload)
     WifiFileProtocol.END -> { if(record.payload.isNotEmpty()) throw java.io.IOException("Invalid upload end record"); break }
     else -> throw java.io.IOException("Unexpected upload record")
    }
   }
   stageProcess.outputStream.close()
   val stageCode=stageProcess.waitFor(); errorThread.join()
   if(stageCode!=0) {
    runFileCommand(root,"rm -f ${quote(stage)}")
   writeFileResult(output,key,id,stageCode,errorBytes.toByteArray().toString(Charsets.UTF_8)); return
   }
   val commit=runFileCommand(root,"cp -f ${quote(stage)} ${quote(path)}")
   runFileCommand(root,"rm -f ${quote(stage)}")
   writeFileResult(output,key,id,commit.first,commit.second)
  } catch(t:Throwable) {
   try{stageProcess.outputStream.close()}catch(_:Throwable){}
   try{stageProcess.waitFor()}catch(_:Throwable){}
   try{errorThread.join(1000)}catch(_:Throwable){}
   val processError=errorBytes.toByteArray().toString(Charsets.UTF_8).trim()
   val detail=listOf(processError,t.message.orEmpty(),t.javaClass.simpleName).filter{it.isNotBlank()}.joinToString(": ").take(4000)
   try{writeFileResult(output,key,id,1,detail)}catch(writeError:Throwable){t.addSuppressed(writeError);throw t}
   try { stageProcess.destroy(); stageProcess.destroyForcibly() } catch(_:Exception){}
   try { runFileCommand(root,"rm -f ${quote(stage)}") } catch(_:Exception){}
  }
 }

 private fun sendWifiFile(input:DataInputStream,output:DataOutputStream,key:ByteArray,id:String,path:String,root:Boolean) {
  val process=startFileCommand(root,"cat ${quote(path)}")
  val errors=ByteArrayOutputStream()
  val errorThread=Thread { try { process.errorStream.copyTo(errors) } catch(_:Exception){} }.apply { start() }
  val writer=WifiFileProtocol.writer(output,key,id)
  val buffer=ByteArray(WifiFileProtocol.CHUNK_SIZE)
  try {
   while(true) { val n=process.inputStream.read(buffer); if(n<0) break; if(n>0) writer.write(WifiFileProtocol.DATA,buffer.copyOf(n)) }
   val code=process.waitFor(); errorThread.join()
   writeFileResult(writer,code,errors.toByteArray().toString(Charsets.UTF_8))
  } catch(t:Throwable) { process.destroy(); process.destroyForcibly(); throw t }
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
  val out=ByteArrayOutputStream(); val err=ByteArrayOutputStream()
  val t1=Thread { try { process.inputStream.copyTo(out) } catch(_:Exception){} }.apply { start() }
  val t2=Thread { try { process.errorStream.copyTo(err) } catch(_:Exception){} }.apply { start() }
  val code=process.waitFor(); t1.join(); t2.join()
  return code to (err.toByteArray().toString(Charsets.UTF_8).ifEmpty { out.toByteArray().toString(Charsets.UTF_8) })
 }

 private fun quote(value:String):String="'"+value.replace("'","'\\''")+"'"
 private fun validDirectoryPath(path:String)=path=="/"||validFilePath(path)
 private fun validFilePath(path:String)=path.startsWith("/") && path.length>1 && !path.contains('\u0000') && !path.contains('\n') && !path.contains('\r')
 private fun validDeletablePath(path:String)=validFilePath(path)&&path!="/"&&!path.split('/').any{it==".."||it=="."}
 private fun isUuid(value:String)=try { java.util.UUID.fromString(value); true } catch(_:Exception) { false }

 override fun onChannelOpened(channel:ChannelClient.Channel) { super.onChannelOpened(channel)
  val root=channel.path=="/terminal/root"
  if(channel.path!="/terminal" && !root) { scope.launch { try { Wearable.getChannelClient(this@DataLayerListenerService).close(channel).await() } catch(_:Exception){} }; return }
  retainForeground()
  scope.launch {
   var pfd:ParcelFileDescriptor?=null
   var fromPhone:java.io.InputStream?=null
   var toPhone:java.io.OutputStream?=null
   try {
    if(root) {
     val fd=Pty.openPty(true)
     if(fd<0) throw java.io.IOException("root PTY open failed: errno=${-fd}")
     pfd=ParcelFileDescriptor.adoptFd(fd)
    } else {
     if(!Shizuku.pingBinder() || Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("Shizuku permission required")
     pfd=obtainBackend().openPty(false)
    }
    val client=Wearable.getChannelClient(this@DataLayerListenerService)
    fromPhone=client.getInputStream(channel).await(); toPhone=client.getOutputStream(channel).await()
    val fd=pfd.fd
    activePtyFds[channel.nodeId]=fd
    val inputPump=launch(Dispatchers.IO) {
     val b=ByteArray(4096)
     while(isActive) { val n=fromPhone.read(b); if(n<0)break; var off=0; while(off<n) { val w=Pty.writePty(fd,b,off,n-off); if(w<=0) throw java.io.IOException("PTY write failed: $w"); off+=w } }
    }
    val outputPump=launch(Dispatchers.IO) {
     val b=ByteArray(4096)
     while(isActive) { val n=Pty.readPty(fd,b,0,b.size); if(n<0)throw java.io.IOException("PTY read failed: $n"); if(n==0)continue; toPhone.write(b,0,n); toPhone.flush() }
    }
    select<Unit> { inputPump.onJoin { }; outputPump.onJoin { } }
    inputPump.cancelAndJoin(); outputPump.cancelAndJoin()
    try { fromPhone.close() } catch(_:Exception){}; try { toPhone.close() } catch(_:Exception){}
    client.close(channel).await()
   } catch(t:Throwable) {
    if(t !is CancellationException) {
     Log.e(TAG,"Stream channel ${channel.nodeId} failed",t)
     try { Wearable.getMessageClient(this@DataLayerListenerService).sendMessage(channel.nodeId,"/stream-error",(t.javaClass.simpleName+": "+(t.message?:"no detail")).toByteArray()).await() } catch(_:Exception){}
    }
    try { Wearable.getChannelClient(this@DataLayerListenerService).close(channel,1).await() } catch(_:Exception){}
   } finally {
    activePtyFds.remove(channel.nodeId)
    try { fromPhone?.close() } catch(_:Exception){}; try { toPhone?.close() } catch(_:Exception){}; try { pfd?.close() } catch(_:Exception){}
    releaseForeground()
   }
  }
 }

 private fun execAsKernelSuApp(command:String):String {
  val p=try { ProcessBuilder("/system/bin/su","-c",command).start() }
   catch(e:java.io.IOException) {
    if(e.message?.contains("error=2") == true) throw java.io.IOException(
     "KernelSU did not resolve its virtual /system/bin/su for app uid=${Process.myUid()}. " +
      "Check that KernelSU jailbreak is active and dev.wearbrdgeadb is allowed in KernelSU.",e)
    throw e
   }
  val out=java.io.ByteArrayOutputStream(); val err=java.io.ByteArrayOutputStream()
  val t1=Thread{p.inputStream.copyTo(out)}; val t2=Thread{p.errorStream.copyTo(err)}
  t1.start(); t2.start(); val code=p.waitFor(); t1.join(); t2.join()
  return "exitCode=$code\nstdout:\n${out.toString("UTF-8")}\nstderr:\n${err.toString("UTF-8")}"
 }

 private suspend fun obtainBackend():ICmdService=bindMutex.withLock {
  remote?.let { return@withLock it }
  val gate=CompletableDeferred<ICmdService>(); pending=gate
  try { Shizuku.bindUserService(args,connection); withTimeout(15000) { gate.await() } }
  finally { pending=null }
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
 override fun onDestroy(){ scope.cancel(); try { Shizuku.unbindUserService(args,connection,true) } catch(_:Exception){}; super.onDestroy() }
 companion object { private const val TAG="WearBridgeADB"; private const val CHANNEL="wearbridge-shell"; private const val NOTIFICATION_ID=41; private const val ACTION_KEEP_ALIVE="dev.wearbrdgeadb.KEEP_ALIVE" }
}
