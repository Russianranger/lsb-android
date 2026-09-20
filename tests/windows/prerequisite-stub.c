#include <windows.h>
int WINAPI wWinMain(HINSTANCE h,HINSTANCE prev,LPWSTR cmd,int show){
    (void)h;(void)prev;(void)cmd;(void)show;
    DeleteFileW(L"D:\\fail-com");
    return 3010; /* Real Windows reboot-required exit, not a truncated Unix status. */
}
