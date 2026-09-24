cmake_minimum_required(VERSION 3.22)
project(anbu LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Vendored sources live here. If a subtree is absent we fetch it at configure time.
set(ANBU_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party" CACHE PATH "Vendored third-party sources")

# Pin these for reproducible builds. `master` tracks upstream and WILL drift:
# llama.cpp's C API is not stable across revisions. Bump deliberately, then rebuild.
# Verified against these tags: llama.h @ v0.4.1 has the 2-arg llama_batch_get_one
# and the 5-arg llama_sampler_init_penalties that anbu_engine.cpp calls; usearch
# v2.26.2 still exposes index_dense_t + the `usearch` INTERFACE target.
set(ANBU_LLAMA_CPP_TAG "v0.4.1" CACHE STRING "llama.cpp git tag/commit")
set(ANBU_USEARCH_TAG "v2.26.2" CACHE STRING "usearch git tag/commit")
set(ANBU_SQLITE_URL "https://sqlite.org/2024/sqlite-amalgamation-3470200.zip"
    CACHE STRING "sqlite amalgamation zip (bump to latest from sqlite.org/download.html)")

include(FetchContent)
set(FETCHCONTENT_QUIET OFF)

# ---------------------------------------------------------------- llama.cpp ----
# Cache vars must be set BEFORE the subproject is declared.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)   # no libcurl on Android
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)   # NDK ships no libomp
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)   # never probe the host CPU
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

if(EXISTS "${ANBU_THIRD_PARTY}/llama.cpp/CMakeLists.txt")
    message(STATUS "anbu: vendored llama.cpp")
    add_subdirectory("${ANBU_THIRD_PARTY}/llama.cpp" "${CMAKE_BINARY_DIR}/_sub/llama.cpp" EXCLUDE_FROM_ALL)
