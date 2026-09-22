/* App-owned, finite DXVK D3D8->D3D9 device/draw/presentation compatibility check.
 * Runs before the login process; never opens client files or submits credentials. */
#define COBJMACROS
#include <windows.h>
#include <d3d8.h>
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,LPWSTR args,int show){
    (void)previous;(void)args;(void)show;
    HWND window=CreateWindowW(L"STATIC",L"Graphics compatibility check",WS_POPUP|WS_VISIBLE,0,0,320,240,NULL,NULL,instance,NULL);
    if(!window)return 10;
    IDirect3D8 *api=Direct3DCreate8(D3D_SDK_VERSION);if(!api){DestroyWindow(window);return 11;}
    D3DPRESENT_PARAMETERS p={0};p.Windowed=TRUE;p.SwapEffect=D3DSWAPEFFECT_DISCARD;p.BackBufferWidth=320;p.BackBufferHeight=240;p.hDeviceWindow=window;
    IDirect3DDevice8 *device=NULL;
    HRESULT hr=IDirect3D8_CreateDevice(api,0,D3DDEVTYPE_HAL,window,D3DCREATE_SOFTWARE_VERTEXPROCESSING,&p,&device);
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
        if(SUCCEEDED(hr))hr=IDirect3DDevice8_Present(device,NULL,NULL,NULL,NULL);
        MSG message;while(PeekMessageW(&message,NULL,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}
    }
    if(device)IDirect3DDevice8_Release(device);IDirect3D8_Release(api);DestroyWindow(window);
    return FAILED(hr)?12:0;
}
