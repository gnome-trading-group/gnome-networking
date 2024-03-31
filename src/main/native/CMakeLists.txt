cmake_minimum_required(VERSION 3.5)

## TODO: REMOVE THIS
set(NATIVE_LIBRARY_NAME "NativeSockets")

if(NOT DEFINED NATIVE_LIBRARY_NAME)
    message(SEND_ERROR "Must pass NATIVE_LIBRARY_NAME to project" )
endif()
message(STATUS "Using library name: ${NATIVE_LIBRARY_NAME}")
project(${NATIVE_LIBRARY_NAME})

if(NOT DEFINED JAVA_HOME)
    set(JAVA_HOME "/Users/mprey/Library/Java/JavaVirtualMachines/corretto-1.8.0_402/Contents/Home/")
    #set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
message(STATUS "Using Java home: ${JAVA_HOME}")

find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})
include_directories(${JNI_INCLUDE_DIRS2})

set(JAVA_HOME "$ENV{JAVA_HOME}/lib")
message(STATUS ${JAVA_HOME})
find_library(JAVA_NET NAMES net HINTS "${JAVA_HOME}" REQUIRED)
# find_library(JAVA_JVM NAMES jvm HINTS "${JAVA_HOME}/lib" REQUIRED)
find_library(JAVA_NIO NAMES nio HINTS "${JAVA_HOME}" REQUIRED)
find_library(JAVA_JAVA NAMES java HINTS "${JAVA_HOME}" REQUIRED)

message(STATUS ${JAVA_JAVA})
file(GLOB SOURCE_FILES CONFIGURE_DEPENDS "*.h" "*.cpp")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../target/classes)

include_directories(${JAVA_HOME})
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17")
add_library(${NATIVE_LIBRARY_NAME} SHARED ${SOURCE_FILES})
#target_include_directories(${NATIVE_LIBRARY_NAME} PUBLIC jvm.h REQUIRED)
#target_link_directories(${NATIVE_LIBRARY_NAME} PUBLIC ${JAVA_HOME})
#target_link_libraries(${NATIVE_LIBRARY_NAME} java net nio)