# Imported ARM64 server: BFD 2.45 compatibility

The 0.5.31 phone report identifies `libbfd-2.45-system.so` as the only
unresolved dependency of all four imported `xi_*` executables. Updating the
Ubuntu 26.04 toolchain supplies its current BFD version, which has a different
SONAME. Renaming or symlinking a newer BFD library is not an ABI repair.

Version 0.5.32 installs the original Ubuntu ARM64 BFD 2.45 library and its
SFrame 2 dependency in `/opt/lsb-compat/binutils-2.45/lib`. Only those two
versioned libraries are extracted. The installer does not install old Debian
packages, change apt sources, replace system headers/tools, create `libbfd.so`,
or change the imported server binaries. `/etc/ld.so.conf.d/lsb-binutils-2.45.conf`
and `ldconfig` make the directory available to validation and managed processes.
Fresh server builds continue to use the current system headers and linker.

`server/bfd_compat.py` bounds each download to its expected size and verifies
SHA-256 before extraction. A fresh Python process loads BFD with immediate
symbol resolution and calls `bfd_init`, first against the staged directory and
then through the normal loader cache. Bootstrap writes the v4 readiness marker
only after these checks pass. Failed downloads or staged loader checks preserve
the existing compatibility libraries and loader configuration; setup can retry.

## Pinned official packages

Version: **2.45-7ubuntu1.2**, architecture: **arm64**. Sizes and hashes were
checked against Ubuntu's `questing-security/main/binary-arm64/Packages` on
2026-09-25. Downloads use the official ports pool, with the official old-releases
pool as a fallback for the identical hash-pinned bytes.

| Package | Bytes | SHA-256 |
| --- | ---: | --- |
| libbinutils | 800450 | b07eb33595ee9e00b2bf6b1af8885e62ddad432e8c2bdbe9ed69b6698db3884c |
| libsframe2 | 16336 | 90501ac7289e12e9b7ce81b2c3f61b410bd2d59dd29ce30b3cf820460690187f |
| binutils-dev (test headers only) | 12947812 | a36b1e07661eabd0d9c9f22299b07b12bbcf6084b9be724ac55bfe946933230e |

Sources:

- [Ubuntu package file list](https://packages.ubuntu.com/questing/arm64/libbinutils/filelist)
- [Official package pool](https://ports.ubuntu.com/ubuntu-ports/pool/main/b/binutils/)
- [Official package index](https://ports.ubuntu.com/ubuntu-ports/dists/questing-security/main/binary-arm64/Packages.gz)
- [Upstream source archive](https://ports.ubuntu.com/ubuntu-ports/pool/main/b/binutils/binutils_2.45.orig.tar.xz)
- [Ubuntu source changes](https://ports.ubuntu.com/ubuntu-ports/pool/main/b/binutils/binutils_2.45-7ubuntu1.2.debian.tar.xz)

## Verification and device retry

The ARM64 integration fixture uses matching 2.45 headers and invokes real BFD
open/object-format/close operations. It removes the compatibility libraries,
reproduces the missing SONAME, reinstalls them, and validates/runs the same
unchanged executables. It checks that current system BFD bytes, its development
symlink and installed toolchain package versions are unchanged. Managed server
fixtures use both BFD and jemalloc during start/stop and MariaDB recovery tests.
These fixtures do not establish gameplay with the user's private server ZIP.

Install 0.5.32 over the existing APK. In **Server → Import your working server**,
choose **Update server runtime and build tools**, then **Deploy matching server +
database**. Existing source and SQL imports remain available. This operation
does not recompile the imported server or force a jemalloc preload/linker flag.
