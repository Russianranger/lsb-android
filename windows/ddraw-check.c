/* Finite DirectDraw 7 / fixed-function D3D7 qualification. No client files,
 * registry writes, arbitrary arguments, or persistent display-mode changes. */
#define COBJMACROS
#define DIRECTDRAW_VERSION 0x0700
#define DIRECT3D_VERSION 0x0700
#include <windows.h>
#include <ddraw.h>
#include <d3d.h>
#include <stdio.h>
#include <stdint.h>

#define FRAMES 24
#define WIDTH 320
#define HEIGHT 240
#define PATTERN 128

typedef struct {
    const char *stage;
    HRESULT hr;
    DWORD vendor, device, frames, colorfills, uploads, blits, ffp_frames, presents;
    DWORD readback_samples, presentation_samples;
    DWORD mismatch_sample, expected_rgb, actual_rgb;
    ULONGLONG elapsed_ms, render_ms, first_frame_ms, max_frame_ms;
} Receipt;

static DWORD pattern(unsigned x, unsigned y, unsigned frame)
{
    return (((x * 13 + frame * 3) & 255) << 16)
         | (((y * 7 + frame * 5) & 255) << 8)
         | ((x * 3 + y * 11 + frame * 7) & 255);
}

static HRESULT surface(IDirectDraw7 *ddraw, DWORD caps, unsigned width,
        unsigned height, IDirectDrawSurface7 **result)
{
    DDSURFACEDESC2 desc = {0};
    desc.dwSize = sizeof(desc);
    desc.dwFlags = DDSD_CAPS;
    desc.ddsCaps.dwCaps = caps;
    if (!(caps & DDSCAPS_PRIMARYSURFACE)) {
        desc.dwFlags |= DDSD_WIDTH | DDSD_HEIGHT | DDSD_PIXELFORMAT;
        desc.dwWidth = width;
        desc.dwHeight = height;
        desc.ddpfPixelFormat.dwSize = sizeof(desc.ddpfPixelFormat);
        desc.ddpfPixelFormat.dwFlags = DDPF_RGB;
        desc.ddpfPixelFormat.dwRGBBitCount = 32;
        desc.ddpfPixelFormat.dwRBitMask = 0xff0000;
        desc.ddpfPixelFormat.dwGBitMask = 0x00ff00;
        desc.ddpfPixelFormat.dwBBitMask = 0x0000ff;
    }
    return IDirectDraw7_CreateSurface(ddraw, &desc, result, NULL);
}

static int rgb32(const DDSURFACEDESC2 *desc)
{
    return desc->lpSurface && desc->lPitch >= (LONG)(desc->dwWidth * 4)
        && desc->ddpfPixelFormat.dwRGBBitCount == 32
        && desc->ddpfPixelFormat.dwRBitMask == 0xff0000
        && desc->ddpfPixelFormat.dwGBitMask == 0x00ff00
        && desc->ddpfPixelFormat.dwBBitMask == 0x0000ff;
}

static HRESULT upload(IDirectDrawSurface7 *source, unsigned frame)
{
    DDSURFACEDESC2 desc = {0};
    desc.dwSize = sizeof(desc);
    HRESULT hr = IDirectDrawSurface7_Lock(source, NULL, &desc,
            DDLOCK_WAIT | DDLOCK_WRITEONLY, NULL);
    if (FAILED(hr)) return hr;
    if (!rgb32(&desc) || desc.dwWidth != PATTERN || desc.dwHeight != PATTERN)
        hr = E_FAIL;
    else {
        for (unsigned y = 0; y < PATTERN; ++y) {
            DWORD *row = (DWORD *)((BYTE *)desc.lpSurface + y * desc.lPitch);
            for (unsigned x = 0; x < PATTERN; ++x) row[x] = pattern(x, y, frame);
        }
    }
    HRESULT unlock = IDirectDrawSurface7_Unlock(source, NULL);
    return FAILED(hr) ? hr : unlock;
}

