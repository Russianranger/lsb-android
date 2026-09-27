# Loaded via CMAKE_PROJECT_TOP_LEVEL_INCLUDES; change only our staged build.
# Run after upstream has created the four server targets. This works with ZIP
# snapshots without editing upstream CMake files or relying on a fork's options.
include_guard(GLOBAL)

function(lsb_android_link_jemalloc)
    find_library(LSB_ANDROID_JEMALLOC NAMES jemalloc REQUIRED)
    if(NOT LSB_ANDROID_JEMALLOC MATCHES "[.]so([.][0-9]+)*$")
        message(FATAL_ERROR "LSB Android requires shared jemalloc (install libjemalloc-dev)")
    endif()
    foreach(server IN ITEMS xi_connect xi_map xi_search xi_world)
        if(NOT TARGET ${server})
            message(FATAL_ERROR "Selected source does not define required server target ${server}")
        endif()
        # Retain the allocator even when only dependent libraries use malloc.
        # Restore linker state immediately so other libraries keep their policy.
        target_link_libraries(${server} PRIVATE
            "-Wl,--push-state,--no-as-needed"
            "${LSB_ANDROID_JEMALLOC}"
            "-Wl,--pop-state")
    endforeach()
    message(STATUS "LSB Android allocator: ${LSB_ANDROID_JEMALLOC}")
endfunction()

cmake_language(DEFER DIRECTORY "${CMAKE_SOURCE_DIR}" CALL lsb_android_link_jemalloc)
