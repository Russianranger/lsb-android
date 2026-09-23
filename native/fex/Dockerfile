FROM debian:bookworm-slim
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl git build-essential cmake ninja-build python3 xz-utils \
    flex bison pkg-config autoconf automake libtool \
    libx11-dev libxext-dev libxrender-dev libxrandr-dev libxi-dev libxfixes-dev \
    libxcursor-dev libxinerama-dev libxxf86vm-dev libfreetype-dev libfontconfig-dev \
    libasound2-dev libpulse-dev libdbus-1-dev libudev-dev libsdl2-dev \
    libgnutls28-dev libvulkan-dev libgl-dev libunwind-dev \
 && rm -rf /var/lib/apt/lists/*
ARG LLVM=20250910
RUN curl -fL --retry 3 -o /tmp/llvm.tar.xz https://github.com/mstorsjo/llvm-mingw/releases/download/${LLVM}/llvm-mingw-${LLVM}-ucrt-ubuntu-22.04-aarch64.tar.xz \
 && echo '175f344e3db2e11b3d53f4f828ae7b5f0073a4d239b9600fa023ef4b90eb5a7e /tmp/llvm.tar.xz' | sha256sum -c - \
 && mkdir /opt/llvm && tar -xJf /tmp/llvm.tar.xz --strip-components=1 -C /opt/llvm && rm /tmp/llvm.tar.xz
ENV PATH=/opt/llvm/bin:$PATH
WORKDIR /src
# Stable, immutable upstream source identities, independent of GameHub's fork.
RUN git clone --depth 1 --branch wine-10.0 https://github.com/wine-mirror/wine.git wine \
 && test "$(git -C wine rev-parse HEAD)" = b073859675060c9211fcbccfd90e4e87520dc2c2
RUN git clone --depth 1 --branch FEX-2510 https://github.com/FEX-Emu/FEX.git fex \
 && test "$(git -C fex rev-parse HEAD)" = 320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab \
 && git -C fex submodule update --init --depth 1 External/vixl Source/Common/cpp-optparse External/jemalloc External/fmt External/xxhash External/robin-map External/range-v3
RUN cmake -S fex -B fex-build -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE=/src/fex/Data/CMake/toolchain_mingw.cmake \
    -DMINGW_TRIPLE=aarch64-w64-mingw32 -DENABLE_LTO=False -DENABLE_ASSERTIONS=False \
    -DENABLE_JEMALLOC_GLIBC_ALLOC=False -DBUILD_TESTING=False -DTUNE_ARCH=generic -DTUNE_CPU=none \
 && cmake --build fex-build --parallel 4
# Retain the accepted bounded process-exit grace period. No render/audio patches.
RUN python3 - <<'PY'
from pathlib import Path
p=Path('wine/server/process.c');s=p.read_text();old='process->sigkill_delay >= TICKS_PER_SEC / 2'
assert s.count(old)==1;p.write_text(s.replace(old,'process->sigkill_delay >= TICKS_PER_SEC * 8'))
p=Path('wine/loader/wine.inf.in');s=p.read_text()
assert s.count('"xtajit.dll"')==1
p.write_text(s.replace('"xtajit.dll"','"libwow64fex.dll"'))
PY
RUN mkdir wine-build && cd wine-build && ../wine/configure --prefix=/opt/wine \
    --enable-archs=aarch64,i386 --disable-tests --without-oss --without-gstreamer \
    --without-cups --without-sane --without-gphoto --without-pcap --without-kerberos \
    --without-wayland CFLAGS='-O2' \
 && make -j4 && make install DESTDIR=/stage
RUN cp fex-build/Source/Windows/WOW64/libwow64fex.dll /stage/opt/wine/lib/wine/aarch64-windows/ \
 && mkdir -p /stage/opt/wine/share/lsb-sources \
 && cp wine/COPYING.LIB /stage/opt/wine/share/lsb-sources/Wine-LICENSE \
 && cp fex/LICENSE /stage/opt/wine/share/lsb-sources/FEX-LICENSE
COPY native/fex/pack.py /pack.py
CMD ["python3", "/pack.py"]
