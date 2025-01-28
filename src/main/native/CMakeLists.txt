cmake_minimum_required(VERSION 3.5)

if(NOT DEFINED NATIVE_LIBRARY_NAME)
    message(SEND_ERROR "Must pass NATIVE_LIBRARY_NAME to project" )
endif()
message(STATUS "Using library name: ${NATIVE_LIBRARY_NAME}")
project(${NATIVE_LIBRARY_NAME})

if(NOT DEFINED JAVA_HOME)
    set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
message(STATUS "Using Java home: ${JAVA_HOME}")

find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})
include_directories(${JNI_INCLUDE_DIRS2})
add_subdirectory(boringssl)

file(GLOB HEADER_FILES CONFIGURE_DEPENDS "include/*.h")
message(STATUS ${HEADER_FILES})
file(GLOB SOURCE_FILES CONFIGURE_DEPENDS "*.h" "*.cpp")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../target/classes)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17")
add_library(${NATIVE_LIBRARY_NAME} SHARED ${HEADER_FILES} ${SOURCE_FILES})
target_link_libraries(
${NATIVE_LIBRARY_NAME}
    -L${JAVA_HOME}/lib/ -L${JAVA_HOME}/lib/server -L${JAVA_HOME}/jre/lib -L${JAVA_HOME}/jre/lib/server
    -ljava -lnio -lnet ssl crypto
)
target_include_directories(${NATIVE_LIBRARY_NAME} PRIVATE boringssl/include)
