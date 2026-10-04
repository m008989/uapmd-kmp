cmake_minimum_required(VERSION 3.22)
project(uapmd-kmp LANGUAGES C CXX)

# Match uapmd's C++ standard so all remidy/uapmd headers compile identically.
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_SCAN_FOR_MODULES OFF)

set(UAPMD_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/external/uapmd/source"
    CACHE PATH "Path to the uapmd source tree")

# Every UAPMD_ENABLE_* feature option, on. Must precede add_subdirectory():
# uapmd declares the same options itself, and option() is a no-op once a cache
# entry of that name exists.
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/UapmdFeatureOptions.cmake")

# Build the uapmd engine libraries from the submodule.
# uapmd-c-api is NOT included here; we use our own copy in c-api/.
add_subdirectory("${UAPMD_SOURCE_DIR}" uapmd-source)

# Build our owned C API wrapper (SHARED on desktop, STATIC on mobile/wasm).
add_subdirectory(c-api)
