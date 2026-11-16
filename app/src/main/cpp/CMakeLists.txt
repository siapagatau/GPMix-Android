cmake_minimum_required(VERSION 3.22.1)
project(gpmix LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(gpmix SHARED engine.cpp jni_bridge.cpp)
target_compile_features(gpmix PRIVATE cxx_std_17)
target_compile_options(gpmix PRIVATE -O3 -ffast-math)
target_link_libraries(gpmix oboe::oboe log android)
