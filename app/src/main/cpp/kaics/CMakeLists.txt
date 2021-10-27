cmake_minimum_required(VERSION 3.0)

project(kaics CXX)

set(CMAKE_CXX_STANDARD 14)
set(TARGET kaics)
set(OPTION "-fPIC -pthread")
set(CMAKE_CXX_FLAGS_DEBUG "$ENV{CXXFLAGS} -O0 -Wall -g -ggdb")
set(CMAKE_CXX_FLAGS_RELEASE "$ENV{CXXFLAGS} -O3 -Wall")
set(CMAKE_CXX_FLAGS "-std=c++0x ${CMAKE_CXX_FLAGS} -g -ftest-coverage -fprofile-arcs -Wno-deprecated")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${PROJECT_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI})

file(GLOB SRC_FILE_LIST "Kai*[^Test]*.*")

add_library(${TARGET} SHARED ${SRC_FILE_LIST})

target_link_libraries(${TARGET} ${OPTION})
