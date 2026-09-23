#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavcodec/jni.h>
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>

#define AF_LOG_TAG "ANIMEFIRE_NATIVE"
#define AF_LOGI(...) __android_log_print(ANDROID_LOG_INFO, AF_LOG_TAG, __VA_ARGS__)
#define AF_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, AF_LOG_TAG, __VA_ARGS__)

typedef struct {
    const uint8_t *data;
    size_t size;
    size_t position;
} InputBuffer;

typedef struct {
    uint8_t *data;
    size_t size;
    size_t capacity;
} OutputBuffer;

static int input_read(void *opaque, uint8_t *buffer, int size) {
    InputBuffer *input = opaque;
    size_t remaining = input->size - input->position;
    size_t amount = remaining < (size_t) size ? remaining : (size_t) size;
    if (amount == 0) return AVERROR_EOF;
    memcpy(buffer, input->data + input->position, amount);
    input->position += amount;
    return (int) amount;
}

static int64_t input_seek(void *opaque, int64_t offset, int whence) {
    InputBuffer *input = opaque;
    if (whence == AVSEEK_SIZE) return (int64_t) input->size;
    size_t position;
    switch (whence & ~AVSEEK_FORCE) {
        case SEEK_SET: position = (size_t) offset; break;
        case SEEK_CUR: position = input->position + offset; break;
        case SEEK_END: position = input->size + offset; break;
        default: return AVERROR(EINVAL);
    }
    if (position > input->size) return AVERROR(EINVAL);
    input->position = position;
    return (int64_t) position;
}

static int output_write(void *opaque, const uint8_t *buffer, int size) {
    OutputBuffer *output = opaque;
    size_t required = output->size + (size_t) size;
    if (required > output->capacity) {
        size_t capacity = output->capacity ? output->capacity : 4096;
        while (capacity < required) capacity *= 2;
        uint8_t *data = realloc(output->data, capacity);
        if (!data) return AVERROR(ENOMEM);
        output->data = data;
        output->capacity = capacity;
    }
    memcpy(output->data + output->size, buffer, (size_t) size);
    output->size += (size_t) size;
    return size;
}

static void throw_error(JNIEnv *env, int error, const char *operation) {
    char message[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(error, message, sizeof(message));
    char full[AV_ERROR_MAX_STRING_SIZE + 64];
    snprintf(full, sizeof(full), "%s: %s", operation, message);
    jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception) (*env)->ThrowNew(env, exception, full);
}

static int write_encoded_packet(AVCodecContext *encoder, AVFormatContext *output,
                                AVStream *output_video, AVPacket *encoded,
                                int *encoded_packets) {
    int error;
    while ((error = avcodec_receive_packet(encoder, encoded)) >= 0) {
        encoded->stream_index = output_video->index;
        av_packet_rescale_ts(encoded, encoder->time_base, output_video->time_base);
        encoded->pos = -1;
        if ((error = av_interleaved_write_frame(output, encoded)) < 0) return error;
        (*encoded_packets)++;
        av_packet_unref(encoded);
    }
    return error == AVERROR(EAGAIN) || error == AVERROR_EOF ? 0 : error;
}

JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_animeextension_pt_animefire_nativebridge_AnimeFireNative_ffmpegVersion(
        JNIEnv *env,
        jobject thiz) {
    return (*env)->NewStringUTF(env, av_version_info());
}