static HRESULT readback(IDirectDrawSurface7 *source, unsigned frame,
        DWORD background, DWORD *samples, Receipt *receipt)
{
    static const unsigned points[][2] = {
        {8, 8}, {23, 31}, {73, 85}, {135, 135}, /* uploaded pattern */
        {200, 40}, {180, 110},                /* fixed-function triangle */
        {310, 230}, {150, 170}               /* color-filled background */
    };
    DDSURFACEDESC2 desc = {0};
    desc.dwSize = sizeof(desc);
    HRESULT hr = IDirectDrawSurface7_Lock(source, NULL, &desc,
            DDLOCK_WAIT | DDLOCK_READONLY, NULL);
    if (FAILED(hr)) return hr;
    if (!rgb32(&desc) || desc.dwWidth != WIDTH || desc.dwHeight != HEIGHT)
        hr = E_FAIL;
    else {
        for (unsigned i = 0; i < sizeof(points) / sizeof(points[0]); ++i) {
            unsigned x = points[i][0], y = points[i][1];
            DWORD actual = *(DWORD *)((BYTE *)desc.lpSurface + y * desc.lPitch + x * 4);
            DWORD expected = i < 4 ? pattern(x - 8, y - 8, frame)
                : i < 6 ? 0x40e080 : background;
            if ((actual & 0xffffff) != expected) {
                receipt->mismatch_sample = i;
                receipt->expected_rgb = expected;
                receipt->actual_rgb = actual & 0xffffff;
                hr = E_FAIL; break;
            }
            ++*samples;
        }
    }
    HRESULT unlock = IDirectDrawSurface7_Unlock(source, NULL);
    return FAILED(hr) ? hr : unlock;
}

