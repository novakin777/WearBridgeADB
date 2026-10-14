package dev.wearbrdgeadb

import android.os.*
import java.io.ByteArrayOutputStream

class ShellUserService : ICmdService.Stub() {
 override fun exec(command: String, root: Boolean): String {
  val cmd=if(root && Process.myUid()!=0) listOf("/system/bin/su","-c",command) else listOf("/system/bin/sh","-c",command)
  val p = ProcessBuilder(cmd).start()
  val out=ByteArrayOutputStream(); val err=ByteArrayOutputStream()
  val t1=Thread { p.inputStream.copyTo(out) }; val t2=Thread { p.errorStream.copyTo(err) }; t1.start(); t2.start()
  val code=p.waitFor(); t1.join(); t2.join()
  return "exitCode=$code\nstdout:\n${out.toString("UTF-8")}\nstderr:\n${err.toString("UTF-8")}"
 }
 override fun openPty(root: Boolean): ParcelFileDescriptor {
  val fd=Pty.openPty(root)
  if(fd<0) throw RemoteException("openpty failed: errno=${-fd}")
  return ParcelFileDescriptor.adoptFd(fd)
 }
}