JNIEXPORT jbyteArray JNICALL
Java_eu_kanade_tachiyomi_animeextension_pt_animefire_nativebridge_AnimeFireNative_transmuxToMpegTs(
        JNIEnv *env,
        jobject thiz,
        jbyteArray init,
        jbyteArray fragment) {
    jbyte *init_bytes = NULL;
    jbyte *fragment_bytes = NULL;
    uint8_t *input_data = NULL;
    uint8_t *input_io_buffer = NULL;
    uint8_t *output_io_buffer = NULL;
    AVIOContext *input_io = NULL;
    AVIOContext *output_io = NULL;
    AVFormatContext *input = NULL;
    AVFormatContext *output = NULL;
    AVPacket *packet = NULL;
    OutputBuffer result = {0};
    jbyteArray value = NULL;
    int error = 0;

    jsize init_size = (*env)->GetArrayLength(env, init);
    jsize fragment_size = (*env)->GetArrayLength(env, fragment);
    if (init_size <= 0 || fragment_size <= 0) {
        throw_error(env, AVERROR(EINVAL), "Empty fMP4 input");
        return NULL;
    }
    init_bytes = (*env)->GetByteArrayElements(env, init, NULL);
    fragment_bytes = (*env)->GetByteArrayElements(env, fragment, NULL);
    input_data = av_malloc((size_t) init_size + (size_t) fragment_size);
    input_io_buffer = av_malloc(32768);
    output_io_buffer = av_malloc(32768);
    if (!init_bytes || !fragment_bytes || !input_data || !input_io_buffer || !output_io_buffer) {
        error = AVERROR(ENOMEM);
        goto fail;
    }
    memcpy(input_data, init_bytes, (size_t) init_size);
    memcpy(input_data + init_size, fragment_bytes, (size_t) fragment_size);

    InputBuffer source = { input_data, (size_t) init_size + (size_t) fragment_size, 0 };
    input_io = avio_alloc_context(input_io_buffer, 32768, 0, &source, input_read, NULL, input_seek);
    if (!input_io) { error = AVERROR(ENOMEM); goto fail; }
    input_io_buffer = NULL;
    input = avformat_alloc_context();
    if (!input) { error = AVERROR(ENOMEM); goto fail; }
    input->pb = input_io;
    input->flags |= AVFMT_FLAG_CUSTOM_IO;
    if ((error = avformat_open_input(&input, NULL, av_find_input_format("mov"), NULL)) < 0) goto fail;
    if ((error = avformat_find_stream_info(input, NULL)) < 0) goto fail;

    if ((error = avformat_alloc_output_context2(&output, NULL, "mpegts", NULL)) < 0 || !output) goto fail;
    for (unsigned int index = 0; index < input->nb_streams; index++) {
        AVStream *source_stream = input->streams[index];
        AVStream *target_stream = avformat_new_stream(output, NULL);
        if (!target_stream) { error = AVERROR(ENOMEM); goto fail; }
        if ((error = avcodec_parameters_copy(target_stream->codecpar, source_stream->codecpar)) < 0) goto fail;
        target_stream->time_base = source_stream->time_base;
    }
    output_io = avio_alloc_context(output_io_buffer, 32768, 1, &result, NULL, output_write, NULL);
    if (!output_io) { error = AVERROR(ENOMEM); goto fail; }
    output_io_buffer = NULL;
    output->pb = output_io;
    output->flags |= AVFMT_FLAG_CUSTOM_IO;
    // Every HLS segment is an independently muxed TS file. Mark its first
    // packets as a transport discontinuity so an older HLS demuxer resets
    // PID continuity instead of flagging every segment as corrupt.
    av_opt_set(output->priv_data, "mpegts_flags", "+initial_discontinuity", 0);
    av_opt_set_int(output->priv_data, "mpegts_copyts", 1, 0);
    if ((error = avformat_write_header(output, NULL)) < 0) goto fail;

    packet = av_packet_alloc();
    if (!packet) { error = AVERROR(ENOMEM); goto fail; }
    while ((error = av_read_frame(input, packet)) >= 0) {
        AVStream *source_stream = input->streams[packet->stream_index];
        AVStream *target_stream = output->streams[packet->stream_index];
        av_packet_rescale_ts(packet, source_stream->time_base, target_stream->time_base);
        packet->pos = -1;
        if ((error = av_interleaved_write_frame(output, packet)) < 0) goto fail;
        av_packet_unref(packet);
    }
    if (error != AVERROR_EOF) goto fail;
    if ((error = av_write_trailer(output)) < 0) goto fail;
    value = (*env)->NewByteArray(env, (jsize) result.size);
    if (!value) goto cleanup;
    (*env)->SetByteArrayRegion(env, value, 0, (jsize) result.size, (const jbyte *) result.data);
    goto cleanup;

fail:
    throw_error(env, error, "FFmpeg transmux");
cleanup:
    if (packet) av_packet_free(&packet);
    if (output) avformat_free_context(output);
    if (output_io) avio_context_free(&output_io);
    if (input) avformat_close_input(&input);
    if (input_io) avio_context_free(&input_io);
    av_free(output_io_buffer);
    av_free(input_io_buffer);
    av_free(input_data);
    free(result.data);
    if (init_bytes) (*env)->ReleaseByteArrayElements(env, init, init_bytes, JNI_ABORT);
    if (fragment_bytes) (*env)->ReleaseByteArrayElements(env, fragment, fragment_bytes, JNI_ABORT);
    return value;
}

