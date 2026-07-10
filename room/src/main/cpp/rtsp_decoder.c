// Native RTSP decoder: FFmpeg demux + MediaCodec hardware decode (h264/hevc)
// + swscale NV12->I420, handed to Kotlin per frame. No pixel processing in the
// JVM. The MediaCodec decoder needs a JavaVM (set in JNI_OnLoad) and does the
// H.264/H.265 decode on the device's hardware video engine.

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>
#include <pthread.h>
#include <android/log.h>
#include <sys/system_properties.h>

#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "libavcodec/jni.h"
#include "libavutil/imgutils.h"
#include "libavutil/opt.h"
#include "libavutil/channel_layout.h"
#include "libswscale/swscale.h"
#include "libswresample/swresample.h"

#define TAG "FfmpegRtsp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    av_jni_set_java_vm(vm, NULL);
    return JNI_VERSION_1_6;
}

// Camera audio, decoded + resampled to 16 kHz mono s16 for the AV-sync
// estimator (plan 2026-07-10-audio-and-av-sync). One decoded frame per slot,
// tagged with its mux-timeline PTS in µs so Kotlin can place it relative to
// the video frames it emits.
#define A_RATE 16000
#define AFRAME_MAX 4096          // samples per slot (covers AAC/G.711 frames)
#define AFIFO_LEN 256            // ~5+ s of AAC frames; oldest dropped if full
typedef struct {
    int64_t pts_us;
    int n;
    int16_t data[AFRAME_MAX];
} AFrame;

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    struct SwsContext *sws;
    int video_stream;
    // ---- camera audio (optional; audio_stream = -1 when absent/unusable)
    int audio_stream;
    AVCodecContext *adec;
    struct SwrContext *swr;
    pthread_mutex_t amutex;      // guards afifo/a_head/a_count/last_video_pts_us
    AFrame *afifo;
    int a_head, a_count;
    int64_t last_video_pts_us;   // mux-timeline µs of the last *emitted* frame
    int64_t a_dropped;
    int out_w, out_h;
    uint8_t *i420;          // packed I420 output buffer
    int i420_size;
    uint8_t *dst_data[4];
    int dst_linesize[4];
    volatile int stop;
    // Per-stage timing (ns), accumulated and logged every LOG_EVERY frames, so
    // we can see empirically where each output frame's wall time goes:
    //   read = av_read_frame (RTSP/network), send = feed packet to decoder,
    //   recv = avcodec_receive_frame (MediaCodec HW decode + wrapper wait),
    //   scale = swscale convert, copy = JNI handoff to Kotlin.
    int64_t t_read, t_send, t_recv, t_scale, t_copy;
    int64_t n_frames, n_reads, n_recv_calls, n_drops;
    // PTS-based pacing to the negotiated fps (0 = off). Wall-clock pacing in
    // Kotlin failed: MediaCodec delivers frames in bursts, so arrival time
    // carries no cadence — the stream's own timestamps do. Same deadline-
    // accumulator as FramePacer.kt, in stream-timebase ticks, applied before
    // sws_scale so dropped frames cost nothing.
    int pace_fps;
    int64_t next_pts;       // AV_NOPTS_VALUE until the first paced frame
} Ctx;

#define LOG_EVERY 100

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// Experiment toggle: `adb shell setprop debug.room.swdec 1` forces the FFmpeg
// software decoder instead of MediaCodec HW. Default (0) keeps HW.
static int prefer_software(void) {
    char v[PROP_VALUE_MAX] = {0};
    __system_property_get("debug.room.swdec", v);
    return v[0] == '1';
}

