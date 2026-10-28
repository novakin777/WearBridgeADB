package dev.wearbrdgeadb;
import android.os.ParcelFileDescriptor;
interface ICmdService {
 String exec(String command, boolean root) = 0;
 String execJob(String requestId, String command, boolean root) = 1;
 boolean cancel(String requestId) = 2;
 String copyToFile(in ParcelFileDescriptor source, String target) = 3;
 String copyFromFile(String source, in ParcelFileDescriptor destination) = 4;
 ParcelFileDescriptor openPty(boolean root, String sessionId) = 5;
 void closePty(String sessionId) = 6;
 void destroy() = 16777114;
}
