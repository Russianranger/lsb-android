#define COBJMACROS
#define DIRECTINPUT_VERSION 0x0800
#include <windows.h>
#include <dinput.h>
#include <stdio.h>
#include <string.h>
static IDirectInput8A *input;
static IDirectInputDevice8A *device;
static BOOL CALLBACK found(const DIDEVICEINSTANCEA *info,void *unused){
    (void)unused;
    if(!strstr(info->tszProductName,"Virtual"))return DIENUM_CONTINUE;
    printf("Controller: %s\n",info->tszProductName);
    return SUCCEEDED(IDirectInput8_CreateDevice(input,&info->guidInstance,&device,NULL))?DIENUM_STOP:DIENUM_CONTINUE;
}
static void stage(int n){FILE *f=fopen("Z:\\session\\gamepad-stage","w");if(f){fprintf(f,"%d",n);fclose(f);}}
int wmain(void){
    if(FAILED(DirectInput8Create(GetModuleHandle(NULL),DIRECTINPUT_VERSION,&IID_IDirectInput8A,(void**)&input,NULL)))return 1;
    for(int i=0;i<160&&!device;i++){IDirectInput8_EnumDevices(input,DI8DEVCLASS_GAMECTRL,found,NULL,DIEDFL_ATTACHEDONLY);Sleep(250);}
    if(!device){puts("FAIL: virtual DirectInput joystick was not enumerated");return 2;}
    DIDEVCAPS caps={0};caps.dwSize=sizeof(caps);IDirectInputDevice8_GetCapabilities(device,&caps);
    printf("Capabilities: axes=%lu buttons=%lu hats=%lu\n",caps.dwAxes,caps.dwButtons,caps.dwPOVs);
    if(caps.dwAxes<4||caps.dwButtons<16||caps.dwPOVs<1)return 3;
    if(FAILED(IDirectInputDevice8_SetDataFormat(device,&c_dfDIJoystick2)))return 4;
    if(FAILED(IDirectInputDevice8_SetCooperativeLevel(device,GetDesktopWindow(),DISCL_BACKGROUND|DISCL_NONEXCLUSIVE)))return 5;
    IDirectInputDevice8_Acquire(device);
    for(int step=0;step<4;step++){
        stage(step);BOOL ok=FALSE;
        DIJOYSTATE2 last={0};HRESULT last_hr=E_FAIL;
        for(int i=0;i<200;i++){
            DIJOYSTATE2 s={0};IDirectInputDevice8_Poll(device);
            HRESULT hr=IDirectInputDevice8_GetDeviceState(device,sizeof(s),&s);
            last=s;last_hr=hr;
            if(SUCCEEDED(hr)){
                if(step==0||step==2)ok=(s.rgbButtons[0]&128)&&s.lX>45000&&s.rgdwPOV[0]==4500;
                else ok=!(s.rgbButtons[0]&128)&&s.lX>=32000&&s.lX<=33500&&s.rgdwPOV[0]==0xffffffff;
                if(ok)break;
            }else IDirectInputDevice8_Acquire(device);
            Sleep(50);
        }
        if(!ok){printf("FAIL: gamepad stage %d hr=%08lx button0=%u x=%ld y=%ld z=%ld rx=%ld hat=%lu\n",step,(unsigned long)last_hr,last.rgbButtons[0],last.lX,last.lY,last.lZ,last.lRx,last.rgdwPOV[0]);return 10+step;}
        printf("PASS: gamepad stage %d button0=%u x=%ld hat=%lu\n",step,last.rgbButtons[0],last.lX,last.rgdwPOV[0]);
    }
    IDirectInputDevice8_Unacquire(device);IDirectInputDevice8_Release(device);IDirectInput8_Release(input);
    puts("PASS: virtual DirectInput joystick axes, buttons, hat, release and stale-input neutralization");return 0;
}