static void report(const Receipt *r)
{
    printf("{\"format\":1,\"bits\":32,\"passed\":%s,\"stage\":\"%s\","
        "\"hresult\":%lu,\"adapter_vendor_id\":%lu,\"adapter_device_id\":%lu,"
        "\"frames\":%lu,\"expected_frames\":24,\"colorfills\":%lu,\"uploads\":%lu,"
        "\"blits\":%lu,\"ffp_frames\":%lu,\"presents\":%lu,"
        "\"readback_samples\":%lu,\"presentation_samples\":%lu,"
        "\"mismatch_sample\":%lu,\"expected_rgb\":%lu,\"actual_rgb\":%lu,"
        "\"elapsed_ms\":%llu,\"render_ms\":%llu,\"first_frame_ms\":%llu,\"max_frame_ms\":%llu}\n",
        SUCCEEDED(r->hr) && r->frames == FRAMES ? "true" : "false", r->stage,
        (unsigned long)(DWORD)r->hr, (unsigned long)r->vendor, (unsigned long)r->device,
        (unsigned long)r->frames, (unsigned long)r->colorfills, (unsigned long)r->uploads,
        (unsigned long)r->blits, (unsigned long)r->ffp_frames, (unsigned long)r->presents,
        (unsigned long)r->readback_samples, (unsigned long)r->presentation_samples,
        (unsigned long)r->mismatch_sample, (unsigned long)r->expected_rgb, (unsigned long)r->actual_rgb,
        (unsigned long long)r->elapsed_ms, (unsigned long long)r->render_ms,
        (unsigned long long)r->first_frame_ms, (unsigned long long)r->max_frame_ms);
    fflush(stdout);
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, LPWSTR args, int show)
{
    (void)previous; (void)show;
    Receipt r = { .stage = "arguments", .hr = E_INVALIDARG, .mismatch_sample = 0xffffffff };
    ULONGLONG started = GetTickCount64(), render_started = 0;
    HWND window = NULL;
    IDirectDraw7 *ddraw = NULL;
    IDirectDrawSurface7 *primary = NULL, *source = NULL, *target = NULL, *copy = NULL;
    IDirectDrawClipper *clipper = NULL;
    IDirect3D7 *d3d = NULL;
    IDirect3DDevice7 *device = NULL;
    DDDEVICEIDENTIFIER2 identifier = {0};
    if (*args) goto done;

#define CHECK(stage_name, call) do { r.stage = stage_name; r.hr = (call); if (FAILED(r.hr)) goto done; } while (0)
    r.stage = "window";
    window = CreateWindowW(L"STATIC", L"LSB DirectDraw check", WS_POPUP | WS_VISIBLE,
            64, 64, WIDTH, HEIGHT, NULL, NULL, instance, NULL);
    if (!window) { r.hr = HRESULT_FROM_WIN32(GetLastError()); goto done; }
    ShowWindow(window, SW_SHOW);
    UpdateWindow(window);
    CHECK("create", DirectDrawCreateEx(NULL, (void **)&ddraw, &IID_IDirectDraw7, NULL));
    CHECK("cooperative", IDirectDraw7_SetCooperativeLevel(ddraw, window, DDSCL_NORMAL));
    CHECK("identifier", IDirectDraw7_GetDeviceIdentifier(ddraw, &identifier, 0));
    r.vendor = identifier.dwVendorId; r.device = identifier.dwDeviceId;
    CHECK("primary", surface(ddraw, DDSCAPS_PRIMARYSURFACE, 0, 0, &primary));
    CHECK("clipper", IDirectDraw7_CreateClipper(ddraw, 0, &clipper, NULL));
    CHECK("clipper", IDirectDrawClipper_SetHWnd(clipper, 0, window));
    CHECK("clipper", IDirectDrawSurface7_SetClipper(primary, clipper));
    CHECK("offscreen", surface(ddraw, DDSCAPS_OFFSCREENPLAIN | DDSCAPS_SYSTEMMEMORY,
            PATTERN, PATTERN, &source));
    CHECK("offscreen", surface(ddraw, DDSCAPS_OFFSCREENPLAIN | DDSCAPS_VIDEOMEMORY
            | DDSCAPS_3DDEVICE, WIDTH, HEIGHT, &target));
    CHECK("offscreen", surface(ddraw, DDSCAPS_OFFSCREENPLAIN | DDSCAPS_SYSTEMMEMORY,
            WIDTH, HEIGHT, &copy));
    CHECK("device", IDirectDraw7_QueryInterface(ddraw, &IID_IDirect3D7, (void **)&d3d));
    CHECK("device", IDirect3D7_CreateDevice(d3d, &IID_IDirect3DHALDevice, target, &device));
    D3DVIEWPORT7 viewport = {0, 0, WIDTH, HEIGHT, 0.0f, 1.0f};
    CHECK("state", IDirect3DDevice7_SetViewport(device, &viewport));
    CHECK("state", IDirect3DDevice7_SetRenderState(device, D3DRENDERSTATE_LIGHTING, FALSE));
    CHECK("state", IDirect3DDevice7_SetRenderState(device, D3DRENDERSTATE_ZENABLE, FALSE));
    CHECK("state", IDirect3DDevice7_SetRenderState(device, D3DRENDERSTATE_CULLMODE, D3DCULL_NONE));
    CHECK("state", IDirect3DDevice7_SetRenderState(device, D3DRENDERSTATE_DITHERENABLE, FALSE));
    CHECK("state", IDirect3DDevice7_SetRenderState(device, D3DRENDERSTATE_ALPHABLENDENABLE, FALSE));
    CHECK("state", IDirect3DDevice7_SetTexture(device, 0, NULL));
    CHECK("state", IDirect3DDevice7_SetTextureStageState(device, 0, D3DTSS_COLOROP, D3DTOP_SELECTARG1));
    CHECK("state", IDirect3DDevice7_SetTextureStageState(device, 0, D3DTSS_COLORARG1, D3DTA_DIFFUSE));
    CHECK("state", IDirect3DDevice7_SetTextureStageState(device, 0, D3DTSS_ALPHAOP, D3DTOP_SELECTARG1));
    CHECK("state", IDirect3DDevice7_SetTextureStageState(device, 0, D3DTSS_ALPHAARG1, D3DTA_DIFFUSE));

    render_started = GetTickCount64();
    for (unsigned frame = 0; frame < FRAMES; ++frame) {
        ULONGLONG frame_started = GetTickCount64();
        if (frame_started - started >= 20000) {
            r.stage = "budget"; r.hr = HRESULT_FROM_WIN32(ERROR_TIMEOUT); goto done;
        }
        MSG message;
        while (PeekMessageW(&message, NULL, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message); DispatchMessageW(&message);
        }
        DWORD background = 0x182838 + frame;
        DDBLTFX fill = {0}; fill.dwSize = sizeof(fill); fill.dwFillColor = background;
        CHECK("colorfill", IDirectDrawSurface7_Blt(target, NULL, NULL, NULL,
                DDBLT_COLORFILL | DDBLT_WAIT, &fill));
        ++r.colorfills;
        CHECK("upload", upload(source, frame)); ++r.uploads;
        RECT pattern_rect = {8, 8, PATTERN + 8, PATTERN + 8};
        CHECK("blit", IDirectDrawSurface7_Blt(target, &pattern_rect, source, NULL, DDBLT_WAIT, NULL));
        ++r.blits;
        struct Vertex { float x, y, z, rhw; DWORD color; } vertices[] = {
            {170.0f, 20.0f, 0.5f, 1.0f, 0xff40e080},
            {300.0f, 20.0f, 0.5f, 1.0f, 0xff40e080},
            {170.0f, 150.0f, 0.5f, 1.0f, 0xff40e080}
        };
        CHECK("draw", IDirect3DDevice7_BeginScene(device));
        HRESULT draw = IDirect3DDevice7_DrawPrimitive(device, D3DPT_TRIANGLELIST,
                D3DFVF_XYZRHW | D3DFVF_DIFFUSE, vertices, 3, 0);
        HRESULT end = IDirect3DDevice7_EndScene(device);
        CHECK("draw", FAILED(draw) ? draw : end); ++r.ffp_frames;
        CHECK("readback", readback(target, frame, background, &r.readback_samples, &r));
        POINT origin = {0, 0};
        if (!ClientToScreen(window, &origin)) {
            r.stage = "present"; r.hr = HRESULT_FROM_WIN32(GetLastError()); goto done;
        }
        RECT destination = {origin.x, origin.y, origin.x + WIDTH, origin.y + HEIGHT};
        CHECK("present", IDirectDrawSurface7_Blt(primary, &destination, target, NULL, DDBLT_WAIT, NULL));
        ++r.presents;
        CHECK("present_readback", IDirectDrawSurface7_Blt(copy, NULL, primary,
                &destination, DDBLT_WAIT, NULL));
        CHECK("present_readback", readback(copy, frame, background, &r.presentation_samples, &r));
        ULONGLONG quantum = GetTickCount64() - frame_started;
        if (!frame) r.first_frame_ms = quantum;
        if (quantum > r.max_frame_ms) r.max_frame_ms = quantum;
        ++r.frames;
    }
    r.stage = "completed"; r.hr = S_OK;
done:
    if (render_started) r.render_ms = GetTickCount64() - render_started;
    if (device) IDirect3DDevice7_Release(device);
    if (d3d) IDirect3D7_Release(d3d);
    if (copy) IDirectDrawSurface7_Release(copy);
    if (target) IDirectDrawSurface7_Release(target);
    if (source) IDirectDrawSurface7_Release(source);
    if (primary) IDirectDrawSurface7_Release(primary);
    if (clipper) IDirectDrawClipper_Release(clipper);
    if (ddraw) IDirectDraw7_Release(ddraw);
    if (window) DestroyWindow(window);
    r.elapsed_ms = GetTickCount64() - started;
    report(&r);
    return SUCCEEDED(r.hr) && r.frames == FRAMES ? 0 : 1;
}
