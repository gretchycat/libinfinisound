/* Pull decoding through the FFmpeg C API exported by the pinned libVLC binary.
 * All I/O uses the caller's AudioBytes; no player clock or device output.
 * The exact public-header ABI is checked before touching codec/format structs.
 */
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <errno.h>
#include <stdio.h>
#include <time.h>
#include <math.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/version.h>

#define API(f) static __typeof__(&f) p_##f
API(avcodec_get_class); API(avformat_alloc_context); API(avformat_open_input);
API(avformat_find_stream_info); API(av_find_best_stream); API(avcodec_alloc_context3);
API(avcodec_parameters_to_context); API(avcodec_open2); API(av_frame_alloc);
API(av_packet_alloc); API(avcodec_receive_frame); API(avcodec_send_packet);
API(av_read_frame); API(av_frame_unref); API(av_packet_unref); API(avcodec_free_context);
API(avformat_close_input); API(av_frame_free); API(av_packet_free); API(av_malloc);
API(av_free); API(avio_alloc_context); API(avio_context_free); API(av_dict_set); API(av_dict_free);
static pthread_once_t once=PTHREAD_ONCE_INIT;
static int available;
static void initialize(void) {
    void *lib=dlopen("libvlc.so",RTLD_NOW|RTLD_LOCAL);
    if(!lib)return;
#define LOAD(f) do { *(void **)(&p_##f)=dlsym(lib,#f); if(!p_##f)return; } while(0)
    LOAD(avcodec_get_class);
    if(p_avcodec_get_class()->version!=LIBAVUTIL_VERSION_INT)return;
    LOAD(avformat_alloc_context);LOAD(avformat_open_input);LOAD(avformat_find_stream_info);
    LOAD(av_find_best_stream);LOAD(avcodec_alloc_context3);LOAD(avcodec_parameters_to_context);
    LOAD(avcodec_open2);LOAD(av_frame_alloc);LOAD(av_packet_alloc);LOAD(avcodec_receive_frame);
    LOAD(avcodec_send_packet);LOAD(av_read_frame);LOAD(av_frame_unref);LOAD(av_packet_unref);
    LOAD(avcodec_free_context);LOAD(avformat_close_input);LOAD(av_frame_free);LOAD(av_packet_free);
    LOAD(av_malloc);LOAD(av_free);LOAD(avio_alloc_context);LOAD(avio_context_free);LOAD(av_dict_set);LOAD(av_dict_free);
#undef LOAD
    available=1;
}
typedef struct {
    JNIEnv *env;
    jobject bytes;
    jbyteArray buffer;
    jmethodID read,close;
    int64_t size,position;
    AVFormatContext *format;
    AVIOContext *io;
    AVCodecContext *codec;
    AVFrame *frame;
    AVPacket *packet;
    const AVCodec *decoder;
    int stream,offset,eof,flushed,rate,channels;
} audio;
static void error(JNIEnv *e,const char *message) {
    if(!(*e)->ExceptionCheck(e))(*e)->ThrowNew(e,(*e)->FindClass(e,"java/io/IOException"),message);
}
static int read_input(void *opaque,uint8_t *target,int size) {
    audio *s=opaque;JNIEnv *e=s->env;
    if(s->position>=s->size)return AVERROR_EOF;
    if(size>65536)size=65536;
    if(size>s->size-s->position)size=(int)(s->size-s->position);
    int n=(*e)->CallIntMethod(e,s->bytes,s->read,(jlong)s->position,s->buffer,0,size);
    if((*e)->ExceptionCheck(e)||n<=0||n>size)return AVERROR(EIO);
    (*e)->GetByteArrayRegion(e,s->buffer,0,n,(jbyte *)target);
    if((*e)->ExceptionCheck(e))return AVERROR(EIO);
    s->position+=n;return n;
}
static int64_t seek_input(void *opaque,int64_t offset,int mode) {
    audio *s=opaque;mode&=~AVSEEK_FORCE;
    if(mode==AVSEEK_SIZE)return s->size;
    int64_t base=mode==SEEK_SET?0:mode==SEEK_CUR?s->position:mode==SEEK_END?s->size:-1;
    if(base<0||offset< -base||offset>s->size-base)return AVERROR(EINVAL);
    s->position=base+offset;return s->position;
}
static void dispose(audio *s) {
    JNIEnv *e=s->env;
    // Preserve an input/cancellation exception while closing JNI-owned handles.
    jthrowable pending=(*e)->ExceptionOccurred(e);if(pending)(*e)->ExceptionClear(e);
    if(s->codec)p_avcodec_free_context(&s->codec);
    if(s->frame)p_av_frame_free(&s->frame);
    if(s->packet)p_av_packet_free(&s->packet);
    if(s->format)p_avformat_close_input(&s->format);
    if(s->io){p_av_free(s->io->buffer);p_avio_context_free(&s->io);}
    if(s->bytes){(*e)->CallVoidMethod(e,s->bytes,s->close);if((*e)->ExceptionCheck(e))(*e)->ExceptionClear(e);(*e)->DeleteGlobalRef(e,s->bytes);}
    if(s->buffer)(*e)->DeleteGlobalRef(e,s->buffer);
    if(pending){(*e)->Throw(e,pending);(*e)->DeleteLocalRef(e,pending);}
    free(s);
}
static int next_frame(audio *s) {
    for(int work=0;work<100000;work++) {
        if((*s->env)->ExceptionCheck(s->env))return -1;
        int r=p_avcodec_receive_frame(s->codec,s->frame);
        if(r==0){s->offset=0;return 1;}
        if(r==AVERROR_EOF)return 0;
        if(r!=AVERROR(EAGAIN))return -1;
        if(s->eof){
            if(s->flushed)return -1;
            s->flushed=1;
            if(p_avcodec_send_packet(s->codec,NULL)<0)return -1;
            continue;
        }
        r=p_av_read_frame(s->format,s->packet);
        if((*s->env)->ExceptionCheck(s->env))return -1;
        if(r==AVERROR_EOF){
            if(s->io->error<0&&s->io->error!=AVERROR_EOF)return -1;
            s->eof=1;continue;
        }
        if(r<0)return -1;
        if(s->packet->stream_index==s->stream)r=p_avcodec_send_packet(s->codec,s->packet);
        p_av_packet_unref(s->packet);
        if(r<0)return -1;
    }
    return -1;
}
#define JNI(n) Java_com_infinifolder_audio_LibVlcAvNative_##n
JNIEXPORT jlong JNICALL JNI(open)(JNIEnv *e,jobject self,jobject input) {
    (void)self;pthread_once(&once,initialize);
    if(!available){error(e,"Bundled FFmpeg API/header ABI unavailable");return 0;}
    audio *s=calloc(1,sizeof(*s));if(!s){error(e,"Audio decoder allocation failed");return 0;}s->env=e;
    jclass input_type=(*e)->GetObjectClass(e,input);
    jmethodID open=(*e)->GetMethodID(e,input_type,"open","()Lcom/infinifolder/audio/AudioBytes;");
    jobject bytes=(*e)->CallObjectMethod(e,input,open);
    if((*e)->ExceptionCheck(e)||!bytes)goto failure;
    jclass bytes_type=(*e)->FindClass(e,"com/infinifolder/audio/AudioBytes");
    s->close=(*e)->GetMethodID(e,bytes_type,"close","()V");
    s->read=(*e)->GetMethodID(e,bytes_type,"readAt","(J[BII)I");
    jmethodID size=(*e)->GetMethodID(e,bytes_type,"getSize","()J");
    s->bytes=(*e)->NewGlobalRef(e,bytes);(*e)->DeleteLocalRef(e,bytes);
    s->size=(*e)->CallLongMethod(e,s->bytes,size);
    if((*e)->ExceptionCheck(e)||s->size<=0)goto failure;
    jbyteArray buffer=(*e)->NewByteArray(e,65536);if(!buffer)goto failure;
    s->buffer=(*e)->NewGlobalRef(e,buffer);(*e)->DeleteLocalRef(e,buffer);
    unsigned char *avio=p_av_malloc(65536);if(!avio)goto failure;
    s->io=p_avio_alloc_context(avio,65536,0,s,read_input,NULL,seek_input);
    if(!s->io){p_av_free(avio);goto failure;}
    s->format=p_avformat_alloc_context();if(!s->format)goto failure;
    s->format->pb=s->io;
    AVDictionary *options=NULL;
    p_av_dict_set(&options,"protocol_whitelist","crypto,data",0);
    int result=p_avformat_open_input(&s->format,NULL,NULL,&options);p_av_dict_free(&options);
    if(result<0||(*e)->ExceptionCheck(e))goto failure;
    if(p_avformat_find_stream_info(s->format,NULL)<0||(*e)->ExceptionCheck(e))goto failure;
    s->stream=p_av_find_best_stream(s->format,AVMEDIA_TYPE_AUDIO,-1,-1,&s->decoder,0);
    if(s->stream<0||!s->decoder)goto failure;
    s->codec=p_avcodec_alloc_context3(s->decoder);
    if(!s->codec||p_avcodec_parameters_to_context(s->codec,s->format->streams[s->stream]->codecpar)<0)goto failure;
    s->codec->thread_count=1;
    if(p_avcodec_open2(s->codec,s->decoder,NULL)<0)goto failure;
    s->frame=p_av_frame_alloc();s->packet=p_av_packet_alloc();
    if(!s->frame||!s->packet||next_frame(s)!=1)goto failure;
    s->rate=s->frame->sample_rate;s->channels=s->frame->ch_layout.nb_channels;
    if(s->rate<=0||s->rate>768000||s->channels<1||s->channels>8)goto failure;
    return (jlong)(intptr_t)s;
failure:
    dispose(s);error(e,"Cannot parse/decode this audio source");return 0;
}
JNIEXPORT jlong JNICALL JNI(format)(JNIEnv *e,jobject self,jlong h){(void)e;(void)self;audio *s=(void *)(intptr_t)h;return ((jlong)s->rate<<8)|s->channels;}
JNIEXPORT jstring JNICALL JNI(codec)(JNIEnv *e,jobject self,jlong h){(void)self;return (*e)->NewStringUTF(e,((audio *)(intptr_t)h)->decoder->name);}
JNIEXPORT jlong JNICALL JNI(duration)(JNIEnv *e,jobject self,jlong h) {
    (void)self;audio *s=(void *)(intptr_t)h;s->env=e;
    if(s->format->duration>0)return s->format->duration/1000;
    AVStream *stream=s->format->streams[s->stream];
    if(stream->duration>0&&stream->time_base.num>0&&stream->time_base.den>0){
        long double ms=(long double)stream->duration*stream->time_base.num*1000/stream->time_base.den;
        if(ms>0&&ms<INT64_MAX)return (jlong)ms;
    }
    // Small legacy files (notably raw RealAudio) may have no timing fields.
    // Count only within strict byte/frame/time limits in this metadata-only session.
    if(s->size>65536)return 0;
    int64_t frames=s->frame->nb_samples;
    struct timespec start,now;clock_gettime(CLOCK_MONOTONIC,&start);
    while(frames<=s->rate*10L) {
        clock_gettime(CLOCK_MONOTONIC,&now);
        if((now.tv_sec-start.tv_sec)*1000000000LL+now.tv_nsec-start.tv_nsec>250000000)return 0;
        p_av_frame_unref(s->frame);int r=next_frame(s);
        if(r==0)return frames*1000/s->rate;
        if(r<0||s->frame->sample_rate!=s->rate||s->frame->ch_layout.nb_channels!=s->channels)return 0;
        frames+=s->frame->nb_samples;
    }
    return 0;
}
JNIEXPORT jint JNICALL JNI(read)(JNIEnv *e,jobject self,jlong h,jshortArray target,jint length) {
    (void)self;audio *s=(void *)(intptr_t)h;s->env=e;
    if(length<s->channels||length>32768||length>(*e)->GetArrayLength(e,target)){error(e,"Invalid audio read range");return -1;}
    if(s->offset>=s->frame->nb_samples){p_av_frame_unref(s->frame);int r=next_frame(s);if(r==0)return -1;if(r<0){error(e,"Audio decoding failed");return -1;}}
    AVFrame *f=s->frame;
    if(f->sample_rate!=s->rate||f->ch_layout.nb_channels!=s->channels){error(e,"Changing PCM formats are unsupported");return -1;}
    int frames=length/s->channels;if(frames>f->nb_samples-s->offset)frames=f->nb_samples-s->offset;
    int planar=f->format==AV_SAMPLE_FMT_U8P||f->format==AV_SAMPLE_FMT_S16P||f->format==AV_SAMPLE_FMT_S32P||f->format==AV_SAMPLE_FMT_FLTP||f->format==AV_SAMPLE_FMT_DBLP||f->format==AV_SAMPLE_FMT_S64P;
    short samples[32768];
    for(int i=0;i<frames;i++)for(int ch=0;ch<s->channels;ch++){
        unsigned char *data=planar?f->extended_data[ch]:f->data[0];int at=planar?s->offset+i:(s->offset+i)*s->channels+ch;
        int value;
        switch(f->format){
        case AV_SAMPLE_FMT_U8:case AV_SAMPLE_FMT_U8P:value=((int)data[at]-128)*256;break;
        case AV_SAMPLE_FMT_S16:case AV_SAMPLE_FMT_S16P:value=((int16_t *)data)[at];break;
        case AV_SAMPLE_FMT_S32:case AV_SAMPLE_FMT_S32P:value=((int32_t *)data)[at]>>16;break;
        case AV_SAMPLE_FMT_S64:case AV_SAMPLE_FMT_S64P:value=(int)(((int64_t *)data)[at]>>48);break;
        case AV_SAMPLE_FMT_FLT:case AV_SAMPLE_FMT_FLTP:case AV_SAMPLE_FMT_DBL:case AV_SAMPLE_FMT_DBLP:{
            double n=(f->format==AV_SAMPLE_FMT_FLT||f->format==AV_SAMPLE_FMT_FLTP)?((float *)data)[at]:((double *)data)[at];
            if(!isfinite(n)){error(e,"Invalid floating PCM");return -1;}
            value=n>=1?32767:n<=-1?-32768:(int)lrint(n*32768.0);break;
        }
        default:error(e,"Unsupported decoded PCM representation");return -1;
        }
        if(value>32767)value=32767;else if(value< -32768)value= -32768;
        samples[i*s->channels+ch]=(short)value;
    }
    s->offset+=frames;(*e)->SetShortArrayRegion(e,target,0,frames*s->channels,samples);return frames*s->channels;
}
JNIEXPORT void JNICALL JNI(close)(JNIEnv *e,jobject self,jlong h){(void)self;audio *s=(void *)(intptr_t)h;s->env=e;dispose(s);}
