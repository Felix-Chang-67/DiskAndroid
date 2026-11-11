cmake_minimum_required(VERSION 3.22)
project(diskplayer C)

# 原生视频播放器：libdvdnav / libdvdread 导航 DVD（含菜单），FFmpeg 解码，OpenGL ES 3 画面，AAudio 声音。
# 第三方库是静态库，由 native/third_party/build.sh 编好放在 out/android-arm64（Gradle 的 :core:player 会在缺的时候先跑它）。
# 解密库（libdvdcss、libaacs）不在这里，按需求做成单独的可选组件。
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/out/android-arm64)
if(NOT EXISTS ${TP}/lib/libavcodec.a)
    message(FATAL_ERROR "缺少第三方库：先运行 bash native/third_party/build.sh android-arm64")
endif()

add_library(diskplayer SHARED
        android/dp_android.c
        src/dp_av.c
        src/dp_bd.c
        src/dp_core.c
        src/dp_dvd.c
        src/dp_spu.c)

target_include_directories(diskplayer PRIVATE ${TP}/include src)

target_compile_options(diskplayer PRIVATE
        -std=gnu11 -O2 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter
        -Werror=implicit-function-declaration -Werror=return-type -Werror=incompatible-pointer-types)

# 顺序：依赖方在前（dvdnav → dvdread；avfilter → swscale → swresample → avcodec → avutil）
target_link_libraries(diskplayer
        ${TP}/lib/libbluray.a
        ${TP}/lib/libudfread.a
        ${TP}/lib/libdvdnav.a
        ${TP}/lib/libdvdread.a
        ${TP}/lib/libavfilter.a
        ${TP}/lib/libswscale.a
        ${TP}/lib/libswresample.a
        ${TP}/lib/libavcodec.a
        ${TP}/lib/libavutil.a
        android log EGL GLESv3 aaudio m dl)

# 16 KB 页对齐（与 libdiskcore 一致）；静态库里的符号不导出；FFmpeg 汇编需要 -Bsymbolic
target_link_options(diskplayer PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,--exclude-libs,ALL"
        "-Wl,--gc-sections"
        "-Wl,-Bsymbolic")
