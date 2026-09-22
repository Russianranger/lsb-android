FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev libx11-dev libxext-dev libxfixes-dev libxdamage-dev libxcb1-dev libxcb-shm0-dev && rm -rf /var/lib/apt/lists/*
COPY frame.h transfer.h x11-frame-bridge.c x11-upload.c x11-upload-check.c /src/
RUN mkdir /out && cc -std=c11 -O2 -Wall -Wextra -Werror /src/x11-frame-bridge.c -Wl,-Bstatic -lXdamage -Wl,-Bdynamic -lX11 -lXext -lXfixes -o /out/x11-frame-bridge
RUN cat /usr/share/doc/libxdamage-dev/copyright > /out/presentation-notices.txt
RUN cc -std=c11 -O2 -Wall -Wextra -Werror -shared -fPIC /src/x11-upload.c -lxcb -lxcb-shm -ldl -pthread -o /out/liblsb-x11-upload.so
RUN cc -std=c11 -O2 -Wall -Wextra -Werror /src/x11-upload-check.c -lxcb -o /out/x11-upload-check
