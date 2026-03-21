cmake_minimum_required(VERSION 3.18.1)

project("dadb_android")

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(boringssl REQUIRED CONFIG)

add_library(
    adbpairing
    SHARED
    pairing/adb_pairing.cpp
)

find_library(log-lib log)

target_link_libraries(
    adbpairing
    ${log-lib}
    boringssl::crypto_static
)
