#ifndef UNICODE
#define UNICODE
#endif
#include <windows.h>
#include <stdio.h>
int WINAPI wWinMain(HINSTANCE a, HINSTANCE b, PWSTR c, int d) {
    (void)a; (void)b; (void)c; (void)d;
    wchar_t cwd[2048], code[16] = {0}; GetCurrentDirectoryW(2048, cwd);
    FILE *f = _wfopen(L"launch-observed.txt", L"wb"); if (!f) return 99;
    const wchar_t *command = GetCommandLineW();
    fwrite(cwd, 2, wcslen(cwd), f); fwrite(L"\n", 2, 1, f); fwrite(command, 2, wcslen(command), f); fclose(f);
    GetEnvironmentVariableW(L"LSB_TEST_EXIT", code, 16); return _wtoi(code);
}
