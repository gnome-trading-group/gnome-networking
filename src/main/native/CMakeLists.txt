cmake_minimum_required(VERSION 3.5)

if(NOT DEFINED NATIVE_LIBRARY_NAME)
    message(SEND_ERROR "Must pass NATIVE_LIBRARY_NAME to project" )
endif()
message(STATUS "Using library name: ${NATIVE_LIBRARY_NAME}")
project(${NATIVE_LIBRARY_NAME})

# Without a build type CMake compiles with no optimization at all: BoringSSL's RSA and libsecp256k1 then sign
# slower than the JDK and BouncyCastle they replace.
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

if(NOT DEFINED JAVA_HOME)
    set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
message(STATUS "Using Java home: ${JAVA_HOME}")

find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})
include_directories(${JNI_INCLUDE_DIRS2})
# Both vendored libraries are linked statically into the one JNI library; libsecp256k1 would otherwise default to
# a shared build, and the cache entry it creates would turn BoringSSL shared too on the next configure.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(boringssl)

# libsecp256k1 is vendored from its v0.6.0 release (commit 0cdc758), unmodified apart from dropping its CI files.
set(SECP256K1_DISABLE_SHARED ON CACHE BOOL "" FORCE)
set(SECP256K1_ENABLE_MODULE_RECOVERY ON CACHE BOOL "" FORCE)
set(SECP256K1_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(SECP256K1_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SECP256K1_BUILD_EXHAUSTIVE_TESTS OFF CACHE BOOL "" FORCE)
set(SECP256K1_BUILD_CTIME_TESTS OFF CACHE BOOL "" FORCE)
set(SECP256K1_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(secp256k1)

file(GLOB HEADER_FILES CONFIGURE_DEPENDS "include/*.h")
message(STATUS ${HEADER_FILES})
file(GLOB SOURCE_FILES CONFIGURE_DEPENDS "*.h" "*.cpp")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../target/classes)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17")
add_library(${NATIVE_LIBRARY_NAME} SHARED ${HEADER_FILES} ${SOURCE_FILES})
target_link_libraries(
${NATIVE_LIBRARY_NAME}
    -L${JAVA_HOME}/lib/ -L${JAVA_HOME}/lib/server -L${JAVA_HOME}/jre/lib -L${JAVA_HOME}/jre/lib/server
    -ljava -lnio -lnet ssl crypto secp256k1
)
target_include_directories(${NATIVE_LIBRARY_NAME} PRIVATE boringssl/include secp256k1/include)
