cmake_minimum_required(VERSION 2.8...3.16)
set(CMAKE_BUILD_TYPE "Debug")
add_definitions("-Wno-unused-parameter -Wno-implicit-function-declaration")
#add_definitions(-D_KCP_TEST_)

add_library(ikcp STATIC kcp/ikcp.c)
add_library(tcpSocket STATIC TcpSocket.cpp)
add_library(udpSocket STATIC UdpSocket.cpp)
add_library(kcpSocket STATIC KcpSocket.cpp)
add_library(Network STATIC KcpEmulator.cpp)

target_link_libraries(Network kcpSocket udpSocket tcpSocket ikcp log)
