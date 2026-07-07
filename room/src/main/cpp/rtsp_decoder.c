// Native RTSP decoder: FFmpeg demux + MediaCodec hardware decode (h264/hevc)
// + swscale NV12->I420, handed to Kotlin per frame. No pixel processing in the
// JVM. The MediaCodec decoder needs a JavaVM (set in JNI_OnLoad) and does the
// H.264/H.265 decode on the device's hardware video engine.

#include <jni.h>
#include <string.h>
#include <android/log.h>

#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "libavcodec/jni.h"
#include "libavutil/imgutils.h"
#include "libavutil/opt.h"
#include "libswscale/swscale.h"

#define TAG "FfmpegRtsp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    av_jni_set_java_vm(vm, NULL);
    return JNI_VERSION_1_6;
}

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    struct SwsContext *sws;
    int video_stream;
    int out_w, out_h;
    uint8_t *i420;          // packed I420 output buffer
    int i420_size;
    uint8_t *dst_data[4];
    int dst_linesize[4];
    volatile int stop;
} Ctx;

// Pick the MediaCodec hardware decoder for this codec id, else the software one.
static const AVCodec *pick_decoder(enum AVCodecID id) {
    const AVCodec *hw = NULL;
    if (id == AV_CODEC_ID_H264) hw = avcodec_find_decoder_by_name("h264_mediacodec");
    else if (id == AV_CODEC_ID_HEVC) hw = avcodec_find_decoder_by_name("hevc_mediacodec");
    if (hw) { LOGI("using hardware decoder %s", hw->name); return hw; }
    LOGW("no mediacodec decoder for codec %d, using software", id);
    return avcodec_find_decoder(id);
}

// Returns a Ctx* as a jlong, or 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_bilal_zoomroom_source_FfmpegVideoSource_nativeOpen(
        JNIEnv *env, jobject thiz, jstring jurl, jint want_w, jint want_h) {
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    LOGI("open %s", url);

    Ctx *c = av_mallocz(sizeof(Ctx));
    c->video_stream = -1;

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
        if (c->fmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            c->video_stream = (int) i; break;
        }
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

    c->out_w = want_w > 0 ? want_w : par->width;
    c->out_h = want_h > 0 ? want_h : par->height;
    c->i420_size = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, c->out_w, c->out_h, 1);
    c->i420 = av_malloc(c->i420_size);
    av_image_fill_arrays(c->dst_data, c->dst_linesize, c->i420,
                         AV_PIX_FMT_YUV420P, c->out_w, c->out_h, 1);
    LOGI("opened %dx%d -> I420 %dx%d", par->width, par->height, c->out_w, c->out_h);
    return (jlong) (intptr_t) c;
}

// Blocks reading/decoding until a frame is produced or the stream ends.
// On success copies packed I420 into [out] and returns bytes written; 0 on EOF/stop.
JNIEXPORT jint JNICALL
Java_com_bilal_zoomroom_source_FfmpegVideoSource_nativeNextFrame(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray out) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    if (!c || c->stop) return 0;

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    int result = 0;

    while (!c->stop) {
        // Try to receive an already-decoded frame first.
        int r = avcodec_receive_frame(c->dec, frame);
        if (r == 0) {
            if (!c->sws) {
                c->sws = sws_getContext(frame->width, frame->height,
                                        (enum AVPixelFormat) frame->format,
                                        c->out_w, c->out_h, AV_PIX_FMT_YUV420P,
                                        SWS_BILINEAR, NULL, NULL, NULL);
            }
            sws_scale(c->sws, (const uint8_t *const *) frame->data, frame->linesize,
                      0, frame->height, c->dst_data, c->dst_linesize);
            jsize cap = (*env)->GetArrayLength(env, out);
            int n = c->i420_size < cap ? c->i420_size : cap;
            (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) c->i420);
            result = n;
            break;
        }
        if (r != AVERROR(EAGAIN) && r != AVERROR_EOF) { LOGW("receive_frame %d", r); }

        // Need more input: read a packet.
        int rp = av_read_frame(c->fmt, pkt);
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
            avcodec_send_packet(c->dec, pkt);
        }
        av_packet_unref(pkt);
    }

    av_frame_free(&frame);
    av_packet_free(&pkt);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_bilal_zoomroom_source_FfmpegVideoSource_nativeWidth(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h; return c ? c->out_w : 0;
}
JNIEXPORT jint JNICALL
Java_com_bilal_zoomroom_source_FfmpegVideoSource_nativeHeight(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h; return c ? c->out_h : 0;
}

JNIEXPORT void JNICALL
Java_com_bilal_zoomroom_source_FfmpegVideoSource_nativeStop(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h; if (c) c->stop = 1;
}

JNIEXPORT void JNICALL
Java_com_bilal_zoomroom_source_FfmpegVideoSource_nativeClose(JNIEnv *e, jobject t, jlong h) {
    Ctx *c = (Ctx *) (intptr_t) h;
    if (!c) return;
    if (c->sws) sws_freeContext(c->sws);
    if (c->dec) avcodec_free_context(&c->dec);
    if (c->fmt) avformat_close_input(&c->fmt);
    if (c->i420) av_free(c->i420);
    av_free(c);
}
