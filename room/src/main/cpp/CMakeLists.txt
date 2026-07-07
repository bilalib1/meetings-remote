cmake_minimum_required(VERSION 3.22.1)
project(rtspdecoder C)

set(FF ${CMAKE_SOURCE_DIR}/include)
# Prebuilt FFmpeg .so are packaged from src/main/jniLibs/<abi>/.
set(JNILIBS ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

foreach(lib avcodec avformat avutil swscale swresample)
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${JNILIBS}/lib${lib}.so)
endforeach()

add_library(rtspdecoder SHARED rtsp_decoder.c)
target_include_directories(rtspdecoder PRIVATE ${FF})
target_link_libraries(rtspdecoder
    avformat avcodec swscale swresample avutil
    android log)