else()
    message(STATUS "anbu: fetching llama.cpp @ ${ANBU_LLAMA_CPP_TAG}")
    FetchContent_Declare(llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        ${ANBU_LLAMA_CPP_TAG}
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(llama_cpp)
endif()

# ------------------------------------------------------------------ USearch ----
# usearch is header-only here (target `usearch`, INTERFACE). These names are the
# real cache options; NUMKONG must stay OFF or the build needs the numkong
# submodule, which a plain git clone will not have fetched.
set(USEARCH_INSTALL         OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_TEST_CPP  OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_BENCH_CPP OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_LIB_C     OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_TEST_C    OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_JNI       OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_SQLITE    OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_WOLFRAM   OFF CACHE BOOL "" FORCE)
set(USEARCH_USE_NUMKONG     OFF CACHE BOOL "" FORCE)
set(USEARCH_USE_JEMALLOC    OFF CACHE BOOL "" FORCE)
set(USEARCH_USE_OPENMP      OFF CACHE BOOL "" FORCE)

if(EXISTS "${ANBU_THIRD_PARTY}/usearch/CMakeLists.txt")
    message(STATUS "anbu: vendored usearch")
    add_subdirectory("${ANBU_THIRD_PARTY}/usearch" "${CMAKE_BINARY_DIR}/_sub/usearch" EXCLUDE_FROM_ALL)
else()
    message(STATUS "anbu: fetching usearch @ ${ANBU_USEARCH_TAG}")
    FetchContent_Declare(usearch
        GIT_REPOSITORY https://github.com/unum-cloud/usearch.git
        GIT_TAG        ${ANBU_USEARCH_TAG}
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(usearch)
endif()

# usearch's CMake target name has moved between releases. Detect it rather than
# hard-coding; if absent, anbu_rag.cpp compiles the FTS5-only path.
if(TARGET usearch)
    set(ANBU_USEARCH_TARGET usearch)
    set(ANBU_HAVE_USEARCH 1)
elseif(TARGET usearch::usearch)
    set(ANBU_USEARCH_TARGET usearch::usearch)
    set(ANBU_HAVE_USEARCH 1)
else()
    message(WARNING "anbu: usearch target not found — building FTS5-only retrieval")
    set(ANBU_HAVE_USEARCH 0)
endif()

# ------------------------------------------------------------------- SQLite ----
# System libsqlite3 on Android is not guaranteed to expose FTS5, so we build our own.
# The amalgamation zip ships no CMakeLists.txt, so FetchContent_MakeAvailable would
# hard-fail; plain file(DOWNLOAD)+file(ARCHIVE_EXTRACT) is the whole job here.
if(EXISTS "${ANBU_THIRD_PARTY}/sqlite/sqlite3.c")
    set(ANBU_SQLITE_C "${ANBU_THIRD_PARTY}/sqlite/sqlite3.c")
else()
    set(_anbu_sqlite_dir "${CMAKE_BINARY_DIR}/_deps/sqlite-amalgamation")
    # The zip nests sqlite3.c under sqlite-amalgamation-<version>/, so glob for it
    # rather than assuming a depth. Idempotent: already-extracted trees just match.
    file(GLOB_RECURSE ANBU_SQLITE_C "${_anbu_sqlite_dir}/sqlite3.c")
    if(NOT ANBU_SQLITE_C)
        message(STATUS "anbu: downloading sqlite amalgamation from ${ANBU_SQLITE_URL}")
        file(MAKE_DIRECTORY "${_anbu_sqlite_dir}")
        file(DOWNLOAD "${ANBU_SQLITE_URL}" "${_anbu_sqlite_dir}/sqlite.zip"
             STATUS _anbu_dl TLS_VERIFY ON)
        list(GET _anbu_dl 0 _anbu_dl_code)
        if(NOT _anbu_dl_code EQUAL 0)
            list(GET _anbu_dl 1 _anbu_dl_msg)
            message(FATAL_ERROR
                "anbu: sqlite download failed (${_anbu_dl_msg}).\n"
                "Fix: set -DANBU_SQLITE_URL=<url>, or drop sqlite3.c into "
                "${ANBU_THIRD_PARTY}/sqlite/")
        endif()
        file(ARCHIVE_EXTRACT INPUT "${_anbu_sqlite_dir}/sqlite.zip"
             DESTINATION "${_anbu_sqlite_dir}")
        file(GLOB_RECURSE ANBU_SQLITE_C "${_anbu_sqlite_dir}/sqlite3.c")
    endif()
endif()

if(NOT ANBU_SQLITE_C)
    message(FATAL_ERROR "anbu: sqlite3.c not found — cannot build the FTS5 store")
endif()
list(GET ANBU_SQLITE_C 0 ANBU_SQLITE_C)
get_filename_component(ANBU_SQLITE_INC "${ANBU_SQLITE_C}" DIRECTORY)

add_library(anbu_sqlite STATIC "${ANBU_SQLITE_C}")
target_include_directories(anbu_sqlite PUBLIC "${ANBU_SQLITE_INC}")
target_compile_definitions(anbu_sqlite PUBLIC
    SQLITE_ENABLE_FTS5=1
    SQLITE_THREADSAFE=2          # multi-thread: connection per thread
    SQLITE_OMIT_LOAD_EXTENSION=1
    SQLITE_DQS=0
    SQLITE_DEFAULT_MEMSTATUS=0
    SQLITE_OMIT_DEPRECATED=1)

# --------------------------------------------------------------------- anbu ----
add_library(anbu SHARED
    native-lib.cpp
    anbu_engine.cpp
    anbu_rag.cpp)

target_include_directories(anbu PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

target_compile_definitions(anbu PRIVATE ANBU_HAVE_USEARCH=${ANBU_HAVE_USEARCH})
target_compile_options(anbu PRIVATE -O3)

target_link_libraries(anbu
    PRIVATE
        llama
        anbu_sqlite
        android
        log)

if(ANBU_HAVE_USEARCH)
    target_link_libraries(anbu PRIVATE ${ANBU_USEARCH_TARGET})
endif()

# 16 KB page size alignment (Android 15+ requirement for native .so).
target_link_options(anbu PRIVATE "-Wl,-z,max-page-size=16384")
