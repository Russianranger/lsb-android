#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <wchar.h>

/* Negative controls: prove the lifetime fixture catches both FreeLibrary and
 * ordinary process exit, the two cleanup paths deliberately avoided by check. */
int wmain(int argc,WCHAR **argv){
    if(argc!=3)return 80;
    HMODULE dll=LoadLibraryW(argv[2]);if(!dll)return 81;
    if(!wcscmp(argv[1],L"unload"))FreeLibrary(dll);
    return 0;
}