/*
 * Deliberately isolated AV1 proof-of-pipeline.  It receives one fragmented-MP4
 * init+media pair, decodes AV1, sends real frames to Android's MediaCodec H264
 * encoder, copies AAC packets, and muxes the result as a standalone TS file.
 * It is not wired to the HLS server yet.
 */
JNIEXPORT jbyteArray JNICALL
Java_eu_kanade_tachiyomi_animeextension_pt_animefire_nativebridge_AnimeFireNative_transcodeAv1FragmentToMpegTs(
        JNIEnv *env,
        jobject thiz,
        jbyteArray init,
        jbyteArray fragment) {
    jbyte *init_bytes = NULL;
    jbyte *fragment_bytes = NULL;
    uint8_t *input_data = NULL, *input_io_buffer = NULL, *output_io_buffer = NULL;
    AVIOContext *input_io = NULL, *output_io = NULL;
    AVFormatContext *input = NULL, *output = NULL;
    AVCodecContext *decoder = NULL, *encoder = NULL;
    AVPacket *packet = NULL, *encoded = NULL;
    AVFrame *frame = NULL, *converted = NULL;
    struct SwsContext *scaler = NULL;
    OutputBuffer result = {0};
    jbyteArray value = NULL;
    int video_index = -1, audio_index = -1;
    AVStream *input_video = NULL, *input_audio = NULL;
    AVStream *output_video = NULL, *output_audio = NULL;
    int decoded_frames = 0, encoded_packets = 0, copied_audio = 0;
    int error = 0;
    const char *stage = "initialization";
    int64_t started_us = av_gettime_relative();
    JavaVM *vm = NULL;

    jsize init_size = (*env)->GetArrayLength(env, init);
    jsize fragment_size = (*env)->GetArrayLength(env, fragment);
    if (init_size <= 0 || fragment_size <= 0) {
        throw_error(env, AVERROR(EINVAL), "Empty AV1 fMP4 input");
        return NULL;
    }
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK || !vm) {
        throw_error(env, AVERROR_EXTERNAL, "GetJavaVM");
        return NULL;
    }
    if ((error = av_jni_set_java_vm(vm, NULL)) < 0) goto fail;
    AF_LOGI("AV1 test BSF registry h264_mp4toannexb=%p aac_adtstoasc=%p",
            av_bsf_get_by_name("h264_mp4toannexb"), av_bsf_get_by_name("aac_adtstoasc"));

    init_bytes = (*env)->GetByteArrayElements(env, init, NULL);
    fragment_bytes = (*env)->GetByteArrayElements(env, fragment, NULL);
    input_data = av_malloc((size_t)init_size + (size_t)fragment_size);
    input_io_buffer = av_malloc(32768);
    output_io_buffer = av_malloc(32768);
    if (!init_bytes || !fragment_bytes || !input_data || !input_io_buffer || !output_io_buffer) {
        error = AVERROR(ENOMEM); goto fail;
    }
    memcpy(input_data, init_bytes, (size_t)init_size);
    memcpy(input_data + init_size, fragment_bytes, (size_t)fragment_size);

    InputBuffer source = { input_data, (size_t)init_size + (size_t)fragment_size, 0 };
    input_io = avio_alloc_context(input_io_buffer, 32768, 0, &source, input_read, NULL, input_seek);
    if (!input_io) { error = AVERROR(ENOMEM); goto fail; }
    input_io_buffer = NULL;
    input = avformat_alloc_context();
    if (!input) { error = AVERROR(ENOMEM); goto fail; }
    input->pb = input_io;
    input->flags |= AVFMT_FLAG_CUSTOM_IO;
    stage = "open fMP4";
    if ((error = avformat_open_input(&input, NULL, av_find_input_format("mov"), NULL)) < 0) goto fail;
    stage = "read fMP4 stream info";
    if ((error = avformat_find_stream_info(input, NULL)) < 0) goto fail;

    for (unsigned int i = 0; i < input->nb_streams; i++) {
        enum AVMediaType type = input->streams[i]->codecpar->codec_type;
        if (type == AVMEDIA_TYPE_VIDEO && video_index < 0) video_index = (int)i;
        if (type == AVMEDIA_TYPE_AUDIO && audio_index < 0) audio_index = (int)i;
    }
    if (video_index < 0 || audio_index < 0) { error = AVERROR_STREAM_NOT_FOUND; goto fail; }
    input_video = input->streams[video_index];
    input_audio = input->streams[audio_index];
    if (input_video->codecpar->codec_id != AV_CODEC_ID_AV1 || input_audio->codecpar->codec_id != AV_CODEC_ID_AAC) {
        AF_LOGE("AV1 test rejected: video=%s audio=%s",
                avcodec_get_name(input_video->codecpar->codec_id),
                avcodec_get_name(input_audio->codecpar->codec_id));
        error = AVERROR_INVALIDDATA; goto fail;
    }
    AF_LOGI("AV1 input video=%s audio=%s", avcodec_get_name(input_video->codecpar->codec_id),
            avcodec_get_name(input_audio->codecpar->codec_id));

    // FFmpeg's built-in AV1 decoder only supports hardware-backed decoding.
    // The embedded dav1d decoder is software-backed and works on every ABI.
    const AVCodec *decoder_codec = avcodec_find_decoder_by_name("libdav1d");
    if (!decoder_codec) { error = AVERROR_DECODER_NOT_FOUND; goto fail; }
    decoder = avcodec_alloc_context3(decoder_codec);
    if (!decoder) { error = AVERROR(ENOMEM); goto fail; }
    if ((error = avcodec_parameters_to_context(decoder, input_video->codecpar)) < 0) goto fail;
    decoder->pkt_timebase = input_video->time_base;
    stage = "open AV1 decoder";
    if ((error = avcodec_open2(decoder, decoder_codec, NULL)) < 0) goto fail;
    AF_LOGI("AV1 decoder opened: %s", decoder_codec->name);

    const AVCodec *encoder_codec = avcodec_find_encoder_by_name("h264_mediacodec");
    if (!encoder_codec) { error = AVERROR_ENCODER_NOT_FOUND; goto fail; }
    encoder = avcodec_alloc_context3(encoder_codec);
    if (!encoder) { error = AVERROR(ENOMEM); goto fail; }
    encoder->width = decoder->width;
    encoder->height = decoder->height;
    encoder->pix_fmt = AV_PIX_FMT_YUV420P;
    AVRational guessed_rate = av_guess_frame_rate(input, input_video, NULL);
    if (guessed_rate.num <= 0 || guessed_rate.den <= 0) guessed_rate = (AVRational){ 24, 1 };
    encoder->framerate = guessed_rate;
    encoder->time_base = av_inv_q(guessed_rate);
    encoder->bit_rate = input_video->codecpar->bit_rate > 0 ? input_video->codecpar->bit_rate : 3000000;
    encoder->gop_size = guessed_rate.num / guessed_rate.den * 2;
    encoder->max_b_frames = 0;
    stage = "open H264 MediaCodec encoder";
    if ((error = avcodec_open2(encoder, encoder_codec, NULL)) < 0) goto fail;
    AF_LOGI("H264 MediaCodec encoder opened: %s %dx%d fps=%d/%d bitrate=%lld",
            encoder_codec->name, encoder->width, encoder->height,
            encoder->framerate.num, encoder->framerate.den, (long long)encoder->bit_rate);

    if ((error = avformat_alloc_output_context2(&output, NULL, "mpegts", NULL)) < 0 || !output) goto fail;
    output_video = avformat_new_stream(output, NULL);
    output_audio = avformat_new_stream(output, NULL);
    if (!output_video || !output_audio) { error = AVERROR(ENOMEM); goto fail; }
    if ((error = avcodec_parameters_from_context(output_video->codecpar, encoder)) < 0) goto fail;
    output_video->time_base = encoder->time_base;
    if ((error = avcodec_parameters_copy(output_audio->codecpar, input_audio->codecpar)) < 0) goto fail;
    output_audio->time_base = input_audio->time_base;
    output_io = avio_alloc_context(output_io_buffer, 32768, 1, &result, NULL, output_write, NULL);
    if (!output_io) { error = AVERROR(ENOMEM); goto fail; }
    output_io_buffer = NULL;
    output->pb = output_io;
    output->flags |= AVFMT_FLAG_CUSTOM_IO;
    av_opt_set(output->priv_data, "mpegts_flags", "+initial_discontinuity", 0);
    av_opt_set_int(output->priv_data, "mpegts_copyts", 1, 0);
    stage = "write MPEG-TS header";
    if ((error = avformat_write_header(output, NULL)) < 0) goto fail;
    AF_LOGI("AV1 test muxer opened: mpegts");

    packet = av_packet_alloc();
    encoded = av_packet_alloc();
    frame = av_frame_alloc();
    converted = av_frame_alloc();
    if (!packet || !encoded || !frame || !converted) { error = AVERROR(ENOMEM); goto fail; }
    stage = "read fMP4 packet";
    while ((error = av_read_frame(input, packet)) >= 0) {
        if (packet->stream_index == audio_index) {
            packet->stream_index = output_audio->index;
            av_packet_rescale_ts(packet, input_audio->time_base, output_audio->time_base);
            packet->pos = -1;
            stage = "write copied AAC packet";
            if ((error = av_interleaved_write_frame(output, packet)) < 0) goto fail;
            copied_audio++;
        } else if (packet->stream_index == video_index) {
            stage = "send AV1 packet to decoder";
            if ((error = avcodec_send_packet(decoder, packet)) < 0) goto fail;
            stage = "receive AV1 decoded frame";
            while ((error = avcodec_receive_frame(decoder, frame)) >= 0) {
                if (decoded_frames == 0)
                    AF_LOGI("AV1 decoded format=%s first_pts=%lld",
                            av_get_pix_fmt_name(frame->format), (long long)frame->pts);
                AVFrame *to_encode = frame;
                if (frame->format != encoder->pix_fmt) {
                    AF_LOGI("AV1 frame conversion %s -> %s", av_get_pix_fmt_name(frame->format), av_get_pix_fmt_name(encoder->pix_fmt));
                    scaler = sws_getCachedContext(scaler, frame->width, frame->height, frame->format,
                                                   encoder->width, encoder->height, encoder->pix_fmt,
                                                   SWS_BILINEAR, NULL, NULL, NULL);
                    if (!scaler) { error = AVERROR(ENOMEM); goto fail; }
                    av_frame_unref(converted);
                    converted->format = encoder->pix_fmt;
                    converted->width = encoder->width;
                    converted->height = encoder->height;
                    stage = "allocate converted YUV420P frame";
                    if ((error = av_frame_get_buffer(converted, 32)) < 0) goto fail;
                    if ((error = av_frame_make_writable(converted)) < 0) goto fail;
                    sws_scale(scaler, (const uint8_t * const *)frame->data, frame->linesize, 0, frame->height,
                              converted->data, converted->linesize);
                    to_encode = converted;
                }
                to_encode->pts = av_rescale_q(frame->pts, input_video->time_base, encoder->time_base);
                stage = "send H264 frame to MediaCodec";
                if ((error = avcodec_send_frame(encoder, to_encode)) < 0) goto fail;
                decoded_frames++;
                stage = "receive/write H264 MediaCodec packet";
                if ((error = write_encoded_packet(encoder, output, output_video, encoded, &encoded_packets)) < 0) goto fail;
                av_frame_unref(frame);
            }
            if (error != AVERROR(EAGAIN) && error != AVERROR_EOF) goto fail;
        }
        av_packet_unref(packet);
    }
    if (error != AVERROR_EOF) goto fail;
    stage = "flush AV1 decoder";
    if ((error = avcodec_send_packet(decoder, NULL)) < 0) goto fail;
    stage = "receive flushed AV1 frame";
    while ((error = avcodec_receive_frame(decoder, frame)) >= 0) {
        AVFrame *to_encode = frame;
        if (frame->format != encoder->pix_fmt) {
            AF_LOGI("AV1 frame conversion %s -> %s", av_get_pix_fmt_name(frame->format), av_get_pix_fmt_name(encoder->pix_fmt));
            scaler = sws_getCachedContext(scaler, frame->width, frame->height, frame->format,
                                           encoder->width, encoder->height, encoder->pix_fmt,
                                           SWS_BILINEAR, NULL, NULL, NULL);
            if (!scaler) { error = AVERROR(ENOMEM); goto fail; }
            av_frame_unref(converted);
            converted->format = encoder->pix_fmt;
            converted->width = encoder->width;
            converted->height = encoder->height;
            stage = "allocate flushed converted YUV420P frame";
            if ((error = av_frame_get_buffer(converted, 32)) < 0) goto fail;
            if ((error = av_frame_make_writable(converted)) < 0) goto fail;
            sws_scale(scaler, (const uint8_t * const *)frame->data, frame->linesize, 0, frame->height,
                      converted->data, converted->linesize);
            to_encode = converted;
        }
        to_encode->pts = av_rescale_q(frame->pts, input_video->time_base, encoder->time_base);
        stage = "send flushed H264 frame to MediaCodec";
        if ((error = avcodec_send_frame(encoder, to_encode)) < 0) goto fail;
        decoded_frames++;
        av_frame_unref(frame);
    }
    if (error != AVERROR(EAGAIN) && error != AVERROR_EOF) goto fail;
    stage = "flush H264 MediaCodec encoder";
    if ((error = avcodec_send_frame(encoder, NULL)) < 0) goto fail;
    stage = "receive/write flushed H264 packet";
    if ((error = write_encoded_packet(encoder, output, output_video, encoded, &encoded_packets)) < 0) goto fail;
    stage = "write MPEG-TS trailer";
    if ((error = av_write_trailer(output)) < 0) goto fail;
    AF_LOGI("AV1 test complete decoder=%s encoder=%s frames_decoded=%d frames_h264=%d audio_packets=%d ts_bytes=%zu elapsed_ms=%lld",
            decoder_codec->name, encoder_codec->name, decoded_frames, encoded_packets, copied_audio,
            result.size, (long long)((av_gettime_relative() - started_us) / 1000));
    value = (*env)->NewByteArray(env, (jsize)result.size);
    if (value) (*env)->SetByteArrayRegion(env, value, 0, (jsize)result.size, (const jbyte *)result.data);
    goto cleanup;

fail:
    AF_LOGE("AV1 test failed stage=%s: %s (%d)", stage, av_err2str(error), error);
    throw_error(env, error, "FFmpeg AV1 to H264 MediaCodec");
cleanup:
    if (scaler) sws_freeContext(scaler);
    if (converted) av_frame_free(&converted);
    if (frame) av_frame_free(&frame);
    if (encoded) av_packet_free(&encoded);
    if (packet) av_packet_free(&packet);
    if (encoder) avcodec_free_context(&encoder);
    if (decoder) avcodec_free_context(&decoder);
    if (output) avformat_free_context(output);
    if (output_io) avio_context_free(&output_io);
    if (input) avformat_close_input(&input);
    if (input_io) avio_context_free(&input_io);
    av_free(output_io_buffer);
    av_free(input_io_buffer);
    av_free(input_data);
    free(result.data);
    if (init_bytes) (*env)->ReleaseByteArrayElements(env, init, init_bytes, JNI_ABORT);
    if (fragment_bytes) (*env)->ReleaseByteArrayElements(env, fragment, fragment_bytes, JNI_ABORT);
    return value;
}
