package dev.wearbrdgeadb

import android.os.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ShellUserService : ICmdService.Stub() {
 private data class RunningProcess(val process: java.lang.Process, val processGroupId: Int?)
 private val lifecycleLock=Any()
 private val processes = java.util.concurrent.ConcurrentHashMap<String, RunningProcess>()
 private val ownedProcesses=java.util.concurrent.ConcurrentHashMap.newKeySet<java.lang.Process>()
 private val ptySessions=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
 @Volatile private var destroying=false
 // Cancellation may arrive before execJob registers its Process. Keep these short-lived,
 // bounded markers so a late cancel for an already-finished/unknown request cannot leak IDs.
 private val cancelled = java.util.concurrent.ConcurrentHashMap<String, Long>()
 // Keep a small amount of concurrency while bounding process, reader-thread, and output retention.
 private val executionSlots = java.util.concurrent.Semaphore(MAX_CONCURRENT_COMMANDS)
 override fun exec(command: String, root: Boolean): String = execJob(java.util.UUID.randomUUID().toString(), command, root)
 override fun execJob(requestId: String, command: String, root: Boolean): String {
  if(destroying)throw RemoteException("Shizuku UserService is stopping")
  pruneCancelled()
  if(!executionSlots.tryAcquire())return "exitCode=75\nstdout:\n\nstderr:\nWearBridge watch is busy (maximum $MAX_CONCURRENT_COMMANDS concurrent commands)"
  return try { executeJob(requestId,command,root) } finally { executionSlots.release() }
 }
 private fun executeJob(requestId:String,command:String,root:Boolean):String {
  val commandArgv=if(root && Process.myUid()!=0) listOf("/system/bin/su","-c",command) else listOf("/system/bin/sh","-c",command)
  // A dedicated session lets cancellation signal the whole shell process group, including
  // foreground children such as logcat. Without it, killing only `sh -c` can orphan a child
  // that keeps stdout/stderr open forever.
  val running=synchronized(lifecycleLock) {
   if(destroying)throw RemoteException("Shizuku UserService is stopping")
   val pidFile=if(java.io.File("/system/bin/setsid").canExecute())try{
    java.io.File.createTempFile("wearbridge-pgid-",".tmp",java.io.File("/data/local/tmp"))
   }catch(_:Exception){null}else null
   val hasSetsid=pidFile!=null
   val sessionScript="/system/bin/toybox printf '%s' \"\$\$\" > '${pidFile?.absolutePath}'; exec \"\$@\""
   val cmd=if(hasSetsid) listOf("/system/bin/setsid","/system/bin/sh","-c",sessionScript,"wearbridge")+commandArgv else commandArgv
   val p=try{ProcessBuilder(cmd).start()}catch(t:Throwable){pidFile?.delete();throw t}
   val value=try{RunningProcess(p,if(hasSetsid)readProcessGroupId(pidFile!!,p) else null)}finally{pidFile?.delete()}
   ownedProcesses.add(p)
   processes[requestId]=value
   value
  }
  val p=running.process
  if(consumeCancellation(requestId)) stopProcess(running)
  val out=ByteArrayOutputStream(); val err=ByteArrayOutputStream()
  val outTruncated=java.util.concurrent.atomic.AtomicBoolean();val errTruncated=java.util.concurrent.atomic.AtomicBoolean()
  val t1=Thread { try { copyBounded(p.inputStream,out,outTruncated) } catch(_:Exception){} }; val t2=Thread { try { copyBounded(p.errorStream,err,errTruncated) } catch(_:Exception){} }; t1.start(); t2.start()
  try {
   val code=p.waitFor()
   joinReader(t1,p.inputStream);joinReader(t2,p.errorStream)
   return "exitCode=$code\nstdout:\n${out.toString("UTF-8")}${if(outTruncated.get())"\n[WearBridge output truncated at 2 MiB]\n" else ""}stderr:\n${err.toString("UTF-8")}${if(errTruncated.get())"\n[WearBridge output truncated at 2 MiB]\n" else ""}"
  } catch(t:InterruptedException) {
   stopProcess(running)
   Thread.currentThread().interrupt()
   throw t
  } finally {
   if(p.isAlive)stopProcess(running)
   closeQuietly(p.inputStream);closeQuietly(p.errorStream)
   processes.remove(requestId,running);cancelled.remove(requestId)
   ownedProcesses.remove(p)
  }
 }
 private fun copyBounded(input:java.io.InputStream,output:ByteArrayOutputStream,truncated:java.util.concurrent.atomic.AtomicBoolean){
  val buffer=ByteArray(8192);var kept=0;while(true){val n=input.read(buffer);if(n<0)break;val allowed=(MAX_OUTPUT_BYTES-kept).coerceAtLeast(0);if(allowed>0){val count=minOf(n,allowed);output.write(buffer,0,count);kept+=count};if(n>allowed)truncated.set(true)}
 }
 override fun cancel(requestId: String): Boolean {
  pruneCancelled()
  cancelled[requestId] = android.os.SystemClock.elapsedRealtime() + CANCELLED_TTL_MS
  val running=processes[requestId] ?: return true // cancellation is recorded before execJob registers its process
  stopProcess(running)
  return !running.process.isAlive
 }
 private fun consumeCancellation(requestId:String):Boolean {
  val expiresAt=cancelled.remove(requestId) ?: return false
  return expiresAt >= android.os.SystemClock.elapsedRealtime()
 }
 private fun pruneCancelled() {
  val now=android.os.SystemClock.elapsedRealtime()
  cancelled.entries.removeIf { it.value < now }
  while(cancelled.size > MAX_CANCELLED_MARKERS) {
   val oldest=cancelled.entries.minByOrNull { it.value } ?: break
   cancelled.remove(oldest.key,oldest.value)
  }
 }
 private fun stopProcess(running:RunningProcess) {
  val process=running.process
  val groupId=running.processGroupId
  var groupSignalled=false
  if(groupId!=null) {
   try { groupSignalled=Pty.signalProcessGroup(groupId,android.system.OsConstants.SIGTERM)==0 } catch(_:Throwable) {}
  }
  if(!groupSignalled)process.destroy()
  try {
   process.waitFor(750,TimeUnit.MILLISECONDS)
   if(groupSignalled)try { Pty.signalProcessGroup(groupId!!,android.system.OsConstants.SIGKILL) } catch(_:Throwable) {}
   if(process.isAlive)process.destroyForcibly()
   process.waitFor(1000,TimeUnit.MILLISECONDS)
  } catch(_:InterruptedException) { if(process.isAlive)process.destroyForcibly();Thread.currentThread().interrupt() }
  // Closing these streams also releases reader threads if a shell child inherited a pipe.
  closeQuietly(process.inputStream);closeQuietly(process.errorStream)
 }
 private fun joinReader(thread:Thread,input:InputStream) {
  thread.join(1000)
  if(thread.isAlive){closeQuietly(input);thread.join(250)}
 }
 private fun readProcessGroupId(pidFile:java.io.File,process:java.lang.Process):Int? {
  val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
  while(System.nanoTime()<deadline&&process.isAlive){
   val id=try{pidFile.takeIf{it.isFile}?.readText()?.trim()?.toIntOrNull()}catch(_:Exception){null}
   if(id!=null&&id>1)return id
   try{Thread.sleep(10)}catch(_:InterruptedException){Thread.currentThread().interrupt();return null}
  }
  return try{pidFile.takeIf{it.isFile}?.readText()?.trim()?.toIntOrNull()?.takeIf{it>1}}catch(_:Exception){null}
 }
 private fun closeQuietly(closeable:java.io.Closeable?){try{closeable?.close()}catch(_:Exception){}}
 private fun startOwnedProcess(command:List<String>):java.lang.Process=synchronized(lifecycleLock){
  if(destroying)throw RemoteException("Shizuku UserService is stopping")
  val process=ProcessBuilder(command).start()
  ownedProcesses.add(process)
  process
 }
 override fun copyToFile(source: ParcelFileDescriptor, target: String): String {
  val process=startOwnedProcess(listOf("/system/bin/sh","-c","cat > ${quote(target)}"))
  val errors=ByteArrayOutputStream();val truncated=java.util.concurrent.atomic.AtomicBoolean();val errorThread=Thread{try{copyBounded(process.errorStream,errors,truncated,MAX_COPY_ERROR_BYTES)}catch(_:Exception){}}.apply{start()}
  return try { ParcelFileDescriptor.AutoCloseInputStream(source).use{input->process.outputStream.use{output->input.copyTo(output,64*1024)}};val code=process.waitFor();joinReader(errorThread,process.errorStream);"exitCode=$code\n${errors.toString("UTF-8")}${if(truncated.get())"\n[WearBridge error output truncated]\n" else ""}" }
  catch(t:Throwable){process.destroy();process.destroyForcibly();throw t}
  finally{if(process.isAlive){process.destroy();process.destroyForcibly();try{process.waitFor(500,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){Thread.currentThread().interrupt()}};closeQuietly(process.outputStream);closeQuietly(process.errorStream);joinReader(errorThread,process.errorStream);ownedProcesses.remove(process)}
 }
 override fun copyFromFile(source: String, destination: ParcelFileDescriptor): String {
  val process=startOwnedProcess(listOf("/system/bin/sh","-c","cat ${quote(source)}"))
  val errors=ByteArrayOutputStream();val truncated=java.util.concurrent.atomic.AtomicBoolean();val errorThread=Thread{try{copyBounded(process.errorStream,errors,truncated,MAX_COPY_ERROR_BYTES)}catch(_:Exception){}}.apply{start()}
  return try { process.inputStream.use{input->ParcelFileDescriptor.AutoCloseOutputStream(destination).use{output->input.copyTo(output,64*1024)}};val code=process.waitFor();joinReader(errorThread,process.errorStream);"exitCode=$code\n${errors.toString("UTF-8")}${if(truncated.get())"\n[WearBridge error output truncated]\n" else ""}" }
  catch(t:Throwable){process.destroy();process.destroyForcibly();throw t}
  finally{if(process.isAlive){process.destroy();process.destroyForcibly();try{process.waitFor(500,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){Thread.currentThread().interrupt()}};closeQuietly(process.inputStream);closeQuietly(process.errorStream);joinReader(errorThread,process.errorStream);ownedProcesses.remove(process)}
 }
 private fun copyBounded(input:InputStream,output:ByteArrayOutputStream,truncated:java.util.concurrent.atomic.AtomicBoolean,limit:Int){val buffer=ByteArray(8192);var kept=0;while(true){val n=input.read(buffer);if(n<0)break;val allowed=(limit-kept).coerceAtLeast(0);if(allowed>0){val count=minOf(n,allowed);output.write(buffer,0,count);kept+=count};if(n>allowed)truncated.set(true)}}
 private fun quote(value:String)="'"+value.replace("'","'\\''")+"'"
 companion object {
  private const val MAX_CONCURRENT_COMMANDS=3
  private const val MAX_OUTPUT_BYTES=1024*1024
  private const val MAX_COPY_ERROR_BYTES=64*1024
  private const val CANCELLED_TTL_MS=120_000L
  private const val MAX_CANCELLED_MARKERS=256
 }
 override fun openPty(root: Boolean,sessionId:String): ParcelFileDescriptor {
  val fd=synchronized(lifecycleLock){
   if(destroying)throw RemoteException("Shizuku UserService is stopping")
   val opened=Pty.openPty(root,sessionId)
   if(opened>=0)ptySessions.add(sessionId)
   opened
  }
  if(fd<0)throw RemoteException("openpty failed: errno=${-fd}")
  return ParcelFileDescriptor.adoptFd(fd)
 }
 override fun closePty(sessionId:String){ptySessions.remove(sessionId);Pty.closePty(sessionId)}
 override fun destroy(){
  val running:List<RunningProcess>
  val ptys:List<String>
  val children:List<java.lang.Process>
  synchronized(lifecycleLock){
   if(destroying)return
   destroying=true
   running=processes.values.toList()
   ptys=ptySessions.toList()
   children=ownedProcesses.toList()
   cancelled.clear()
  }
  running.forEach{stopProcess(it)}
  ptys.forEach{try{Pty.closePty(it)}catch(_:Throwable){}}
  children.forEach{process->
   if(process.isAlive){process.destroy();process.destroyForcibly();try{process.waitFor(500,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){Thread.currentThread().interrupt()}}
   closeQuietly(process.inputStream);closeQuietly(process.errorStream);closeQuietly(process.outputStream)
  }
  processes.clear();ownedProcesses.clear();ptySessions.clear()
  System.exit(0)
 }
}
