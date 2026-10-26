cmake_minimum_required(VERSION 3.31.6)
project(motoai-opencl-link-dependency LANGUAGES C)
# Source-built link dependency only. Android runtime resolves the OEM public
# libOpenCL.so via optional uses-native-library. Never package this ICD loader.
set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPENCL_ICD_LOADER_ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
add_subdirectory("${MOTOAI_OPENCL_LOADER_SOURCE}" loader)
set_target_properties(OpenCL PROPERTIES VERSION "" SOVERSION "")
