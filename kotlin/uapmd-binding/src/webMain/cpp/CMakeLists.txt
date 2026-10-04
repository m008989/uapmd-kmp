cmake_minimum_required(VERSION 3.22)
project(uapmd-wasm-api C CXX)

# This CMakeLists.txt must be built through the Emscripten toolchain:
#   emcmake cmake -S <this-dir> -B <build-dir> [options]
if(NOT EMSCRIPTEN)
    message(FATAL_ERROR
        "uapmd-wasm-api must be configured with emcmake.\n"
        "  emcmake cmake -S ${CMAKE_CURRENT_SOURCE_DIR} -B <build-dir>")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_SCAN_FOR_MODULES OFF)

# ── uapmd source root ─────────────────────────────────────────────────────────
# Five levels up from this file lands at the repo root; source is under
# external/uapmd/source and the owned C API wrapper is under c-api/.
if(NOT DEFINED UAPMD_SOURCE_DIR)
    get_filename_component(UAPMD_SOURCE_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../external/uapmd/source"
        ABSOLUTE)
endif()
if(NOT DEFINED CAPI_DIR)
    get_filename_component(CAPI_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../c-api"
        ABSOLUTE)
endif()

# ── Force settings required for a Wasm library build ─────────────────────────
set(UAPMD_TARGET_WASM  ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)
set(UAPMD_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
set(MIDICCI_SKIP_TOOLS ON  CACHE BOOL "" FORCE)

# Every UAPMD_ENABLE_* feature option, on -- before add_subdirectory(), since
# uapmd declares the same options and option() is a no-op once the cache entry
# exists. uapmd excludes ARA from wasm itself (ARA_API has no wasm32 ABI
# definition), so leaving the option on here changes nothing for this target.
get_filename_component(UAPMD_KMP_CMAKE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../cmake" ABSOLUTE)
include("${UAPMD_KMP_CMAKE_DIR}/UapmdFeatureOptions.cmake")
add_compile_options(-pthread -matomics -mbulk-memory)

# Pull in the full uapmd source tree, then our owned C API wrapper.
add_subdirectory("${UAPMD_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/uapmd")
add_subdirectory("${CAPI_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/uapmd-c-api")

# libremidi's Emscripten helper target exports its own tiny EXPORTED_FUNCTIONS
# list for standalone samples. When it propagates into this final link step it
# overrides the uapmd API export list, leaving only a few MIDI helpers visible.
get_target_property(LIBREMIDI_INTERFACE_LINK_OPTIONS libremidi INTERFACE_LINK_OPTIONS)
if(LIBREMIDI_INTERFACE_LINK_OPTIONS AND NOT LIBREMIDI_INTERFACE_LINK_OPTIONS STREQUAL "NOTFOUND")
    list(FILTER LIBREMIDI_INTERFACE_LINK_OPTIONS EXCLUDE REGEX "EXPORTED_FUNCTIONS=.*_libremidi_devices_poll")
    list(FILTER LIBREMIDI_INTERFACE_LINK_OPTIONS EXCLUDE REGEX "EXPORTED_RUNTIME_METHODS=")
    set_target_properties(libremidi PROPERTIES
        INTERFACE_LINK_OPTIONS "${LIBREMIDI_INTERFACE_LINK_OPTIONS}"
    )
endif()

# uapmd-file exposes browser document-provider helpers through INTERFACE link
# options. Keep its library-function include, but remove its runtime-method
# export list because a second -sEXPORTED_RUNTIME_METHODS overrides ours.
get_target_property(UAPMD_FILE_INTERFACE_LINK_OPTIONS uapmd-file INTERFACE_LINK_OPTIONS)
if(UAPMD_FILE_INTERFACE_LINK_OPTIONS AND NOT UAPMD_FILE_INTERFACE_LINK_OPTIONS STREQUAL "NOTFOUND")
    list(FILTER UAPMD_FILE_INTERFACE_LINK_OPTIONS EXCLUDE REGEX "EXPORTED_RUNTIME_METHODS=")
    set_target_properties(uapmd-file PROPERTIES
        INTERFACE_LINK_OPTIONS "${UAPMD_FILE_INTERFACE_LINK_OPTIONS}"
    )
endif()

# ── Exported C API symbols ────────────────────────────────────────────────────
# Auto-derived from every UAPMD_C_EXPORT declaration in the C API header so the
# list stays in sync when functions are added or removed.
file(GLOB _CAPI_HEADERS "${CAPI_DIR}/include/c-api/*.h")
set(_CAPI_EXPORT_LINES "")
foreach(_hdr IN LISTS _CAPI_HEADERS)
    file(STRINGS "${_hdr}" _lines REGEX "UAPMD_C_EXPORT")
    list(APPEND _CAPI_EXPORT_LINES ${_lines})
endforeach()
set(UAPMD_EXPORTED_FUNCTIONS "_malloc" "_free")
foreach(_line IN LISTS _CAPI_EXPORT_LINES)
    # Extract the last identifier before the opening parenthesis.
    if(_line MATCHES "(uapmd_[a-z0-9_]*)\\(")
        list(APPEND UAPMD_EXPORTED_FUNCTIONS "_${CMAKE_MATCH_1}")
    endif()
endforeach()
list(REMOVE_DUPLICATES UAPMD_EXPORTED_FUNCTIONS)

# Join the list into a comma-separated JSON array string for -sEXPORTED_FUNCTIONS
list(JOIN UAPMD_EXPORTED_FUNCTIONS "," _FUNCS_CSV)
set(UAPMD_EXPORTED_FUNCTIONS_ARG "[${_FUNCS_CSV}]")

# ── Emscripten executable that becomes the JS+Wasm module ────────────────────
add_executable(uapmd-c-api-web "${CMAKE_CURRENT_SOURCE_DIR}/stub_main.c")

# The web stub never references the C API symbols directly, so a normal link
# against the static archive lets the linker discard the entire API surface.
# Force-load the archive so EXPORTED_FUNCTIONS can retain the public C entrypoints.
target_link_libraries(uapmd-c-api-web PRIVATE
    "-Wl,--whole-archive"
    uapmd-c-api
    "-Wl,--no-whole-archive"
)

# Output filename: uapmd-c-api.js (+ uapmd-c-api.wasm generated alongside)
set_target_properties(uapmd-c-api-web PROPERTIES
    OUTPUT_NAME "uapmd-c-api"
    SUFFIX      ".js"
)

target_link_options(uapmd-c-api-web PRIVATE
    # Modular ES6 factory export
    "-sMODULARIZE=1"
    "-sEXPORT_ES6=1"
    "-sEXPORT_NAME=UapmdCApi"
    # Symbol exports
    "-sEXPORTED_FUNCTIONS=${UAPMD_EXPORTED_FUNCTIONS_ARG}"
    # HEAPU8 is included because WebAudioWorkletIODevice.cpp inline JS uses it,
    # causing Emscripten to auto-inject a narrower EXPORTED_RUNTIME_METHODS list
    # at link time that would otherwise override and drop getValue/addFunction.
    # Keeping HEAPU8 here as well lets us be the definitive last word.
    "-sEXPORTED_RUNTIME_METHODS=[HEAPU8,UTF8ToString,stringToUTF8,allocate,ALLOC_NORMAL,allocateUTF8,FS,IDBFS,ccall,getValue,setValue,addFunction,removeFunction,lengthBytesUTF8]"
    # Enable JS BigInt ↔ Wasm i64 so int64_t parameters (timestamps, frame
    # counts) round-trip correctly across the JS boundary.
    "-sWASM_BIGINT=1"
    # Allow the function table to grow for callback registration
    "-sALLOW_TABLE_GROWTH=1"
    # Memory: 64 MB initial, allow growth
    "-sINITIAL_MEMORY=67108864"
    "-sALLOW_MEMORY_GROWTH=1"
    # WebAudioWorklet-backed device I/O needs Emscripten's threaded audio flags.
    "-sSHARED_MEMORY=1"
    "-sWASM_WORKERS=1"
    "-sAUDIO_WORKLET=1"
    "-pthread"
    "-sPTHREAD_POOL_SIZE=8"
    # 8 MB stack for C++ deep call stacks
    "-sSTACK_SIZE=8388608"
    # IndexedDB-backed filesystem: uapmd persists its plugin list cache and the
    # blocklist under /browser, which the app mounts as IDBFS at startup.
    "-lidbfs.js"
    # Target web + worker environments (no Node.js requirement)
    "-sENVIRONMENT=web,worker"
    # Disable filesystem (uapmd-c-api doesn't use stdio in the library binding)
    # Comment this out if file I/O is needed at the C layer:
    # "-sNO_FILESYSTEM=1"
)

# Emscripten may auto-inject a narrower -sEXPORTED_RUNTIME_METHODS after ours
# (from inline JS in dependencies that use HEAPU8/UTF8ToString etc.), which would
# override and drop getValue/addFunction/removeFunction. Appending via
# CMAKE_EXE_LINKER_FLAGS places this AFTER all target and dependency link options,
# making it the final word on the link command line.
string(APPEND CMAKE_EXE_LINKER_FLAGS
    " -sEXPORTED_RUNTIME_METHODS=[HEAPU8,UTF8ToString,stringToUTF8,allocate,ALLOC_NORMAL,allocateUTF8,FS,IDBFS,ccall,getValue,setValue,addFunction,removeFunction,lengthBytesUTF8]"
)
