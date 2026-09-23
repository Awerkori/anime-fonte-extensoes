cmake_minimum_required(VERSION 3.22.1)
project(animefire_ffmpeg C)

add_library(animefire_ffmpeg SHARED AnimeFireNative.c)
target_link_options(animefire_ffmpeg PRIVATE "-Wl,-Bsymbolic")

set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ffmpeg/${ANDROID_ABI}")
target_include_directories(animefire_ffmpeg PRIVATE "${FFMPEG_DIR}")
target_link_libraries(animefire_ffmpeg
    "${FFMPEG_DIR}/libavformat.a"
    "${FFMPEG_DIR}/libavcodec.a"
    "${FFMPEG_DIR}/libdav1d.a"
    "${FFMPEG_DIR}/libswscale.a"
    "${FFMPEG_DIR}/libavutil.a"
    z
    m
    log
    android
    mediandk)
