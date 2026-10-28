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
#include <signal.h>
#include <pthread.h>
#include <time.h>

struct pty_session {
    char *id;
    pid_t child;
    int guard_fd;
    struct pty_session *next;
};
static pthread_mutex_t sessions_lock = PTHREAD_MUTEX_INITIALIZER;
static struct pty_session *sessions = NULL;

static int wait_child_bounded(pid_t child, int milliseconds) {
    const struct timespec pause = { .tv_sec=0, .tv_nsec=10 * 1000 * 1000 };
    int status;
    for (int elapsed=0; elapsed<milliseconds; elapsed+=10) {
        pid_t result=waitpid(child,&status,WNOHANG);
        if (result==child || (result<0 && errno==ECHILD)) return 1;
        if (result<0 && errno!=EINTR) return 1;
        nanosleep(&pause,NULL);
    }
    return 0;
}

JNIEXPORT jint JNICALL Java_dev_wearbrdgeadb_Pty_openPty(JNIEnv *env, jclass cls, jboolean root, jstring session_id) {
    if (!session_id) return -EINVAL;
    const char *id=(*env)->GetStringUTFChars(env,session_id,NULL);
    if (!id) return -ENOMEM;
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) || unlockpt(master)) { int e=errno; if (master >= 0) close(master); (*env)->ReleaseStringUTFChars(env,session_id,id); return -e; }
    char name[128];
    if (ptsname_r(master, name, sizeof(name))) { int e=errno; close(master); (*env)->ReleaseStringUTFChars(env,session_id,id); return -e; }
    struct winsize ws = { .ws_row=24, .ws_col=80, .ws_xpixel=0, .ws_ypixel=0 };
    ioctl(master, TIOCSWINSZ, &ws);
    pid_t pid = fork();
    if (pid < 0) { int e=errno; close(master); (*env)->ReleaseStringUTFChars(env,session_id,id); return -e; }
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
    int guard=fcntl(master,F_DUPFD_CLOEXEC,3);
    struct pty_session *session=guard>=0?calloc(1,sizeof(*session)):NULL;
    if (!session) {
        int e=guard<0?errno:ENOMEM;
        if(guard>=0)close(guard);
        kill(-pid,SIGHUP);kill(pid,SIGHUP);close(master);
        if(!wait_child_bounded(pid,500)){kill(-pid,SIGKILL);kill(pid,SIGKILL);while(waitpid(pid,NULL,0)<0&&errno==EINTR){}}
        (*env)->ReleaseStringUTFChars(env,session_id,id);
        return -e;
    }
    session->id=strdup(id);session->child=pid;session->guard_fd=guard;
    (*env)->ReleaseStringUTFChars(env,session_id,id);
    if(!session->id){close(guard);close(master);kill(-pid,SIGKILL);kill(pid,SIGKILL);while(waitpid(pid,NULL,0)<0&&errno==EINTR){};free(session);return -ENOMEM;}
    pthread_mutex_lock(&sessions_lock);session->next=sessions;sessions=session;pthread_mutex_unlock(&sessions_lock);
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
JNIEXPORT void JNICALL Java_dev_wearbrdgeadb_Pty_closePty(JNIEnv *env,jclass cls,jstring session_id) {
    if(!session_id)return;
    const char *id=(*env)->GetStringUTFChars(env,session_id,NULL);if(!id)return;
    pthread_mutex_lock(&sessions_lock);
    struct pty_session **link=&sessions;
    while(*link && strcmp((*link)->id,id)!=0)link=&(*link)->next;
    struct pty_session *session=*link;
    if(session)*link=session->next;
    pthread_mutex_unlock(&sessions_lock);
    (*env)->ReleaseStringUTFChars(env,session_id,id);
    if(!session)return;
    if(session->guard_fd>=0)close(session->guard_fd);
    kill(-session->child,SIGHUP);
    kill(session->child,SIGHUP);
    int child_reaped=wait_child_bounded(session->child,500);
    if(kill(-session->child,0)==0){
        kill(-session->child,SIGTERM);kill(session->child,SIGTERM);
        if(!child_reaped)child_reaped=wait_child_bounded(session->child,250);
        if(kill(-session->child,0)==0){
            kill(-session->child,SIGKILL);kill(session->child,SIGKILL);
            if(!child_reaped)while(waitpid(session->child,NULL,0)<0&&errno==EINTR){}
        }
    }else if(!child_reaped){
        kill(session->child,SIGKILL);
        while(waitpid(session->child,NULL,0)<0&&errno==EINTR){}
    }
    free(session->id);free(session);
}
JNIEXPORT jint JNICALL Java_dev_wearbrdgeadb_Pty_resizePty(JNIEnv *env,jclass cls,jint fd,jint rows,jint cols) {
    struct winsize ws={.ws_row=(unsigned short)rows,.ws_col=(unsigned short)cols}; return ioctl(fd,TIOCSWINSZ,&ws);
}
JNIEXPORT jint JNICALL Java_dev_wearbrdgeadb_Pty_signalProcessGroup(JNIEnv *env,jclass cls,jint processGroupId,jint signal) {
    if (processGroupId <= 1 || signal <= 0) return -EINVAL;
    if (kill(-(pid_t)processGroupId, signal) == 0) return 0;
    return -errno;
}
