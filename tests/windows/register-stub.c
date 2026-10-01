#ifndef UNICODE
#define UNICODE
#endif
#include <windows.h>
#include <stdio.h>
static HMODULE module;
BOOL WINAPI DllMain(HINSTANCE h, DWORD why, LPVOID reserved) { (void)reserved; if (why == DLL_PROCESS_ATTACH) module = h; return TRUE; }
__declspec(dllexport) HRESULT WINAPI DllRegisterServer(void) {
    wchar_t path[2048], fail[128] = {0}; GetModuleFileNameW(module, path, 2000);
    const wchar_t *name = wcsrchr(path, L'\\'); name = name ? name + 1 : path;
    GetEnvironmentVariableW(L"LSB_TEST_FAIL", fail, 128);
    if (!_wcsicmp(name, fail)) return E_FAIL;
    wcscat(path, L".registered"); FILE *f = _wfopen(path, L"wb"); if (!f) return E_ACCESSDENIED;
    fputs("registered", f); fclose(f); return S_OK;
}
