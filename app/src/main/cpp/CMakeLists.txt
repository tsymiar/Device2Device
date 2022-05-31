# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.4.1)
PROJECT(Devidroid CXX C)

set(CMAKE_BUILD_TYPE "Debug")
set(CMAKE_CXX_FLAGS_DEBUG "$ENV{CXXFLAGS} -O0 -Wall -g -ggdb -std=c++11")
set(CMAKE_CXX_FLAGS_RELEASE "$ENV{CXXFLAGS} -O3 -Wall")
set(CMAKE_CXX_FLAGS "-std=c++0x ${CMAKE_CXX_FLAGS} -g -ftest-coverage -fprofile-arcs -Wno-deprecated")
set(TOOLCHAIN ${NDK_ROOT}/toolchains/llvm/prebuilt/darwin-x86_64/sysroot)

include_directories(
        ${PROJECT_SOURCE_DIR}
        ${TOOLCHAIN}/usr/include
        ${CMAKE_SOURCE_DIR}/scadup/src
)

if(${BUILD_SCADUP_LIB} AND ${BUILD_SCADUP_LIB} EQUAL 1)
    add_subdirectory(scadup)
endif()
add_subdirectory(time)
add_subdirectory(files)
add_subdirectory(render)
add_subdirectory(decode)
add_subdirectory(network)
add_subdirectory(callback)
# add_subdirectory(template)
AUX_SOURCE_DIRECTORY(. DIR_SRCS)
# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

add_library( # Sets the name of the library.
        jniComm

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        message/Message.cpp
        files/FileUtils.cpp
        JniMethods.cpp)

set(LIBS_DIR ${CMAKE_SOURCE_DIR}/../../../libs)
if (NOT ${BUILD_SCADUP_LIB} EQUAL 1)
    file(GLOB LIB_FILE_LIST "${CMAKE_SOURCE_DIR}/scadup/jniLibs/*")
    foreach(SOLIB IN LISTS LIB_FILE_LIST)
        add_custom_command(TARGET jniComm
                PRE_BUILD
                COMMAND mkdir -p ${LIBS_DIR}
                COMMAND cp -r ${SOLIB} ${LIBS_DIR}
                COMMENT "setup scadup so files...")
    endforeach()
else()
    add_custom_command(TARGET jniComm
            PRE_BUILD
            COMMAND rm -rf ${LIBS_DIR}
            COMMENT "clean scadup so files...")
endif()
# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
        log-lib
        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

find_library(android android)
find_library(SLES OpenSLES)
find_library(EGL EGL)
find_library(GLESv2 GLESv2)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        jniComm
        # template
        ${PROJECT_SOURCE_DIR}/scadup/jniLibs/${ANDROID_ABI}/libscadup.so
        Network converter callback timestamp texture
        # Links the target library to the log library
        # included in the NDK.
        ${log-lib} ${android} ${SLES} ${EGL} ${GLESv2})
