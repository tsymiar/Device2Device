cmake_minimum_required(VERSION 3.4.1)

add_library(timestamp STATIC TimeStamp.cpp)
aux_source_directory(. DIR_SRCS)

find_library(android android)

target_link_libraries(timestamp log)