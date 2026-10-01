/* Finite D3D8 compatibility check using only app-owned assets. */
#define COBJMACROS
#include <windows.h>
#include <d3d8.h>
#include <wchar.h>
#include "graphics-pixels.h"
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,LPWSTR args,int show){
    (void)previous;(void)show;
    HWND window=CreateWindowW(L"STATIC",L"Graphics compatibility check",WS_POPUP|WS_VISIBLE,0,0,320,240,NULL,NULL,instance,NULL);
    if(!window)return 10;
    IDirect3D8 *api=Direct3DCreate8(D3D_SDK_VERSION);if(!api){DestroyWindow(window);return 11;}
    D3DPRESENT_PARAMETERS p={0};p.Windowed=TRUE;p.SwapEffect=D3DSWAPEFFECT_DISCARD;p.BackBufferWidth=320;p.BackBufferHeight=240;p.hDeviceWindow=window;
    IDirect3DDevice8 *device=NULL;
    graphics_stage("warmup","device",0,"before");
    HRESULT hr=IDirect3D8_CreateDevice(api,0,D3DDEVTYPE_HAL,window,D3DCREATE_SOFTWARE_VERTEXPROCESSING,&p,&device);
    graphics_stage("warmup","device",0,"after");
    for(int i=0;SUCCEEDED(hr)&&i<8;i++){
        struct {float x,y,z,rhw;DWORD color;} v[]={{160,10,.5f,1,0xffff0000},{310,230,.5f,1,0xff00ff00},{10,230,.5f,1,0xff0000ff}};
        hr=IDirect3DDevice8_Clear(device,0,NULL,D3DCLEAR_TARGET,0xff203040,1,0);
        if(SUCCEEDED(hr))hr=IDirect3DDevice8_BeginScene(device);
        if(SUCCEEDED(hr)){
            IDirect3DDevice8_SetRenderState(device,D3DRS_CULLMODE,D3DCULL_NONE);
            IDirect3DDevice8_SetVertexShader(device,D3DFVF_XYZRHW|D3DFVF_DIFFUSE);
            hr=IDirect3DDevice8_DrawPrimitiveUP(device,D3DPT_TRIANGLELIST,1,v,sizeof(v[0]));
            IDirect3DDevice8_EndScene(device);
        }
        if(SUCCEEDED(hr)){
            graphics_stage("warmup","present",i,"before");
            hr=IDirect3DDevice8_Present(device,NULL,NULL,NULL,NULL);
            graphics_stage("warmup","present",i,"after");
        }
        MSG message;while(PeekMessageW(&message,NULL,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}
    }
    if(device){graphics_stage("warmup","release_device",0,"before");IDirect3DDevice8_Release(device);graphics_stage("warmup","release_device",0,"after");}
    int result=FAILED(hr)?12:0;
    if(!result && (!wcscmp(args,L"--pixels")||!wcscmp(args,L"--trace-pixels"))) {
        if(!wcscmp(args,L"--trace-pixels")){
            HMODULE trace=LoadLibraryW(L"P:\\startup-trace.dll");
            typedef void (WINAPI *Attach)(IDirect3D8*);
            Attach attach=trace?(Attach)(void*)GetProcAddress(trace,"LsbGraphicsTrace"):NULL;
            if(!attach){IDirect3D8_Release(api);DestroyWindow(window);return 14;}
            attach(api); /* Keep DLL loaded until process exit; hooks may be active. */
        }
        result=check_pixels(api,window,D3DCREATE_SOFTWARE_VERTEXPROCESSING,"swvp");
        if(!result)result=check_pixels(api,window,D3DCREATE_HARDWARE_VERTEXPROCESSING,"hwvp");
    }
    graphics_stage("final","release_api",0,"before");IDirect3D8_Release(api);graphics_stage("final","release_api",0,"after");
    graphics_stage("final","destroy_window",0,"before");DestroyWindow(window);graphics_stage("final","destroy_window",0,"after");return result;
}
