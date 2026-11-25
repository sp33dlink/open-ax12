cmake_minimum_required(VERSION 3.22.1)

# The STL is selected here and deliberately NOT via -DANDROID_STL in build.gradle.kts:
# if Gradle knows about c++_shared it copies the NDK's own libc++_shared.so next to the build
# output, which then collides with (and could replace) the one shipped with the SDK in jniLibs.
# Must be set before project(), where the NDK toolchain file reads it.
set(ANDROID_STL c++_shared)

project(openax12jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Prebuilt vendor libraries. They are packaged into the APK from src/main/jniLibs by Gradle;
# here they are only link-time inputs. Never modify, rename or replace them.
set(PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(rmAxSdk1 SHARED IMPORTED)
set_target_properties(rmAxSdk1 PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/librmAxSdk1.so)

# Linked directly so that the bridge can call QtAndroidPrivate::initJNI (caches the JavaVM).
add_library(Qt6Core SHARED IMPORTED)
set_target_properties(Qt6Core PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libQt6Core_arm64-v8a.so)

# The one and only C++ runtime of the app is the libc++_shared.so shipped with the SDK
# (std::string / std::list cross the library boundary). Linking against that exact file with
# -nostdlib++ makes the link fail if this code needs a libc++ symbol the shipped runtime lacks,
# instead of crashing with UnsatisfiedLinkError on the radio.
add_library(sdk_cxx_shared SHARED IMPORTED)
set_target_properties(sdk_cxx_shared PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libc++_shared.so)

add_library(openax12jni SHARED openax12_jni.cpp)

target_include_directories(openax12jni PRIVATE include)
target_compile_options(openax12jni PRIVATE -Wall -Wextra)
target_link_options(openax12jni PRIVATE -nostdlib++)
target_link_libraries(openax12jni PRIVATE
    rmAxSdk1
    Qt6Core
    sdk_cxx_shared
    log
    android)
