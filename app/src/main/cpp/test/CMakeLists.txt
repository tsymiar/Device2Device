cmake_minimum_required(VERSION 2.8...3.22)
project(test)

# ---- FileMsgSocket server (fork-based server + client integration test) ----
add_executable(FileMsgSocketServer
    FileMsgSocketServer.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/FileMsgSocket.cpp
)

target_include_directories(FileMsgSocketServer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

# ---- FileMsgSocket client (standalone CLI, no fork needed) ----
add_executable(FileMsgSocketClient
    FileMsgSocketClient.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/FileMsgSocket.cpp
)

target_include_directories(FileMsgSocketClient PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

set_property(TARGET FileMsgSocketClient PROPERTY CXX_STANDARD 11)

# ---- Network client test (TCP / UDP / KCP) ----
add_executable(NetworkClientTest
    NetworkClientTest.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/UdpSocket.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/KcpSocket.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/KcpEmulator.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/kcp/ikcp.c
)

# KcpSocket.cpp uses <utils/logging.h> → need cpp root
target_include_directories(NetworkClientTest PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

# Define _KCP_TEST_ so KcpSocket sends/receives test handshake messages
target_compile_definitions(NetworkClientTest PRIVATE _KCP_TEST_)

# Enable C++11 for std::thread etc.
set_property(TARGET NetworkClientTest PROPERTY CXX_STANDARD 11)

# ---- Network server test (TCP / UDP / KCP echo servers) ----
add_executable(NetworkServerTest
    NetworkServerTest.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/UdpSocket.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/KcpSocket.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/KcpEmulator.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/kcp/ikcp.c
)

target_include_directories(NetworkServerTest PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

target_compile_definitions(NetworkServerTest PRIVATE _KCP_TEST_)
set_property(TARGET NetworkServerTest PROPERTY CXX_STANDARD 11)

if(ANDROID)
    target_link_libraries(FileMsgSocketServer log)
    target_link_libraries(FileMsgSocketClient log)
    target_link_libraries(NetworkClientTest log)
    target_link_libraries(NetworkServerTest log)
endif()
