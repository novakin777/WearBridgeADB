package dev.wearbrdgeadb;
import android.os.ParcelFileDescriptor;
interface ICmdService {
 String exec(String command, boolean root);
 ParcelFileDescriptor openPty(boolean root);
}
