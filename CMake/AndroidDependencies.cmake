include(FetchContent)

function(android_apply_patch dir patch)
    execute_process(
        COMMAND git apply --check --reverse ${patch}
        WORKING_DIRECTORY ${dir}
        RESULT_VARIABLE already_applied
        OUTPUT_QUIET ERROR_QUIET
    )
    if(NOT already_applied EQUAL 0)
        execute_process(
            COMMAND git apply ${patch}
            WORKING_DIRECTORY ${dir}
            RESULT_VARIABLE apply_result
        )
        if(NOT apply_result EQUAL 0)
            message(FATAL_ERROR "Could not apply ${patch} to ${dir}")
        endif()
    endif()
endfunction()

# Not upstream yet (Kenix3/libultraship#1214). Fix by izzy2lost.
android_apply_patch(${CMAKE_SOURCE_DIR}/libultraship ${CMAKE_SOURCE_DIR}/android/patches/libultraship-arm64-tbi.patch)
# The NDK has no execinfo.h below API 33, so ZAPD's backtrace handler can't build.
android_apply_patch(${CMAKE_SOURCE_DIR}/ZAPDTR ${CMAKE_SOURCE_DIR}/android/patches/zapdtr-android-crashhandler.patch)

find_package(Ogg QUIET)
if(NOT Ogg_FOUND)
    FetchContent_Declare(
        Ogg
        GIT_REPOSITORY https://github.com/xiph/ogg.git
        GIT_TAG v1.3.5
        GIT_SHALLOW TRUE
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(Ogg)
endif()

find_package(Vorbis QUIET)
if(NOT Vorbis_FOUND)
    FetchContent_Declare(
        Vorbis
        GIT_REPOSITORY https://github.com/xiph/vorbis.git
        GIT_TAG v1.3.7
        GIT_SHALLOW TRUE
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(Vorbis)

    # Vorbis only defines the Vorbis:: names in the config package it installs.
    foreach(component vorbis vorbisenc vorbisfile)
        if(TARGET ${component} AND NOT TARGET Vorbis::${component})
            add_library(Vorbis::${component} ALIAS ${component})
        endif()
    endforeach()
endif()

find_package(Opus QUIET)
if(NOT Opus_FOUND)
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
        Opus
        GIT_REPOSITORY https://github.com/xiph/opus.git
        GIT_TAG v1.5.2
        GIT_SHALLOW TRUE
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(Opus)
endif()

find_package(OpusFile QUIET)
if(NOT OpusFile_FOUND)
    set(OP_DISABLE_HTTP ON CACHE BOOL "" FORCE)
    set(OP_DISABLE_EXAMPLES ON CACHE BOOL "" FORCE)
    set(OP_DISABLE_DOCS ON CACHE BOOL "" FORCE)
    FetchContent_Declare(
        OpusFile
        GIT_REPOSITORY https://github.com/xiph/opusfile.git
        # The last release, v0.12, predates opusfile's CMake support.
        GIT_TAG 6dfd29e7adb87f2e193575fc3fa88cbf1a0b27df
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(OpusFile)
endif()

find_package(PNG QUIET)
if(NOT PNG_FOUND)
    set(PNG_SHARED OFF CACHE BOOL "" FORCE)
    set(PNG_STATIC ON CACHE BOOL "" FORCE)
    set(PNG_TESTS OFF CACHE BOOL "" FORCE)
    set(PNG_TOOLS OFF CACHE BOOL "" FORCE)
    set(PNG_ARM_NEON off CACHE STRING "" FORCE)
    FetchContent_Declare(
        PNG
        GIT_REPOSITORY https://github.com/pnggroup/libpng.git
        GIT_TAG v1.6.43
        GIT_SHALLOW TRUE
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(PNG)

    # ZAPD links PNG::PNG, which a subproject build of libpng never defines.
    if(TARGET png_static AND NOT TARGET PNG::PNG)
        add_library(PNG::PNG ALIAS png_static)
    endif()
    set(PNG_PNG_INCLUDE_DIR ${png_BINARY_DIR} ${png_SOURCE_DIR})
    set(PNG_INCLUDE_DIRS ${png_BINARY_DIR} ${png_SOURCE_DIR})
endif()
