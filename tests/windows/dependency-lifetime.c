#include <windows.h>

/* Deterministic stand-in for a DLL whose unload/exit callback cannot run safely.
 * No client files are involved. A separate variant fails during initialization. */
BOOL WINAPI DllMain(HINSTANCE instance,DWORD reason,LPVOID reserved){
    (void)instance;(void)reserved;
#ifdef FAIL_ATTACH
    if(reason==DLL_PROCESS_ATTACH){TerminateProcess(GetCurrentProcess(),0xc0000005);return FALSE;}
#else
    if(reason==DLL_PROCESS_DETACH){
        HANDLE f=CreateFileW(L"Z:\\session\\dependency-detached",GENERIC_WRITE,0,NULL,CREATE_ALWAYS,0,NULL);
        if(f!=INVALID_HANDLE_VALUE)CloseHandle(f);
        TerminateProcess(GetCurrentProcess(),0xc0000005);
    }
#endif
    return TRUE;
}
