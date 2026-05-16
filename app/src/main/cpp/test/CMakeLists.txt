cmake_minimum_required(VERSION 2.8...3.22)
project(test)

add_executable(FileMsgSocketTest
    FileMsgSocketTest.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket/FileMsgSocket.cpp
)

target_include_directories(FileMsgSocketTest PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../socket
)

if(ANDROID)
    target_link_libraries(FileMsgSocketTest log)
endif()
