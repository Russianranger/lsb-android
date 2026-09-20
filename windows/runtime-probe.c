#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <d3d8.h>
#include <mmsystem.h>
#include <stdio.h>
#include <math.h>
#include <string.h>

static const CLSID probe = {0x97191a4b,0xb2c7,0x47b1,{0x95,0x0d,0x10,0x57,0xa8,0xb5,0x1f,0x01}};
static IDirect3D8 *api;
static IDirect3DDevice8 *device;
static HWND main_window,canvas;
static DWORD frames,keys,clicks;
static BOOL registry_ok,com_ok,tone_ok,autotest;
static HRESULT last_error;
static char sound[44+48000];
static WCHAR dll_path[MAX_PATH];
static void report(void){
    FILE *f=_wfopen(L"Z:\\session\\probe.new",L"wb");if(!f)return;
    fprintf(f,"{\"format\":1,\"bits\":%d,\"registry32\":%s,\"com\":%s,\"d3d8_frames\":%lu,\"key_events\":%lu,\"pointer_events\":%lu,\"audio_submitted\":%s,\"hresult\":%lu}\n",
        (int)(8*sizeof(void*)),registry_ok?"true":"false",com_ok?"true":"false",(unsigned long)frames,(unsigned long)keys,(unsigned long)clicks,tone_ok?"true":"false",(unsigned long)last_error);
    fclose(f);MoveFileExW(L"Z:\\session\\probe.new",L"Z:\\session\\probe.json",MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
}
static void tone(void){
    memcpy(sound,"RIFF",4);DWORD size=sizeof(sound)-8;memcpy(sound+4,&size,4);memcpy(sound+8,"WAVEfmt ",8);
    size=16;memcpy(sound+16,&size,4);WORD one=1,two=2,bits=16;DWORD rate=48000,bytes=96000;
    memcpy(sound+20,&one,2);memcpy(sound+22,&one,2);memcpy(sound+24,&rate,4);memcpy(sound+28,&bytes,4);memcpy(sound+32,&two,2);memcpy(sound+34,&bits,2);
    memcpy(sound+36,"data",4);size=48000;memcpy(sound+40,&size,4);
    for(int i=0;i<24000;i++){short v=(short)(sin(i*6.28318530718*440/48000)*6000);memcpy(sound+44+i*2,&v,2);}
    tone_ok=PlaySoundA(sound,NULL,SND_MEMORY|SND_ASYNC|SND_NODEFAULT);report();
}
static BOOL registration(void){
    HKEY key;DWORD expected=0x4c534231,actual=0,size=4,type=0;
    LONG e=RegCreateKeyExW(HKEY_LOCAL_MACHINE,L"Software\\LSBOpenProbe",0,NULL,0,KEY_READ|KEY_WRITE|KEY_WOW64_32KEY,NULL,&key,NULL);
    if(e)return FALSE;
    e=RegSetValueExW(key,L"RoundTrip",0,REG_DWORD,(BYTE*)&expected,4);
    if(!e)e=RegQueryValueExW(key,L"RoundTrip",NULL,&type,(BYTE*)&actual,&size);RegCloseKey(key);
    registry_ok=!e&&type==REG_DWORD&&size==4&&actual==expected;
    HMODULE dll=LoadLibraryW(L"P:\\probe-com.dll");if(!dll)return FALSE;
    HRESULT (WINAPI *reg)(void)=(void*)GetProcAddress(dll,"DllRegisterServer");if(!reg)return FALSE;
    last_error=reg();if(FAILED(last_error))return FALSE;
    IUnknown *instance=NULL;last_error=CoCreateInstance(&probe,NULL,CLSCTX_INPROC_SERVER,&IID_IUnknown,(void**)&instance);
    com_ok=SUCCEEDED(last_error)&&instance!=NULL;if(instance)IUnknown_Release(instance);return registry_ok&&com_ok;
}
static BOOL graphics(void){
    api=Direct3DCreate8(D3D_SDK_VERSION);if(!api){last_error=E_FAIL;return FALSE;}
    D3DPRESENT_PARAMETERS p={0};p.Windowed=TRUE;p.SwapEffect=D3DSWAPEFFECT_DISCARD;p.BackBufferWidth=1200;p.BackBufferHeight=470;p.BackBufferFormat=D3DFMT_UNKNOWN;p.hDeviceWindow=canvas;
    last_error=IDirect3D8_CreateDevice(api,D3DADAPTER_DEFAULT,D3DDEVTYPE_HAL,canvas,D3DCREATE_SOFTWARE_VERTEXPROCESSING,&p,&device);
    GetModuleFileNameW(GetModuleHandleW(L"d3d8.dll"),dll_path,MAX_PATH);return SUCCEEDED(last_error);
}
static void draw(void){
    if(!device)return;
    struct Vertex{float x,y,z,rhw;DWORD color;} v[]={{600,40,0.5f,1,0xffff4040},{1050,420,0.5f,1,0xff40ff40},{150,420,0.5f,1,0xff4080ff}};
    HRESULT hr=IDirect3DDevice8_Clear(device,0,NULL,D3DCLEAR_TARGET,D3DCOLOR_XRGB(24,48,72),1,0);
    if(SUCCEEDED(hr))hr=IDirect3DDevice8_BeginScene(device);
    if(SUCCEEDED(hr)){
        IDirect3DDevice8_SetRenderState(device,D3DRS_CULLMODE,D3DCULL_NONE);
        IDirect3DDevice8_SetVertexShader(device,D3DFVF_XYZRHW|D3DFVF_DIFFUSE);
        hr=IDirect3DDevice8_DrawPrimitiveUP(device,D3DPT_TRIANGLELIST,1,v,sizeof(v[0]));
        IDirect3DDevice8_EndScene(device);
    }
    if(SUCCEEDED(hr))hr=IDirect3DDevice8_Present(device,NULL,NULL,NULL,NULL);
    if(FAILED(hr)){last_error=hr;report();return;}frames++;
    if(frames%25==0){report();InvalidateRect(main_window,NULL,FALSE);}
    if(autotest&&frames>=300)PostMessageW(main_window,WM_CLOSE,0,0);
}
static LRESULT CALLBACK window_proc(HWND window,UINT msg,WPARAM w,LPARAM l){
    switch(msg){
    case WM_TIMER:draw();return 0;
    case WM_KEYDOWN:keys++;report();InvalidateRect(window,NULL,FALSE);return 0;
    case WM_LBUTTONDOWN:clicks++;SetFocus(window);report();InvalidateRect(window,NULL,FALSE);return 0;
    case WM_COMMAND:if(LOWORD(w)==1){clicks++;tone();SetFocus(window);}if(LOWORD(w)==2)DestroyWindow(window);return 0;
    case WM_PAINT:{PAINTSTRUCT ps;HDC dc=BeginPaint(window,&ps);WCHAR text[640];
        swprintf(text,640,L"LSB runtime probe - 32-bit Windows\nRegistry: %ls    COM: %ls    D3D8 frames: %lu    Keys: %lu    Clicks: %lu\n%ls",
            registry_ok?L"PASS":L"FAILED",com_ok?L"PASS":L"FAILED",frames,keys,clicks,dll_path);
        RECT top={32,12,1240,92};SetBkMode(dc,TRANSPARENT);DrawTextW(dc,text,-1,&top,DT_LEFT);
        RECT bottom={32,575,1240,620};DrawTextW(dc,L"Tap outside the triangle, send a key, and play a tone. Then exit and relaunch.",-1,&bottom,DT_LEFT);EndPaint(window,&ps);return 0;}
    case WM_CLOSE:DestroyWindow(window);return 0;
    case WM_DESTROY:KillTimer(window,1);report();PostQuitMessage(0);return 0;
    }return DefWindowProcW(window,msg,w,l);
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,LPWSTR args,int show){
    (void)previous;autotest=wcsstr(args,L"--autotest")!=NULL;CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    BOOL registered=registration();report();if(!registered)return 10;
    WNDCLASSW wc={0};wc.lpfnWndProc=window_proc;wc.hInstance=instance;wc.lpszClassName=L"LSBOpenProbe";wc.hCursor=LoadCursor(NULL,IDC_ARROW);wc.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);RegisterClassW(&wc);
    main_window=CreateWindowW(wc.lpszClassName,L"LSB runtime probe",WS_POPUP|WS_VISIBLE,0,0,1280,720,NULL,NULL,instance,NULL);
    canvas=CreateWindowW(L"STATIC",L"",WS_CHILD|WS_VISIBLE,40,100,1200,470,main_window,NULL,instance,NULL);
    CreateWindowW(L"BUTTON",L"Play tone",WS_CHILD|WS_VISIBLE,32,635,190,48,main_window,(HMENU)1,instance,NULL);
    CreateWindowW(L"BUTTON",L"Exit probe",WS_CHILD|WS_VISIBLE,240,635,190,48,main_window,(HMENU)2,instance,NULL);
    ShowWindow(main_window,show);SetForegroundWindow(main_window);SetFocus(main_window);
    if(!graphics()){report();DestroyWindow(main_window);return 20;}tone();SetTimer(main_window,1,40,NULL);
    MSG msg;while(GetMessageW(&msg,NULL,0,0)>0){TranslateMessage(&msg);DispatchMessageW(&msg);}
    PlaySoundA(NULL,NULL,0);if(device)IDirect3DDevice8_Release(device);if(api)IDirect3D8_Release(api);CoUninitialize();return FAILED(last_error)?21:0;
}
