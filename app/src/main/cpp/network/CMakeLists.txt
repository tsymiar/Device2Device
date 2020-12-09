cmake_minimum_required(VERSION 3.4.1)
set(CMAKE_BUILD_TYPE "Debug")

add_library(Network STATIC kcp/ikcp.c)
add_library(udpSocket STATIC UdpSocket.cpp)
add_library(kcpEmulator STATIC KcpEmulator.cpp)

target_link_libraries(Network udpSocket kcpEmulator log)
