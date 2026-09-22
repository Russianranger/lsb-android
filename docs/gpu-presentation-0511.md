# 0.5.11: Vulkan upload transport and DXVK comparison

The user clarified that GameHub's 60 FPS came from its overlay, and confirms
that it was visibly smooth. Do not ask this again or dismiss the comparison.
This iteration targets upstream presentation traffic and DXVK. It retains the
accepted 0.5.8 startup-only observer, 60 Hz preference, controller, audio,
original xiloader and matching Termux server/client `30251204_1`.

## Scope and mechanism

The pinned Mesa 26 source selects CPU swapchain images under
`MESA_VK_WSI_DEBUG=sw`. Its X11 shared-pixmap path requires DRI3/Present and host
memory import, so TigerVNC uses PutImage even though ordinary MIT-SHM works.
The prior native display improvements were downstream of this transfer.

The new opt-out **Shared-memory Vulkan presentation** option preloads a native
ARM64 glibc XCB interposer into the Box64 host, alongside the working controller
preload. It replaces eligible full-image `xcb_put_image` calls with MIT-SHM
uploads; other formats, row updates and unavailable SHM retain original XCB.
Three slots per connection are reused only after a reply queued after the
upload confirms server consumption. Checked-request cookies and application
input events are preserved. Segments are private, marked for removal after
attach, and detached on disconnect; allocation is bounded. Resizes grow slots.

This reduces socket payload/copies in GPU presentation. **It still performs GPU
readback and a CPU copy into SHM; it is not direct GPU-to-Android presentation.**
The driver binary, WSI software flag, X server and Android capture path remain
available unchanged. A startup pixel/ownership check disables the new preload
if unavailable, without removing the gamepad preload. No gameplay observer,
periodic file writer or module scanning is added.

Numeric `wsi-upload-<pid>.bin` counters are mmap-backed (twelve little-endian
uint64s): magic `0x4c534257534931`, calls, SHM uploads, socket fallbacks, bytes
redirected, copy ns, slot-wait ns, attach failures, connections, maximum wait ns,
maximum copy ns, completed fences. They contain no arbitrary process text or
credentials. Diagnostics retains the current and previous run. Preflight alone
has 40 SHM calls; a game process must have its own advancing counters before
claiming the optimization was actually used.

## DXVK

The independent **DXVK 2.7.1** option defaults off; off selects the working
2.5.3 pair. Both matching x86 D3D8/D3D9 pairs are packaged and verified. Upstream
2.7.1 archive SHA-256 is
`d85ce7c79f57ecd765aaa1b9e7007cb875e6fde9f6d331df799bce73d513ce87`.
It is not GameHub's async fork. A finite app-owned D3D8 triangle check creates a
device, draws and presents eight frames before login, exercising the native
D3D8/D3D9 bridge and actual Vulkan requirements rather than trusting a version
label. Failure/timeout restores both 2.5.3 DLLs before starting the loader.
Requested/selected versions, DLL hashes, fallback reason and upload preflight
result are exported in runtime state. Candidate DXVK state cache is separate.

The existing renderer environment is retained, including the required CPU X11
WSI selection. Hardware Vulkan still requires the real Qualcomm/Turnip probe;
there is no automatic software-renderer substitution. Both new options off
retain the previous graphics binaries, environment and transfer path.

## Validation and Thor test

Implementation validation is in progress. The runtime gate must prove the
interposer is reached by actual Wine/DXVK rendering, not just its native unit
fixture. Baseline Mesa checks candidate fallback; a separate CI-only modern
Mesa container executes 2.7.1. It never changes the distributed rootfs.

Install over the existing app. Keep 60 Hz, Native Surface, same resolution,
normal FPS HUD, startup capture off, compression/fast-display settings unchanged.
Use the existing working Termux server. No update, import or preparation.

1. New shared-memory Vulkan presentation on; DXVK 2.7.1 off. Warm up, repeat
   the same route/camera movements for two minutes, stop and export Diagnostics.
2. Enable DXVK 2.7.1, retain shared-memory presentation, repeat the same route.
   Stop and export Diagnostics separately. Compatibility checks can add startup
   time; state reports whether 2.7.1 was actually selected.
3. If either is worse or has visual issues, turn off that option and relaunch.
   Both off restores the previous graphics paths. Confirm audio, right-stick
   camera, exit and relaunch remain good.

No Thor performance gain is claimed until this build is tested on the device.
