cmake_minimum_required(VERSION 3.4.1)

set(CMAKE_BUILD_TYPE "Debug")
set(CMAKE_CXX_FLAGS_DEBUG "$ENV{CXXFLAGS} -O0 -Wall -g -ggdb")
set(CMAKE_CXX_FLAGS_RELEASE "$ENV{CXXFLAGS} -O3 -Wall")
set(CMAKE_CXX_FLAGS "-std=c++0x ${CMAKE_CXX_FLAGS} -g -ftest-coverage -fprofile-arcs -Wno-deprecated")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${PROJECT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(callback SHARED CallJavaMethod.cpp)
add_library(callback_static STATIC CallJavaMethod.cpp)

target_link_libraries(callback log)

SET_TARGET_PROPERTIES (callback_static PROPERTIES OUTPUT_NAME "callback")
SET_TARGET_PROPERTIES (callback_static PROPERTIES CLEAN_DIRECT_OUTPUT 1)
SET_TARGET_PROPERTIES (callback PROPERTIES CLEAN_DIRECT_OUTPUT 1)
SET_TARGET_PROPERTIES (callback PROPERTIES VERSION 1.2 SOVERSION 1)

INSTALL (TARGETS callback callback_static LIBRARY DESTINATION lib ARCHIVE DESTINATION lib)
INSTALL (FILES CallJavaMethod.h DESTINATION include/callback)
