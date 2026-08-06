/*
 * JNI bridge for hev-socks5-tunnel library mode.
 */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "hev_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern int hev_socks5_tunnel_main_from_file(const char *config_path, int tun_fd);
extern void hev_socks5_tunnel_quit(void);

static pthread_t g_thread;
static volatile int g_running = 0;
static char *g_config_path = NULL;
static int g_tun_fd = -1;

static void *tunnel_thread(void *arg) {
    (void)arg;
    g_running = 1;
    LOGI("tunnel thread start fd=%d", g_tun_fd);
    int res = hev_socks5_tunnel_main_from_file(g_config_path, g_tun_fd);
    LOGI("tunnel thread end res=%d", res);
    g_running = 0;
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_clearpass_app_tunnel_HevJni_TProxyStartService(JNIEnv *env, jclass clazz,
                                                       jstring configPath, jint fd) {
    if (g_running) {
        LOGE("already running");
        return JNI_FALSE;
    }
    const char *path = (*env)->GetStringUTFChars(env, configPath, NULL);
    if (!path) return JNI_FALSE;
    free(g_config_path);
    g_config_path = strdup(path);
    (*env)->ReleaseStringUTFChars(env, configPath, path);
    g_tun_fd = (int)fd;

    if (pthread_create(&g_thread, NULL, tunnel_thread, NULL) != 0) {
        LOGE("pthread_create failed");
        g_running = 0;
        return JNI_FALSE;
    }
    pthread_detach(g_thread);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_clearpass_app_tunnel_HevJni_TProxyStopService(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    hev_socks5_tunnel_quit();
    g_running = 0;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_clearpass_app_tunnel_HevJni_TProxyIsRunning(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return g_running ? JNI_TRUE : JNI_FALSE;
}