// Pick the MediaCodec hardware decoder for this codec id, else the software one.
static const AVCodec *pick_decoder(enum AVCodecID id) {
    if (!prefer_software()) {
        const AVCodec *hw = NULL;
        if (id == AV_CODEC_ID_H264) hw = avcodec_find_decoder_by_name("h264_mediacodec");
        else if (id == AV_CODEC_ID_HEVC) hw = avcodec_find_decoder_by_name("hevc_mediacodec");
        if (hw) { LOGI("using hardware decoder %s", hw->name); return hw; }
        LOGW("no mediacodec decoder for codec %d, using software", id);
    }
    const AVCodec *sw = avcodec_find_decoder(id);
    LOGI("using software decoder %s", sw ? sw->name : "(none)");
    return sw;
}

// Returns a Ctx* as a jlong, or 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeOpen(
        JNIEnv *env, jobject thiz, jstring jurl, jint want_w, jint want_h, jint pace_fps) {
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    LOGI("open %s pace_fps=%d", url, pace_fps);

    Ctx *c = av_mallocz(sizeof(Ctx));
    c->video_stream = -1;
    c->audio_stream = -1;
    c->pace_fps = pace_fps;
    c->next_pts = AV_NOPTS_VALUE;
    c->last_video_pts_us = INT64_MIN;
    pthread_mutex_init(&c->amutex, NULL);

    AVDictionary *opts = NULL;
    av_dict_set(&opts, "rtsp_transport", "tcp", 0);   // survives Wi-Fi better than UDP
    av_dict_set(&opts, "stimeout", "5000000", 0);      // 5s socket timeout (us)
    av_dict_set(&opts, "max_delay", "500000", 0);
    av_dict_set(&opts, "buffer_size", "1048576", 0);

    int ret = avformat_open_input(&c->fmt, url, NULL, &opts);
    av_dict_free(&opts);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
    if (ret < 0) { LOGE("open_input failed %d", ret); av_free(c); return 0; }

    if (avformat_find_stream_info(c->fmt, NULL) < 0) {
        LOGE("find_stream_info failed"); avformat_close_input(&c->fmt); av_free(c); return 0;
    }

    for (unsigned i = 0; i < c->fmt->nb_streams; i++) {
        enum AVMediaType t = c->fmt->streams[i]->codecpar->codec_type;
        if (t == AVMEDIA_TYPE_VIDEO && c->video_stream < 0) c->video_stream = (int) i;
        if (t == AVMEDIA_TYPE_AUDIO && c->audio_stream < 0) c->audio_stream = (int) i;
    }
    if (c->video_stream < 0) { LOGE("no video stream"); avformat_close_input(&c->fmt); av_free(c); return 0; }

    AVCodecParameters *par = c->fmt->streams[c->video_stream]->codecpar;
    const AVCodec *dec = pick_decoder(par->codec_id);
    if (!dec) { LOGE("no decoder"); avformat_close_input(&c->fmt); av_free(c); return 0; }

    c->dec = avcodec_alloc_context3(dec);
    avcodec_parameters_to_context(c->dec, par);
    // Low latency: don't buffer frames.
    c->dec->flags |= AV_CODEC_FLAG_LOW_DELAY;
    if (avcodec_open2(c->dec, dec, NULL) < 0) {
        LOGE("avcodec_open2 failed"); avcodec_free_context(&c->dec);
        avformat_close_input(&c->fmt); av_free(c); return 0;
    }

    // Camera audio (if present): software decode + resample to 16 kHz mono
    // s16 for the sync estimator. Failure here degrades to video-only.
    if (c->audio_stream >= 0) {
        AVCodecParameters *apar = c->fmt->streams[c->audio_stream]->codecpar;
        const AVCodec *adec = avcodec_find_decoder(apar->codec_id);
        if (adec) {
            c->adec = avcodec_alloc_context3(adec);
            avcodec_parameters_to_context(c->adec, apar);
            if (avcodec_open2(c->adec, adec, NULL) == 0) {
                AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
                if (swr_alloc_set_opts2(&c->swr,
                        &mono, AV_SAMPLE_FMT_S16, A_RATE,
                        &c->adec->ch_layout, c->adec->sample_fmt,
                        c->adec->sample_rate, 0, NULL) == 0 &&
                    swr_init(c->swr) == 0) {
                    c->afifo = av_mallocz(sizeof(AFrame) * AFIFO_LEN);
                    LOGI("audio stream %d: %s %d Hz -> %d Hz mono s16",
                         c->audio_stream, adec->name, c->adec->sample_rate, A_RATE);
                } else {
                    LOGW("swr init failed; audio disabled");
                    swr_free(&c->swr);
                    avcodec_free_context(&c->adec);
                    c->audio_stream = -1;
                }
            } else {
                LOGW("audio avcodec_open2 failed; audio disabled");
                avcodec_free_context(&c->adec);
                c->audio_stream = -1;
            }
        } else {
            LOGW("no decoder for audio codec %d; audio disabled", apar->codec_id);
            c->audio_stream = -1;
        }
    }

    // Preserve source aspect ratio: fit the frame inside the requested box
    // (the size Zoom negotiated) instead of stretching a 16:9 camera into 4:3.
    int box_w = want_w > 0 ? want_w : par->width;
    int box_h = want_h > 0 ? want_h : par->height;
    if (par->width > 0 && par->height > 0) {
        double s = fmin((double) box_w / par->width, (double) box_h / par->height);
        c->out_w = ((int) (par->width * s) + 1) & ~1;   // round to even
        c->out_h = ((int) (par->height * s) + 1) & ~1;
    } else {
        c->out_w = box_w; c->out_h = box_h;
    }
    c->i420_size = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, c->out_w, c->out_h, 1);
    c->i420 = av_malloc(c->i420_size);
    av_image_fill_arrays(c->dst_data, c->dst_linesize, c->i420,
                         AV_PIX_FMT_YUV420P, c->out_w, c->out_h, 1);
    LOGI("opened %dx%d -> I420 %dx%d", par->width, par->height, c->out_w, c->out_h);
    return (jlong) (intptr_t) c;
}

