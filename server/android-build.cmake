# Loaded via CMAKE_PROJECT_TOP_LEVEL_INCLUDES; change only our staged build.
# Run after upstream has created the four server targets. This works with ZIP
# snapshots without editing upstream CMake files or relying on a fork's options.
include_guard(GLOBAL)

function(lsb_android_link_jemalloc)
    # GCC 15 at -O3 reports potential null dereferences inside ASIO 1.38.0
    # io_context.hpp:871 and detail/impl/scheduler.ipp:338 when inlined into
    # application.cpp, even with ASIO's existing SYSTEM includes. Preserve the
    # diagnostic, but do not promote this one warning to a compilation error in
    # the shared server target. Other warnings and compiler versions are intact.
    if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU"
       AND CMAKE_CXX_COMPILER_VERSION VERSION_GREATER_EQUAL 15
       AND CMAKE_CXX_COMPILER_VERSION VERSION_LESS 16
       AND TARGET xi_common)
        target_compile_options(xi_common PRIVATE -Wno-error=null-dereference)
    endif()
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
