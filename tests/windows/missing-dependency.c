#include <windows.h>
__declspec(dllexport) int missing_fixture(void){return 1;}
BOOL WINAPI DllMain(HINSTANCE h,DWORD why,LPVOID p){(void)h;(void)why;(void)p;return TRUE;}