// Decode one audio packet, resample to 16 kHz mono s16, push to the FIFO with
// its mux-timeline PTS (µs). Runs on the pump thread; FIFO shared with the
// estimator thread via nativeReadAudio.
static void decode_audio(Ctx *c, AVPacket *pkt, AVFrame *frame) {
    if (avcodec_send_packet(c->adec, pkt) < 0) return;
    AVRational tb = c->fmt->streams[c->audio_stream]->time_base;
    while (avcodec_receive_frame(c->adec, frame) == 0) {
        int64_t pts = frame->best_effort_timestamp;
        if (pts == AV_NOPTS_VALUE) pts = frame->pts;
        int64_t pts_us = pts == AV_NOPTS_VALUE ? INT64_MIN
                                               : av_rescale_q(pts, tb, AV_TIME_BASE_Q);
        int max_out = (int) av_rescale_rnd(
            swr_get_delay(c->swr, c->adec->sample_rate) + frame->nb_samples,
            A_RATE, c->adec->sample_rate, AV_ROUND_UP);
        int16_t tmp[AFRAME_MAX * 2];
        if (max_out > (int) (sizeof(tmp) / sizeof(tmp[0]))) max_out = sizeof(tmp) / sizeof(tmp[0]);
        uint8_t *outp[1] = {(uint8_t *) tmp};
        int n = swr_convert(c->swr, outp, max_out,
                            (const uint8_t **) frame->extended_data, frame->nb_samples);
        if (n <= 0) continue;
        pthread_mutex_lock(&c->amutex);
        for (int off = 0; off < n; off += AFRAME_MAX) {
            if (c->a_count == AFIFO_LEN) {  // full: drop oldest
                c->a_head = (c->a_head + 1) % AFIFO_LEN;
                c->a_count--;
                c->a_dropped++;
            }
            AFrame *f = &c->afifo[(c->a_head + c->a_count) % AFIFO_LEN];
            f->n = n - off < AFRAME_MAX ? n - off : AFRAME_MAX;
            f->pts_us = pts_us == INT64_MIN ? INT64_MIN
                                            : pts_us + (int64_t) off * 1000000 / A_RATE;
            memcpy(f->data, tmp + off, (size_t) f->n * sizeof(int16_t));
            c->a_count++;
        }
        pthread_mutex_unlock(&c->amutex);
    }
}

