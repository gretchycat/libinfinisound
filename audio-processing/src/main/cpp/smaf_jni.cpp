#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <vector>
#include "third_party/smaf/smaf_file.h"
#include "third_party/smaf/ma_player.h"

namespace {
constexpr int rate = 44100;
struct Stream {
    fxchain::smaf::MaPlayer player;
    uint64_t position = 0, frames = 0;
    std::array<float, 8192> audio{};
    std::array<jint, 8192> pcm{};
};
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
uint32_t be32(const uint8_t* p) {
    return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3];
}
void validate(const std::vector<uint8_t>& data) {
    if (!fxchain::smaf::SmafFile::looksLikeSmaf(data.data(), data.size()))
        throw std::runtime_error("Invalid SMAF container");
    size_t end = std::min(data.size(), size_t(be32(data.data()+4))+8), pos = 8;
    int tracks = 0;
    while (pos + 8 <= end) {
        const uint8_t* p = data.data()+pos;
        size_t length = be32(p+4);
        if (length > end-pos-8) throw std::runtime_error("Truncated SMAF chunk");
        if ((p[0]=='M' && p[1]=='T' && p[2]=='R') || (p[0]=='A' && p[1]=='T' && p[2]=='R'))
            if (++tracks > 32) throw std::runtime_error("Too many SMAF tracks");
        pos += 8 + length;
    }
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_infinifolder_audio_SmafNative_open(JNIEnv* env, jobject, jbyteArray bytes) {
    try {
        if (!bytes) throw std::runtime_error("Missing SMAF input");
        int size = env->GetArrayLength(bytes);
        if (size < 16 || size > 4*1024*1024) throw std::runtime_error("SMAF input exceeds 4 MiB limit");
        std::vector<uint8_t> data(size);
        env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(data.data()));
        if (env->ExceptionCheck()) return 0;
        validate(data);
        fxchain::smaf::SmafFile file;
        if (!file.parse(data.data(), data.size())) throw std::runtime_error("Invalid SMAF file");
        auto stream = std::make_unique<Stream>();
        if (!stream->player.init(file, rate)) throw std::runtime_error("Unsupported SMAF score or parsing limit exceeded");
        // One second in the engine's nominal end, one additional second for release.
        // Output is exactly this finite window, including silence after voices finish.
        stream->frames = stream->player.totalSamples() + rate;
        return reinterpret_cast<jlong>(stream.release());
    } catch (const std::exception& e) { fail(env,e.what()); return 0; }
      catch (...) { fail(env,"SMAF initialization failed"); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_infinifolder_audio_SmafNative_frames(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Stream*>(handle);
    if (!s) { fail(env,"SMAF stream is closed"); return 0; }
    return static_cast<jlong>(s->frames);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_infinifolder_audio_SmafNative_read(JNIEnv* env, jobject, jlong handle, jintArray samples, jint offset, jint length) {
    auto* s = reinterpret_cast<Stream*>(handle);
    if (!s || !samples) { fail(env,"SMAF stream is closed"); return -1; }
    int capacity = env->GetArrayLength(samples);
    if (offset < 0 || length < 0 || offset > capacity-length || (length > 0 && length < 2)) {
        fail(env,"Invalid SMAF PCM range"); return -1;
    }
    if (!length) return 0;
    if (s->position >= s->frames) return -1;
    int frames = int(std::min<uint64_t>(std::min(length/2,4096),s->frames-s->position));
    try {
        int produced = s->player.render(s->audio.data(),frames);
        if (produced < 0 || produced > frames) throw std::runtime_error("Invalid SMAF render count");
        std::fill(s->audio.begin()+produced*2,s->audio.begin()+frames*2,0.0f);
        for (int i=0;i<frames*2;i++) {
            double sample = s->audio[i];
            if (!std::isfinite(sample)) throw std::runtime_error("Invalid SMAF sample");
            s->pcm[i] = static_cast<jint>(std::clamp(sample,-1.0,1.0)*2147483647.0);
        }
        env->SetIntArrayRegion(samples,offset,frames*2,s->pcm.data());
        if (env->ExceptionCheck()) return -1;
        s->position += frames;
        return frames*2;
    } catch (const std::exception& e) { fail(env,e.what()); return -1; }
      catch (...) { fail(env,"SMAF rendering failed"); return -1; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_infinifolder_audio_SmafNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Stream*>(handle);
}
