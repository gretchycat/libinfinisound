/* Optional FluidLite/GME C APIs exported by the pinned libVLC AAR.
 * Original adapter licensing follows the project; upstream engines are LGPL.
 * All FluidLite calls are serialized: shared SF2 sample reference counts are mutable.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <dlfcn.h>

typedef void fluid_settings;
typedef void fluid_synth;
typedef void fluid_sfont;
static fluid_settings *(*new_settings)(void);
static void (*delete_settings)(fluid_settings *);
static int (*setnum)(fluid_settings *, const char *, double);
static int (*setint)(fluid_settings *, const char *, int);
static fluid_synth *(*new_synth)(fluid_settings *);
static void (*delete_synth)(fluid_synth *);
static int (*sfload)(fluid_synth *,const char *,int);
static fluid_sfont *(*get_sfont)(fluid_synth *,unsigned);
static int (*add_sfont)(fluid_synth *,fluid_sfont *);
static void (*remove_sfont)(fluid_synth *,fluid_sfont *);
static int (*all_off)(fluid_synth *,int);
static int (*noteon)(fluid_synth *,int,int,int), (*noteoff)(fluid_synth *,int,int);
static int (*cc)(fluid_synth *,int,int,int), (*program)(fluid_synth *,int,int);
static int (*bend)(fluid_synth *,int,int), (*pressure)(fluid_synth *,int,int), (*key_pressure)(fluid_synth *,int,int,int);
static int (*sysex)(fluid_synth *,const char *,int,char *,int *,int *,int);
static int (*write_s16)(fluid_synth *,int,void *,int,int,void *,int,int);
static pthread_mutex_t fluid_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t fluid_once = PTHREAD_ONCE_INIT;
static int fluid_ready, bank_loads;
static void init_fluid(void) {
    void *h=dlopen("libvlc.so",RTLD_NOW|RTLD_LOCAL); if(!h)return;
#define F(v,n) do{*(void **)(&v)=dlsym(h,n);if(!v)return;}while(0)
    F(new_settings,"new_fluid_settings"); F(delete_settings,"delete_fluid_settings");
    F(setnum,"fluid_settings_setnum"); F(setint,"fluid_settings_setint");
    F(new_synth,"new_fluid_synth"); F(delete_synth,"delete_fluid_synth");
    F(sfload,"fluid_synth_sfload"); F(get_sfont,"fluid_synth_get_sfont");
    F(add_sfont,"fluid_synth_add_sfont"); F(remove_sfont,"fluid_synth_remove_sfont");
    F(all_off,"fluid_synth_all_sounds_off"); F(noteon,"fluid_synth_noteon"); F(noteoff,"fluid_synth_noteoff");
    F(cc,"fluid_synth_cc"); F(program,"fluid_synth_program_change"); F(bend,"fluid_synth_pitch_bend");
    F(pressure,"fluid_synth_channel_pressure"); F(key_pressure,"fluid_synth_key_pressure");
    F(sysex,"fluid_synth_sysex"); F(write_s16,"fluid_synth_write_s16");
#undef F
    fluid_ready=1;
}
typedef struct bank { char *path; fluid_settings *settings; fluid_synth *owner; fluid_sfont *font; int users; struct bank *next; } bank;
static bank *banks, *resident;
typedef struct { bank *bank; fluid_settings *settings; fluid_synth *synth; } midi_session;
static void error(JNIEnv *e,const char *s){(*e)->ThrowNew(e,(*e)->FindClass(e,"java/io/IOException"),s);}
static fluid_settings *settings(void){
    fluid_settings *s=new_settings(); if(s){setnum(s,"synth.sample-rate",44100);setnum(s,"synth.gain",0.5);setint(s,"synth.polyphony",256);}return s;
}
static void collect(void){
    bank **at=&banks;
    while(*at){bank *b=*at;if(b!=resident&&!b->users){*at=b->next;delete_synth(b->owner);delete_settings(b->settings);free(b->path);free(b);}else at=&b->next;}
}
#define MIDI(n) Java_com_infinifolder_audio_FluidLiteNative_##n
JNIEXPORT jlong JNICALL MIDI(open)(JNIEnv *e,jobject self,jstring path){
    (void)self;pthread_once(&fluid_once,init_fluid);
    if(!fluid_ready){error(e,"Bundled FluidLite API unavailable");return 0;}
    const char *p=(*e)->GetStringUTFChars(e,path,NULL);if(!p)return 0;
    pthread_mutex_lock(&fluid_lock);
    bank *b=banks;while(b&&strcmp(b->path,p))b=b->next;
    if(!b){
        b=calloc(1,sizeof(*b));
        if(b){b->path=strdup(p);b->settings=settings();if(b->settings)b->owner=new_synth(b->settings);}
        if(!b||!b->path||!b->owner||sfload(b->owner,p,1)<0||!(b->font=get_sfont(b->owner,0))){
            if(b){if(b->owner)delete_synth(b->owner);if(b->settings)delete_settings(b->settings);free(b->path);free(b);}b=NULL;
        }else{b->next=banks;banks=b;bank_loads++;}
    }
    (*e)->ReleaseStringUTFChars(e,path,p);
    midi_session *s=b?calloc(1,sizeof(*s)):NULL;
    if(s){s->settings=settings();if(s->settings)s->synth=new_synth(s->settings);}
    if(!s||!s->synth){if(s){if(s->settings)delete_settings(s->settings);free(s);}collect();pthread_mutex_unlock(&fluid_lock);error(e,"Cannot load MIDI synthesizer/SoundFont");return 0;}
    // Fresh synths each assign id 1. The resident owner keeps the font alive;
    // remove it from each borrower before deleting that synth.
    add_sfont(s->synth,b->font);s->bank=b;b->users++;resident=b;collect();
    pthread_mutex_unlock(&fluid_lock);return (jlong)(intptr_t)s;
}
JNIEXPORT void JNICALL MIDI(event)(JNIEnv *e,jobject self,jlong h,jbyteArray data){
    (void)self;midi_session *s=(void *)(intptr_t)h;jsize n=(*e)->GetArrayLength(e,data);if(n<1)return;
    jbyte *p=(*e)->GetByteArrayElements(e,data,NULL);if(!p)return;
    int status=(unsigned char)p[0],ch=status&15,a=n>1?(unsigned char)p[1]:0,b=n>2?(unsigned char)p[2]:0;
    pthread_mutex_lock(&fluid_lock);
    switch(status&240){case 128:noteoff(s->synth,ch,a);break;case 144:if(b)noteon(s->synth,ch,a,b);else noteoff(s->synth,ch,a);break;
    case 160:key_pressure(s->synth,ch,a,b);break;case 176:cc(s->synth,ch,a,b);break;case 192:program(s->synth,ch,a);break;
    case 208:pressure(s->synth,ch,a);break;case 224:bend(s->synth,ch,a|(b<<7));break;
    case 240:if(status==240)sysex(s->synth,(const char *)p+1,n-1,NULL,NULL,NULL,0);break;}
    pthread_mutex_unlock(&fluid_lock);(*e)->ReleaseByteArrayElements(e,data,p,JNI_ABORT);
}
JNIEXPORT void JNICALL MIDI(render)(JNIEnv *e,jobject self,jlong h,jshortArray data,jint frames){
    (void)self;if(frames<0||frames>32768||frames>(*e)->GetArrayLength(e,data)/2){error(e,"Invalid MIDI render size");return;}
    short samples[65536];midi_session *s=(void *)(intptr_t)h;
    pthread_mutex_lock(&fluid_lock);int r=write_s16(s->synth,frames,samples,0,2,samples,1,2);pthread_mutex_unlock(&fluid_lock);
    if(r<0){error(e,"MIDI synthesis failed");return;}(*e)->SetShortArrayRegion(e,data,0,frames*2,samples);
}
JNIEXPORT void JNICALL MIDI(close)(JNIEnv *e,jobject self,jlong h){
    (void)e;(void)self;midi_session *s=(void *)(intptr_t)h;if(!s)return;
    pthread_mutex_lock(&fluid_lock);for(int ch=0;ch<16;ch++)all_off(s->synth,ch);
    remove_sfont(s->synth,s->bank->font);delete_synth(s->synth);delete_settings(s->settings);s->bank->users--;free(s);collect();pthread_mutex_unlock(&fluid_lock);
}
JNIEXPORT jint JNICALL MIDI(bankLoads)(JNIEnv *e,jobject self){(void)e;(void)self;pthread_mutex_lock(&fluid_lock);int n=bank_loads;pthread_mutex_unlock(&fluid_lock);return n;}

/* Real game-music emulation with a finite, declared playback window for loops. */
typedef void Music_Emu;
typedef struct { int length,intro_length,loop_length,play_length,other[12]; const char *system,*game,*song,*author,*copyright,*comment,*dumper; } game_info;
static const char *(*gopen)(const void *,long,Music_Emu **,int);
static const char *(*gstart)(Music_Emu *,int), *(*gplay)(Music_Emu *,int,short *);
static void (*gdelete)(Music_Emu *),(*gfade)(Music_Emu *,int),(*gignore)(Music_Emu *,int);
static const char *(*ginfo)(const Music_Emu *,game_info **,int);static void (*gfree)(game_info *);
static pthread_once_t game_once=PTHREAD_ONCE_INIT;static int game_ready;
static void init_game(void){void *h=dlopen("libvlc.so",RTLD_NOW|RTLD_LOCAL);if(!h)return;
#define G(v,n) do{*(void **)(&v)=dlsym(h,n);if(!v)return;}while(0)
    G(gopen,"gme_open_data");G(gstart,"gme_start_track");G(gplay,"gme_play");G(gdelete,"gme_delete");G(gfade,"gme_set_fade");G(gignore,"gme_ignore_silence");G(ginfo,"gme_track_info");G(gfree,"gme_free_info");
#undef G
    game_ready=1;
}
typedef struct {Music_Emu *emu;int milliseconds;} game_session;
#define GAME(n) Java_com_infinifolder_audio_GameMusicNative_##n
JNIEXPORT jlong JNICALL GAME(open)(JNIEnv *e,jobject self,jbyteArray data){
    (void)self;pthread_once(&game_once,init_game);if(!game_ready){error(e,"Bundled game music API unavailable");return 0;}
    game_session *s=calloc(1,sizeof(*s));if(!s){error(e,"Game music allocation failed");return 0;}
    jbyte *p=(*e)->GetByteArrayElements(e,data,NULL);if(!p){free(s);return 0;}
    int track=(*e)->GetArrayLength(e,data)>8&&!memcmp(p,"NESM",4)?((unsigned char)p[7]>0?(unsigned char)p[7]-1:0):0;
    const char *failure=gopen(p,(*e)->GetArrayLength(e,data),&s->emu,44100);
    (*e)->ReleaseByteArrayElements(e,data,p,JNI_ABORT);
    if(!failure)failure=gstart(s->emu,track);
    if(failure){if(s->emu)gdelete(s->emu);free(s);error(e,"Cannot decode game music source");return 0;}
    game_info *info=NULL;int length=150000;
    if(!ginfo(s->emu,&info,track)&&info){if(info->play_length>0)length=info->play_length;gfree(info);}
    if(length>24*3600*1000-8000){gdelete(s->emu);free(s);error(e,"Game music timeline exceeds limit");return 0;}
    s->milliseconds=length+8000;gignore(s->emu,1);gfade(s->emu,length);
    return (jlong)(intptr_t)s;
}
JNIEXPORT jint JNICALL GAME(duration)(JNIEnv *e,jobject self,jlong h){(void)e;(void)self;return ((game_session *)(intptr_t)h)->milliseconds;}
JNIEXPORT void JNICALL GAME(render)(JNIEnv *e,jobject self,jlong h,jshortArray data,jint samples){
    (void)self;if(samples<0||samples>65536||samples>(*e)->GetArrayLength(e,data)){error(e,"Invalid game music render size");return;}
    short pcm[65536];const char *r=gplay(((game_session *)(intptr_t)h)->emu,samples,pcm);if(r){error(e,"Game music emulation failed");return;}(*e)->SetShortArrayRegion(e,data,0,samples,pcm);
}
JNIEXPORT void JNICALL GAME(close)(JNIEnv *e,jobject self,jlong h){(void)e;(void)self;game_session *s=(void *)(intptr_t)h;if(s){gdelete(s->emu);free(s);}}
