/* libinfinisound's libVLC PCM adapter. No device output is opened.
 * Uses the public libVLC 3 ABI (VideoLAN LGPL-2.1-or-later); see packaged notices.
 * Original adapter licensing follows the containing project.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>
#include <time.h>
#include <dlfcn.h>
#include <sys/types.h>

typedef struct libvlc_instance_t vlc_instance;
typedef struct libvlc_media_t vlc_media;
typedef struct libvlc_media_player_t vlc_player;
static vlc_instance *(*vlc_new)(int, const char *const *);
static void (*vlc_release)(vlc_instance *);
static vlc_media *(*media_callbacks)(vlc_instance *, int (*)(void *,void **,uint64_t *), ssize_t (*)(void *,unsigned char *,size_t), int (*)(void *,uint64_t), void (*)(void *), void *);
static void (*media_option)(vlc_media *,const char *);
static void (*media_release)(vlc_media *);
static vlc_player *(*player_new)(vlc_media *);
static int (*player_play)(vlc_player *);
static void (*player_stop)(vlc_player *);
static void (*player_release)(vlc_player *);
static int (*player_state)(vlc_player *);
static void (*audio_callbacks)(vlc_player *,void (*)(void *,const void *,unsigned,int64_t),void (*)(void *,int64_t),void (*)(void *,int64_t),void (*)(void *,int64_t),void (*)(void *),void *);
static void (*audio_format_callbacks)(vlc_player *,int (*)(void **,char *,unsigned *,unsigned *),void (*)(void *));
static pthread_once_t api_once = PTHREAD_ONCE_INIT;
static int api_ready;
static void load_api(void) {
    void *handle = dlopen("libvlc.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) return;
#define SYM(target, name) do { *(void **)(&target) = dlsym(handle, name); if (!target) return; } while (0)
    SYM(vlc_new,"libvlc_new"); SYM(vlc_release,"libvlc_release");
    SYM(media_callbacks,"libvlc_media_new_callbacks"); SYM(media_option,"libvlc_media_add_option"); SYM(media_release,"libvlc_media_release");
    SYM(player_new,"libvlc_media_player_new_from_media"); SYM(player_play,"libvlc_media_player_play");
    SYM(player_stop,"libvlc_media_player_stop"); SYM(player_release,"libvlc_media_player_release"); SYM(player_state,"libvlc_media_player_get_state");
    SYM(audio_callbacks,"libvlc_audio_set_callbacks"); SYM(audio_format_callbacks,"libvlc_audio_set_format_callbacks");
#undef SYM
    api_ready = 1;
}

#define CAPACITY (512 * 1024)
typedef struct {
    JavaVM *vm;
    jobject input;
    jmethodID open, size, read, close;
    vlc_instance *engine;
    vlc_player *player;
    pthread_mutex_t mutex;
    pthread_cond_t changed;
    unsigned rate, channels;
    int closed, failed, drained;
    short *queue;
    size_t head, used;
    char error[256];
} session;
typedef struct {
    session *owner;
    jobject bytes;
    jbyteArray buffer;
    uint64_t position, size;
} input_cursor;

static JNIEnv *environment(session *s, int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if ((*s->vm)->GetEnv(s->vm,(void **)&env,JNI_VERSION_1_6) != JNI_OK) {
        if ((*s->vm)->AttachCurrentThread(s->vm,(void *)&env,NULL) != JNI_OK) return NULL;
        *attached = 1;
    }
    return env;
}
static void leave(session *s, int attached) { if (attached) (*s->vm)->DetachCurrentThread(s->vm); }
static void fail(session *s, const char *message) {
    pthread_mutex_lock(&s->mutex);
    s->failed = 1;
    snprintf(s->error,sizeof(s->error),"%s",message);
    pthread_cond_broadcast(&s->changed);
    pthread_mutex_unlock(&s->mutex);
}
static int exception(session *s, JNIEnv *env, const char *message) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionClear(env);
    fail(s,message);
    return 1;
}
static int input_open(void *opaque, void **data, uint64_t *size) {
    session *s = opaque;
    int attached;
    JNIEnv *env = environment(s,&attached);
    if (!env) return -1;
    input_cursor *cursor = calloc(1,sizeof(*cursor));
    if (!cursor) { leave(s,attached); return -1; }
    cursor->owner = s;
    jobject bytes = (*env)->CallObjectMethod(env,s->input,s->open);
    if (exception(s,env,"Encoded audio input could not be opened") || !bytes) goto error;
    cursor->bytes = (*env)->NewGlobalRef(env,bytes);
    (*env)->DeleteLocalRef(env,bytes);
    jlong length = (*env)->CallLongMethod(env,cursor->bytes,s->size);
    if (exception(s,env,"Encoded audio length is unavailable") || length < 0) goto error;
    jbyteArray buffer = (*env)->NewByteArray(env,65536);
    if (!buffer) { exception(s,env,"Encoded audio buffer allocation failed"); goto error; }
    cursor->buffer = (*env)->NewGlobalRef(env,buffer);
    (*env)->DeleteLocalRef(env,buffer);
    cursor->size = (uint64_t)length;
    *size = cursor->size;
    *data = cursor;
    leave(s,attached);
    return 0;
error:
    if (cursor->bytes) {
        (*env)->CallVoidMethod(env,cursor->bytes,s->close);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env,cursor->bytes);
    }
    free(cursor); leave(s,attached); return -1;
}
static ssize_t input_read(void *opaque, unsigned char *output, size_t length) {
    if (length == 0) return 0;
    input_cursor *cursor = opaque;
    session *s = cursor->owner;
    pthread_mutex_lock(&s->mutex);
    int closed = s->closed;
    pthread_mutex_unlock(&s->mutex);
    if (closed) return -1;
    if (cursor->position >= cursor->size) return 0;
    if (length > 65536) length = 65536;
    if (length > cursor->size - cursor->position) length = cursor->size - cursor->position;
    int attached;
    JNIEnv *env = environment(s,&attached);
    if (!env) return -1;
    jint count = (*env)->CallIntMethod(env,cursor->bytes,s->read,(jlong)cursor->position,cursor->buffer,0,(jint)length);
    if (exception(s,env,"Encoded audio read failed") || count <= 0 || (size_t)count > length) {
        fail(s,"Incomplete encoded audio input"); leave(s,attached); return -1;
    }
    (*env)->GetByteArrayRegion(env,cursor->buffer,0,count,(jbyte *)output);
    if (exception(s,env,"Encoded audio copy failed")) { leave(s,attached); return -1; }
    cursor->position += count;
    leave(s,attached);
    return count;
}
static int input_seek(void *opaque, uint64_t position) {
    input_cursor *cursor = opaque;
    if (position > cursor->size) return -1;
    cursor->position = position;
    return 0;
}
static void input_close(void *opaque) {
    input_cursor *cursor = opaque;
    session *s = cursor->owner;
    int attached;
    JNIEnv *env = environment(s,&attached);
    if (env) {
        (*env)->CallVoidMethod(env,cursor->bytes,s->close);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env,cursor->buffer);
        (*env)->DeleteGlobalRef(env,cursor->bytes);
    }
    leave(s,attached); free(cursor);
}
static int setup(void **opaque, char *format, unsigned *rate, unsigned *channels) {
    session *s = *opaque;
    if (!*rate || *rate > 768000 || !*channels || *channels > 8) { fail(s,"Unsupported decoded audio format"); return -1; }
    pthread_mutex_lock(&s->mutex);
    if (s->rate && (s->rate != *rate || s->channels != *channels)) {
        pthread_mutex_unlock(&s->mutex); fail(s,"Changing PCM formats are unsupported"); return -1;
    }
    memcpy(format,"S16N",4);
    s->rate = *rate; s->channels = *channels;
    pthread_cond_broadcast(&s->changed);
    pthread_mutex_unlock(&s->mutex);
    return 0;
}
static void play_pcm(void *opaque, const void *data, unsigned frames, int64_t pts) {
    (void)pts;
    session *s = opaque;
    const short *samples = data;
    size_t count = (size_t)frames * s->channels;
    pthread_mutex_lock(&s->mutex);
    while (count && !s->closed && !s->failed) {
        while (s->used == CAPACITY && !s->closed && !s->failed) pthread_cond_wait(&s->changed,&s->mutex);
        if (s->closed || s->failed) break;
        size_t tail = (s->head + s->used) % CAPACITY;
        size_t n = CAPACITY - s->used;
        if (n > CAPACITY - tail) n = CAPACITY - tail;
        if (n > count) n = count;
        memcpy(s->queue + tail,samples,n * sizeof(short));
        s->used += n; samples += n; count -= n;
        pthread_cond_broadcast(&s->changed);
    }
    pthread_mutex_unlock(&s->mutex);
}
static void flush_pcm(void *opaque, int64_t pts) {
    (void)pts;
    session *s = opaque;
    pthread_mutex_lock(&s->mutex); s->head = s->used = 0;
    pthread_cond_broadcast(&s->changed); pthread_mutex_unlock(&s->mutex);
}
static void drain_pcm(void *opaque) {
    session *s = opaque;
    pthread_mutex_lock(&s->mutex); s->drained = 1;
    pthread_cond_broadcast(&s->changed); pthread_mutex_unlock(&s->mutex);
}
static void wait_once(session *s) {
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME,&deadline);
    deadline.tv_nsec += 250000000;
    if (deadline.tv_nsec >= 1000000000) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000; }
    pthread_cond_timedwait(&s->changed,&s->mutex,&deadline);
}
static void destroy(JNIEnv *env, session *s) {
    pthread_mutex_lock(&s->mutex); s->closed = 1;
    pthread_cond_broadcast(&s->changed); pthread_mutex_unlock(&s->mutex);
    if (s->player) { player_stop(s->player); player_release(s->player); }
    if (s->engine) vlc_release(s->engine);
    if (s->input) (*env)->DeleteGlobalRef(env,s->input);
    pthread_cond_destroy(&s->changed); pthread_mutex_destroy(&s->mutex);
    free(s->queue); free(s);
}
static void io_error(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),message);
}
#define JNI(name) Java_com_infinifolder_audio_LibVlcPcmNative_##name
JNIEXPORT jlong JNICALL JNI(open)(JNIEnv *env, jobject self, jobject input, jstring font) {
    (void)self;
    pthread_once(&api_once,load_api);
    if (!api_ready) { io_error(env,"libVLC 3 PCM callbacks are unavailable"); return 0; }
    session *s = calloc(1,sizeof(*s));
    if (!s) { io_error(env,"PCM session allocation failed"); return 0; }
    pthread_mutex_init(&s->mutex,NULL); pthread_cond_init(&s->changed,NULL);
    (*env)->GetJavaVM(env,&s->vm);
    s->input = (*env)->NewGlobalRef(env,input);
    jclass input_class = (*env)->FindClass(env,"com/infinifolder/audio/AudioInput");
    jclass bytes_class = (*env)->FindClass(env,"com/infinifolder/audio/AudioBytes");
    s->open = (*env)->GetMethodID(env,input_class,"open","()Lcom/infinifolder/audio/AudioBytes;");
    s->size = (*env)->GetMethodID(env,bytes_class,"getSize","()J");
    s->read = (*env)->GetMethodID(env,bytes_class,"readAt","(J[BII)I");
    s->close = (*env)->GetMethodID(env,bytes_class,"close","()V");
    s->queue = malloc(CAPACITY * sizeof(short));
    const char *options[] = {"--ignore-config","--no-video","--no-spu","--no-osd","--no-video-title-show","--no-audio-time-stretch","--intf=dummy","--quiet","--no-metadata-network-access"};
    s->engine = vlc_new(sizeof(options)/sizeof(*options),options);
    if (!s->engine || !s->queue) { destroy(env,s); io_error(env,"libVLC decoder initialization failed"); return 0; }
    vlc_media *media = media_callbacks(s->engine,input_open,input_read,input_seek,input_close,s);
    if (!media) { destroy(env,s); io_error(env,"libVLC media callbacks failed"); return 0; }
    media_option(media,":no-sub-autodetect-file"); media_option(media,":clock-jitter=0"); media_option(media,":clock-synchro=0");
    media_option(media,":file-caching=0"); media_option(media,":network-caching=0");
    if (font) {
        const char *path = (*env)->GetStringUTFChars(env,font,NULL);
        size_t n = strlen(path) + 12;
        char *option = malloc(n);
        if (option) { snprintf(option,n,":soundfont=%s",path); media_option(media,option); free(option); }
        (*env)->ReleaseStringUTFChars(env,font,path);
    }
    s->player = player_new(media); media_release(media);
    if (!s->player) { destroy(env,s); io_error(env,"libVLC audio decoder creation failed"); return 0; }
    audio_callbacks(s->player,play_pcm,NULL,NULL,flush_pcm,drain_pcm,s);
    audio_format_callbacks(s->player,setup,NULL);
    if (player_play(s->player) < 0) { destroy(env,s); io_error(env,"libVLC could not decode this source"); return 0; }
    return (jlong)(intptr_t)s;
}
JNIEXPORT jlong JNICALL JNI(format)(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    session *s = (session *)(intptr_t)handle;
    pthread_mutex_lock(&s->mutex);
    if (!s->used && !s->failed && !s->drained) wait_once(s);
    int failed = s->failed;
    jlong result = s->used && s->rate ? ((jlong)s->rate << 8) | s->channels : 0;
    int ended = s->drained;
    pthread_mutex_unlock(&s->mutex);
    int state = player_state(s->player);
    if (failed || state == 7) { io_error(env,failed ? s->error : "libVLC could not decode this audio"); return -1; }
    if (!result && (ended || state == 6)) { io_error(env,"libVLC decoded no audio"); return -1; }
    return result;
}
JNIEXPORT jint JNICALL JNI(read)(JNIEnv *env, jobject self, jlong handle, jshortArray output, jint length) {
    (void)self;
    session *s = (session *)(intptr_t)handle;
    if (length < 0 || length > (*env)->GetArrayLength(env,output)) { io_error(env,"Invalid PCM read range"); return -1; }
    pthread_mutex_lock(&s->mutex);
    if (!s->used && !s->failed && !s->drained && !s->closed) wait_once(s);
    if (s->failed) { pthread_mutex_unlock(&s->mutex); io_error(env,s->error); return -1; }
    size_t n = s->used < (size_t)length ? s->used : (size_t)length;
    n -= n % s->channels;
    size_t first = n < CAPACITY - s->head ? n : CAPACITY - s->head;
    if (first) (*env)->SetShortArrayRegion(env,output,0,first,s->queue + s->head);
    if (n > first) (*env)->SetShortArrayRegion(env,output,first,n - first,s->queue);
    s->head = (s->head + n) % CAPACITY; s->used -= n;
    int ended = s->drained || s->closed;
    pthread_cond_broadcast(&s->changed); pthread_mutex_unlock(&s->mutex);
    if (n) return n;
    int state = player_state(s->player);
    if (state == 7) { io_error(env,"libVLC audio decoding failed"); return -1; }
    return ended || state == 6 ? -1 : 0;
}
JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    destroy(env,(session *)(intptr_t)handle);
}