// Blocks reading/decoding until a frame is produced or the stream ends.
// On success copies packed I420 into [out] and returns bytes written; 0 on EOF/stop.
JNIEXPORT jint JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeNextFrame(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray out) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    if (!c || c->stop) return 0;

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    int result = 0;

    while (!c->stop) {
        // Try to receive an already-decoded frame first.
        int64_t t0 = now_ns();
        int r = avcodec_receive_frame(c->dec, frame);
        c->t_recv += now_ns() - t0; c->n_recv_calls++;
        if (r == 0) {
            int64_t pts = frame->best_effort_timestamp;
            if (pts == AV_NOPTS_VALUE) pts = frame->pts;
            if (c->pace_fps > 0) {
                if (pts != AV_NOPTS_VALUE) {
                    AVRational tb = c->fmt->streams[c->video_stream]->time_base;
                    int64_t interval = av_rescale(1, tb.den, (int64_t) tb.num * c->pace_fps);
                    if (c->next_pts != AV_NOPTS_VALUE &&
                        llabs(pts - c->next_pts) > 10 * interval) {
                        c->next_pts = AV_NOPTS_VALUE;   // PTS jump: resync
                    }
                    if (c->next_pts == AV_NOPTS_VALUE) {
                        c->next_pts = pts + interval;    // first frame passes
                    } else if (pts < c->next_pts) {
                        c->n_drops++;
                        continue;                        // paced out, pre-scale
                    } else {
                        c->next_pts += interval;
                        // Slow source: don't bank credit for a future burst.
                        if (c->next_pts <= pts) c->next_pts = pts + interval;
                    }
                }
            }
            if (!c->sws) {
                c->sws = sws_getContext(frame->width, frame->height,
                                        (enum AVPixelFormat) frame->format,
                                        c->out_w, c->out_h, AV_PIX_FMT_YUV420P,
                                        SWS_BILINEAR, NULL, NULL, NULL);
            }
            t0 = now_ns();
            sws_scale(c->sws, (const uint8_t *const *) frame->data, frame->linesize,
                      0, frame->height, c->dst_data, c->dst_linesize);
            c->t_scale += now_ns() - t0;
            jsize cap = (*env)->GetArrayLength(env, out);
            int n = c->i420_size < cap ? c->i420_size : cap;
            t0 = now_ns();
            (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) c->i420);
            c->t_copy += now_ns() - t0;
            result = n;
            // Anchor for the AV-sync mapping: the mux PTS of the frame that is
            // about to leave for Zoom (Kotlin pairs it with the wall clock).
            if (pts != AV_NOPTS_VALUE) {
                AVRational vtb = c->fmt->streams[c->video_stream]->time_base;
                pthread_mutex_lock(&c->amutex);
                c->last_video_pts_us = av_rescale_q(pts, vtb, AV_TIME_BASE_Q);
                pthread_mutex_unlock(&c->amutex);
            }
            // Emit an averaged per-stage breakdown so we can localize the
            // bottleneck (network vs MediaCodec vs convert vs copy) on-device.
            if (++c->n_frames % LOG_EVERY == 0) {
                double f = c->n_frames >= LOG_EVERY ? LOG_EVERY : c->n_frames;
                LOGI("per-frame ms: read=%.1f send=%.1f recv=%.1f(x%.1f) scale=%.1f copy=%.1f | "
                     "reads/frame=%.1f paced-drops=%lld  => %.1f fps ceiling",
                     c->t_read / 1e6 / f, c->t_send / 1e6 / f, c->t_recv / 1e6 / f,
                     c->n_recv_calls / f, c->t_scale / 1e6 / f, c->t_copy / 1e6 / f,
                     c->n_reads / f, (long long) c->n_drops,
                     1e9 * f / (double)(c->t_read + c->t_send + c->t_recv + c->t_scale + c->t_copy));
                c->t_read = c->t_send = c->t_recv = c->t_scale = c->t_copy = 0;
                c->n_reads = c->n_recv_calls = 0; c->n_drops = 0;
            }
            break;
        }
        if (r != AVERROR(EAGAIN) && r != AVERROR_EOF) { LOGW("receive_frame %d", r); }

        // Need more input: read a packet.
        t0 = now_ns();
        int rp = av_read_frame(c->fmt, pkt);
        c->t_read += now_ns() - t0; c->n_reads++;
        if (rp < 0) {
            avcodec_send_packet(c->dec, NULL); // flush
            if (avcodec_receive_frame(c->dec, frame) == 0) {
                // drain one last frame — handled next loop iteration
                av_packet_unref(pkt);
                continue;
            }
            break; // EOF / error
        }
        if (pkt->stream_index == c->video_stream) {
            t0 = now_ns();
            avcodec_send_packet(c->dec, pkt);
            c->t_send += now_ns() - t0;
        } else if (pkt->stream_index == c->audio_stream && c->adec) {
            decode_audio(c, pkt, frame);
        }
        av_packet_unref(pkt);
    }

    av_frame_free(&frame);
    av_packet_free(&pkt);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeWidth(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h; return c ? c->out_w : 0;
}
JNIEXPORT jint JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeHeight(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h; return c ? c->out_h : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeHasAudio(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h; return c && c->audio_stream >= 0;
}

// Pops one decoded camera-audio frame (16 kHz mono s16) into [out]; writes its
// mux-timeline PTS in µs to ptsUs[0] (Long.MIN_VALUE if unknown). Returns the
// sample count, 0 when the FIFO is empty. Estimator-thread safe.
JNIEXPORT jint JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeReadAudio(
        JNIEnv *env, jobject thiz, jlong handle, jshortArray out, jlongArray ptsUs) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    if (!c || !c->afifo) return 0;
    jsize cap = (*env)->GetArrayLength(env, out);
    pthread_mutex_lock(&c->amutex);
    if (c->a_count == 0) { pthread_mutex_unlock(&c->amutex); return 0; }
    AFrame *f = &c->afifo[c->a_head];
    int n = f->n < cap ? f->n : cap;
    jlong pts = (jlong) f->pts_us;
    (*env)->SetShortArrayRegion(env, out, 0, n, (const jshort *) f->data);
    c->a_head = (c->a_head + 1) % AFIFO_LEN;
    c->a_count--;
    pthread_mutex_unlock(&c->amutex);
    (*env)->SetLongArrayRegion(env, ptsUs, 0, 1, &pts);
    return n;
}

// Mux-timeline µs of the last video frame handed to Kotlin (Long.MIN_VALUE
// before the first one).
JNIEXPORT jlong JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeLastVideoPtsUs(
        JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h;
    if (!c) return INT64_MIN;
    pthread_mutex_lock(&c->amutex);
    jlong v = (jlong) c->last_video_pts_us;
    pthread_mutex_unlock(&c->amutex);
    return v;
}

JNIEXPORT void JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeStop(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h; if (c) c->stop = 1;
}

JNIEXPORT void JNICALL
Java_com_bilal_meetingsremote_source_FfmpegVideoSource_nativeClose(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h;
    if (!c) return;
    if (c->sws) sws_freeContext(c->sws);
    if (c->dec) avcodec_free_context(&c->dec);
    if (c->adec) avcodec_free_context(&c->adec);
    if (c->swr) swr_free(&c->swr);
    if (c->afifo) av_free(c->afifo);
    if (c->fmt) avformat_close_input(&c->fmt);
    if (c->i420) av_free(c->i420);
    pthread_mutex_destroy(&c->amutex);
    av_free(c);
}
