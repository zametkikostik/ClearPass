cmake_minimum_required(VERSION 3.22.1)
project(hev_jni)

add_library(hev-jni SHARED hev_jni.c)

find_library(log-lib log)
target_link_libraries(hev-jni ${log-lib})
