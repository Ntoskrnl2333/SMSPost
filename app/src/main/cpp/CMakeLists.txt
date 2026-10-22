cmake_minimum_required(VERSION 3.22.1)

project("smspost")

add_library(
    keep_alive
    SHARED
    keep_alive.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    keep_alive
    ${log-lib}
)