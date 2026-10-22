#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/wait.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdio.h>

#define TAG "NativeKeepAlive"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C"
JNIEXPORT void JNICALL
Java_cn_ntoskrnl2333_smspost_ForwardingService_initNativeDaemon(JNIEnv *env, jobject thiz, jstring lock_path, jstring package_name) {
    const char *path = env->GetStringUTFChars(lock_path, NULL);
    const char *pkg = env->GetStringUTFChars(package_name, NULL);

    // 1. 主进程加排他锁
    int fd = open(path, O_CREAT | O_RDWR, 0600);
    if (flock(fd, LOCK_EX | LOCK_NB) == -1) {
        LOGD("Lock already held by another process or error.");
        env->ReleaseStringUTFChars(lock_path, path);
        env->ReleaseStringUTFChars(package_name, pkg);
        return;
    }
    // 重新获取锁以阻塞方式（其实已经是独占了）
    flock(fd, LOCK_EX);

    // 2. Double Fork 逃逸进程组
    pid_t pid = fork();
    if (pid < 0) {
        LOGD("First fork failed");
    } else if (pid == 0) {
        // 第一代子进程
        pid_t grandchild = fork();
        if (grandchild < 0) {
            LOGD("Second fork failed");
            _exit(1);
        } else if (grandchild == 0) {
            // 第二代进程（孙子进程），真正的守护进程
            LOGD("Daemon process started, pid: %d", getpid());

            // 脱离控制终端，创建新会话
            setsid();

            // 3. 阻塞式监听文件锁
            // 守护进程尝试获取锁。由于主进程持有锁且未关闭，守护进程会在此阻塞。
            // 当主进程被杀，内核自动释放锁，守护进程被唤醒。
            int monitor_fd = open(path, O_RDWR);
            LOGD("Daemon waiting for lock...");
            flock(monitor_fd, LOCK_EX);

            LOGD("Main process died! Attempting to restart...");

            // 4. 拉起逻辑
            char command[256];
            // 尝试拉起前台服务
            snprintf(command, sizeof(command), "am start-foreground-service %s/.ForwardingService", pkg);
            system(command);

            LOGD("Restart command sent: %s", command);
            _exit(0);
        } else {
            // 第一代子进程立即退出
            _exit(0);
        }
    } else {
        // 主进程继续执行 Java 逻辑
        // 注意：主进程不能在这里 close(fd)，否则锁就没了
    }

    env->ReleaseStringUTFChars(lock_path, path);
    env->ReleaseStringUTFChars(package_name, pkg);
}
