# Loaded via CMAKE_PROJECT_TOP_LEVEL_INCLUDES; change only our staged build.
# Run after upstream has created its server targets. This works with ZIP
# snapshots without editing upstream CMake files or relying on a fork's options.
include_guard(GLOBAL)

function(lsb_android_link_jemalloc)
    # GCC 15 at -O3 reports potential null dereferences in SYSTEM headers:
    # ASIO 1.38.0 io_context.hpp:871 / scheduler.ipp:338, and function2 4.2.4
    # retrieve/process_cmd through std::align. Both libraries establish nonnull
    # invariants before these paths. Keep the diagnostic visible in consumers
    # of those two header targets, without weakening other warning categories.
    if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU"
       AND CMAKE_CXX_COMPILER_VERSION VERSION_GREATER_EQUAL 15
       AND CMAKE_CXX_COMPILER_VERSION VERSION_LESS 16)
        foreach(header_library IN ITEMS asio function2)
            if(TARGET ${header_library})
                target_compile_options(${header_library} INTERFACE -Wno-error=null-dereference)
            endif()
        endforeach()
        if(TARGET asio)
            # ASIO's coroutine new/delete call the matching frame recycler;
            # aligned_new uses aligned_alloc and aligned_delete uses free.
            # GCC 15 loses that pairing after inlining the allocation path.
            target_compile_options(asio INTERFACE -Wno-error=mismatched-new-delete)
        endif()
    endif()
    find_library(LSB_ANDROID_JEMALLOC NAMES jemalloc REQUIRED)
    if(NOT LSB_ANDROID_JEMALLOC MATCHES "[.]so([.][0-9]+)*$")
        message(FATAL_ERROR "LSB Android requires shared jemalloc (install libjemalloc-dev)")
    endif()
    set(lsb_android_servers xi_connect xi_map xi_search xi_world)
    # Older source revisions predate the PlayOnline profile service. Newer
    # loaders require it, and it must use the same allocator as the other roles.
    if(TARGET xi_profile)
        list(APPEND lsb_android_servers xi_profile)
    endif()
    foreach(server IN LISTS lsb_android_servers)
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
