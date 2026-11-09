cmake_minimum_required(VERSION 3.22)
project(diskcore C)

# N1.1：只有一个很小的 usbfs ioctl 辅助库。后续阶段的原生传输层（libusb 后端、
# 第三方库的读块回调适配）也会放进 libdiskcore。
add_library(diskcore SHARED
        src/usbfs_jni.c)

target_compile_options(diskcore PRIVATE
        -Wall -Wextra -Werror=implicit-function-declaration -Werror=return-type)

# NDK r28 起默认 16 KB 页对齐；这里显式写出，保证在 16 KB 页设备上也能加载。
target_link_options(diskcore PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(diskcore log)
