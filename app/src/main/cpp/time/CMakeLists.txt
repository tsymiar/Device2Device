cmake_minimum_required(VERSION 2.8...3.16)

add_library(timestamp STATIC TimeStamp.cpp)
aux_source_directory(. DIR_SRCS)

find_library(android android)

target_link_libraries(timestamp log)
