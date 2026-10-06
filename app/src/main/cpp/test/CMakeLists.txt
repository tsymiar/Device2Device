cmake_minimum_required(VERSION 2.8...3.22)
project(test)

# ---- FileMsg server (--server 只起服务；默认跑单进程集成测试) ----
add_executable(FileMsgServer
    FileMsgServer.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/FileMsgSocket.cpp
)

target_include_directories(FileMsgServer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

set_property(TARGET FileMsgServer PROPERTY CXX_STANDARD 11)

# ---- FileMsg client (standalone CLI, 连一个已经在跑的 FileMsgServer) ----
add_executable(FileMsgClient
    FileMsgClient.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/FileMsgSocket.cpp
)

target_include_directories(FileMsgClient PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

set_property(TARGET FileMsgClient PROPERTY CXX_STANDARD 11)

# ---- Network client test (TCP / UDP / KCP) ----
add_executable(NetworkClient
    NetworkClient.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/UdpSocket.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/KcpSocket.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/kcp/ikcp.c
)

# KcpSocket.cpp uses <utils/logging.h> → need cpp root
target_include_directories(NetworkClient PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

# Define _KCP_TEST_ so KcpSocket sends/receives test handshake messages
target_compile_definitions(NetworkClient PRIVATE _KCP_TEST_)

# Enable C++11 for std::thread etc.
set_property(TARGET NetworkClient PROPERTY CXX_STANDARD 11)

# ---- Network server test (TCP / UDP / KCP echo servers) ----
add_executable(NetworkServer
    NetworkServer.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/UdpSocket.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/KcpSocket.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/kcp/ikcp.c
)

target_include_directories(NetworkServer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

target_compile_definitions(NetworkServer PRIVATE _KCP_TEST_)
set_property(TARGET NetworkServer PROPERTY CXX_STANDARD 11)

if(ANDROID)
    target_link_libraries(FileMsgServer log)
    target_link_libraries(FileMsgClient log)
    target_link_libraries(NetworkClient log)
    target_link_libraries(NetworkServer log)
endif()
