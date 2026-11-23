# Native Solo World acceptance tests

Use a disposable database and a checkout of the pinned LandSandBoat server with Solo World enabled, its SQL installed, and the matching navmesh and ximesh submodules checked out. The fixtures create temporary characters and advance the test server's clock.

From the server checkout, set `android_repo` to the absolute path of this Android repository. Copy the two suites into the server's test directory.

```sh
cp "$android_repo/tests/server/solo_squad_world_integration.lua" scripts/tests/systems/handheld_squad_live.lua
cp "$android_repo/tests/server/solo_gathering_integration.lua" scripts/tests/systems/handheld_solo_gathering.lua
```

Upstream CMake links registered C++ modules to `xi_map`. For this test checkout, append the following to `src/test/CMakeLists.txt` once to register the same modules in `xi_test`.

```cmake
target_sources(xi_test PRIVATE
    "${CMAKE_SOURCE_DIR}/modules/handheld_squad/cpp/handheld_storage.cpp"
    "${CMAKE_SOURCE_DIR}/modules/handheld_squad/cpp/handheld_trust.cpp")
```

With the server's normal build directory and test database settings configured, run from the checkout root.

```sh
cmake --build build --target xi_test
./xi_test --file 'handheld_(squad_live|solo_gathering)' --keep-going --verbose
```

The six cases cover the normal client command and release packets, companion combat and persistence, all 20 job templates, a claimed casket in Ghelsba Outpost, logging, crafting skill progression and a fishing cast and release.
