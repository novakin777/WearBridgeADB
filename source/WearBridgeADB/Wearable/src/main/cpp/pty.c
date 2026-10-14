#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <poll.h>
#include <termios.h>

JNIEXPORT jint JNICALL Java_dev_wearbrdgeadb_Pty_openPty(JNIEnv *env, jclass cls, jboolean root) {
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) || unlockpt(master)) { if (master >= 0) close(master); return -errno; }
    char name[128];
    if (ptsname_r(master, name, sizeof(name))) { int e=errno; close(master); return -e; }
    struct winsize ws = { .ws_row=24, .ws_col=80, .ws_xpixel=0, .ws_ypixel=0 };
    ioctl(master, TIOCSWINSZ, &ws);
    pid_t pid = fork();
    if (pid < 0) { int e=errno; close(master); return -e; }
    if (pid == 0) {
        close(master);
        if (setsid() < 0) _exit(126);
        int slave = open(name, O_RDWR);
        if (slave < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
        dup2(slave, STDIN_FILENO); dup2(slave, STDOUT_FILENO); dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        setenv("TERM", "xterm-256color", 1);
        const char *prompt = (root || geteuid() == 0) ? "PS1=# " : "PS1=$ ";
        setenv("PS1", (root || geteuid() == 0) ? "# " : "$ ", 1);
        char *envp[] = { "PATH=/system/bin:/system/xbin", "HOME=/data/local/tmp", "TERM=xterm-256color", (char *)prompt, NULL };
        if (root && geteuid() != 0) {
            char *argv[] = { "/system/bin/su", "-c", "/system/bin/sh -i", NULL };
            execve(argv[0], argv, envp);
            dprintf(STDERR_FILENO,
                "KernelSU did not resolve virtual /system/bin/su for app uid=%d (errno=%d). "
                "Check jailbreak status and the dev.wearbrdgeadb KernelSU grant.\r\n",
                (int)getuid(), errno);
        } else {
            char *argv[] = { "/system/bin/sh", "-i", NULL };
            execve(argv[0], argv, envp);
        }
        _exit(127);
    }
    return master;
}
JNIEXPORT jint JNICALL Java_dev_wearbrdgeadb_Pty_readPty(JNIEnv *env,jclass cls,jint fd,jbyteArray b,jint off,jint len) {
    jbyte *p=(*env)->GetByteArrayElements(env,b,NULL); if(!p)return -ENOMEM;
    struct pollfd pf={.fd=fd,.events=POLLIN}; int ready; do { ready=poll(&pf,1,100); } while(ready<0 && errno==EINTR); if(ready==0){(*env)->ReleaseByteArrayElements(env,b,p,JNI_ABORT);return 0;} if(ready<0){int e=errno;(*env)->ReleaseByteArrayElements(env,b,p,JNI_ABORT);return -e;}
    ssize_t n; do { n=read(fd,p+off,len); } while(n<0 && errno==EINTR);
    (*env)->ReleaseByteArrayElements(env,b,p,n>0?0:JNI_ABORT); return (jint)n;
}
JNIEXPORT jint JNICALL Java_dev_wearbrdgeadb_Pty_writePty(JNIEnv *env,jclass cls,jint fd,jbyteArray b,jint off,jint len) {
    jbyte *p=(*env)->GetByteArrayElements(env,b,NULL); if(!p)return -ENOMEM;
    ssize_t n; do { n=write(fd,p+off,len); } while(n<0 && errno==EINTR);
    (*env)->ReleaseByteArrayElements(env,b,p,JNI_ABORT); return (jint)n;
}
JNIEXPORT void JNICALL Java_dev_wearbrdgeadb_Pty_closePty(JNIEnv *env,jclass cls,jint fd) { if(fd>=0) close(fd); }
JNIEXPORT jint JNICALL Java_dev_wearbrdgeadb_Pty_resizePty(JNIEnv *env,jclass cls,jint fd,jint rows,jint cols) {
    struct winsize ws={.ws_row=(unsigned short)rows,.ws_col=(unsigned short)cols}; return ioctl(fd,TIOCSWINSZ,&ws);
}
