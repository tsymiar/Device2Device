cmake_minimum_required(VERSION 3.4.1)
set(CMAKE_BUILD_TYPE "Debug")

add_library(ikcp STATIC kcp/ikcp.c)
add_library(tcpSocket STATIC TcpSocket.cpp)
add_library(udpSocket STATIC UdpSocket.cpp)
add_library(Network STATIC KcpEmulator.cpp)

target_link_libraries(Network udpSocket tcpSocket ikcp log)
