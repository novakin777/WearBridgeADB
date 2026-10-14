package dev.wearbrdgeadb

import android.os.ParcelFileDescriptor

object Pty {
 init { System.loadLibrary("wearpty") }
 @JvmStatic external fun openPty(root: Boolean): Int
 @JvmStatic external fun readPty(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
 @JvmStatic external fun writePty(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
 @JvmStatic external fun closePty(fd: Int)
 @JvmStatic external fun resizePty(fd: Int, rows: Int, columns: Int): Int
}
