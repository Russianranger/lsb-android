#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#define _WIN32_WINNT 0x0601
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <wchar.h>
#include <wctype.h>

#define CAP 2048
#define DONE (WM_APP + 1)
static wchar_t self[CAP], base[CAP], ini[CAP], logfile[CAP];
static wchar_t pol[CAP], game[CAP], core[CAP], loader[CAP], host[256], region[8], branch[128];
static HWND window, output, buttons[3];
static BOOL busy;
static int operation;

static void log_line(const wchar_t *format, ...) {
    wchar_t line[4096]; char utf8[16384]; va_list args;
    va_start(args, format); _vsnwprintf(line, 4094, format, args); va_end(args); line[4093] = 0;
    wcscat(line, L"\r\n");
    int n = WideCharToMultiByte(CP_UTF8, 0, line, -1, utf8, sizeof(utf8), NULL, NULL);
    HANDLE f = CreateFileW(logfile, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS, 0, NULL);
    if (f != INVALID_HANDLE_VALUE) { DWORD wrote; if (n > 0) WriteFile(f, utf8, n - 1, &wrote, NULL); CloseHandle(f); }
}
static BOOL exists(const wchar_t *p) { DWORD a = GetFileAttributesW(p); return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY); }
static void parent_of(wchar_t *p) { wchar_t *slash = wcsrchr(p, L'\\'); if (slash) *slash = 0; }
static BOOL file_in(wchar_t *dest, const wchar_t *dir, const wchar_t *name) {
    if (wcslen(dir) + wcslen(name) + 2 >= CAP) return FALSE;
    swprintf(dest, CAP, L"%ls\\%ls", dir, name); return TRUE;
}
static BOOL is_x86(const wchar_t *path) {
    FILE *f = _wfopen(path, L"rb"); if (!f) return FALSE;
    IMAGE_DOS_HEADER dos; DWORD signature; IMAGE_FILE_HEADER pe; WORD magic;
    BOOL ok = fread(&dos, sizeof(dos), 1, f) == 1 && dos.e_magic == IMAGE_DOS_SIGNATURE && dos.e_lfanew >= 64 &&
        fseek(f, dos.e_lfanew, SEEK_SET) == 0 && fread(&signature, 4, 1, f) == 1 && signature == IMAGE_NT_SIGNATURE &&
        fread(&pe, sizeof(pe), 1, f) == 1 && pe.Machine == IMAGE_FILE_MACHINE_I386 && fread(&magic, 2, 1, f) == 1 && magic == IMAGE_NT_OPTIONAL_HDR32_MAGIC;
    fclose(f); return ok;
}
static BOOL read_path(const wchar_t *key, wchar_t *dest, BOOL optional) {
    wchar_t relative[CAP], joined[CAP];
    DWORD n = GetPrivateProfileStringW(L"lsb", key, L"", relative, CAP, ini);
    if (!n) { dest[0] = 0; return optional; }
    if (n >= CAP - 1 || (wcsncmp(relative, L"client\\", 7) && wcscmp(relative, L"client"))) return FALSE;
    if (wcschr(relative, L':') || wcschr(relative, L'"') || wcschr(relative, L'/')) return FALSE;
    wchar_t parts[CAP]; wcscpy(parts, relative);
    for (wchar_t *p = wcstok(parts, L"\\"); p; p = wcstok(NULL, L"\\")) {
        size_t len = wcslen(p);
        if (!wcscmp(p, L".") || !wcscmp(p, L"..") || !_wcsicmp(p, L"patchfiles") || p[len - 1] == L'.' || p[len - 1] == L' ') return FALSE;
    }
    if (!file_in(joined, base, relative)) return FALSE;
    DWORD full = GetFullPathNameW(joined, CAP, dest, NULL);
    return full > 0 && full < CAP && !_wcsnicmp(dest, base, wcslen(base)) && dest[wcslen(base)] == L'\\';
}
static BOOL load_config(void) {
    if (GetPrivateProfileIntW(L"lsb", L"format", 0, ini) != 1) { log_line(L"Missing/unsupported lsb-launcher.ini. Extract the launcher beside the exported client folder."); return FALSE; }
    if (!read_path(L"pol", pol, FALSE) || !read_path(L"game", game, FALSE) || !read_path(L"core", core, FALSE) || !read_path(L"loader", loader, TRUE)) { log_line(L"Invalid client paths in lsb-launcher.ini. Export a fresh launcher update from Android."); return FALSE; }
    GetPrivateProfileStringW(L"lsb", L"host", L"", host, 256, ini); GetPrivateProfileStringW(L"lsb", L"region", L"", region, 8, ini);
    size_t len = wcslen(host);
    if (!len || len > 253 || !iswalnum(host[0]) || !iswalnum(host[len - 1])) return FALSE;
    for (size_t i = 0; i < len; i++) if (!((host[i] >= L'a' && host[i] <= L'z') || (host[i] >= L'A' && host[i] <= L'Z') || (host[i] >= L'0' && host[i] <= L'9') || host[i] == L'.' || host[i] == L'-')) return FALSE;
    if (wcscmp(region, L"US") && wcscmp(region, L"EU") && wcscmp(region, L"JP")) return FALSE;
    const wchar_t *name = wcsrchr(core, L'\\'); name = name ? name + 1 : core;
    if (_wcsicmp(name, !wcscmp(region, L"EU") ? L"polcoreeu.dll" : L"polcore.dll")) return FALSE;
    swprintf(branch, 128, L"SOFTWARE\\PlayOnline%ls", !wcscmp(region, L"JP") ? L"" : region);
    log_line(L"LSB FFXI Launcher 0.1.3 (32-bit)\r\nViewer: %ls\r\nCore: %ls\r\nGame: %ls\r\nServer: %ls / %ls", pol, core, game, host, region);
    return TRUE;
}
static BOOL preflight(void) {
    wchar_t path[CAP];
    if (!file_in(path, pol, L"pol.exe") || !exists(path) || !is_x86(core)) { log_line(L"Missing pol.exe or invalid 32-bit PlayOnline DLL. Check the selected version and extraction folder."); return FALSE; }
    const wchar_t *names[] = { L"FFXi.dll", L"FFXiMain.dll" };
    for (int i = 0; i < 2; i++) if (!file_in(path, game, names[i]) || !is_x86(path)) { log_line(L"Missing or invalid 32-bit %ls.", names[i]); return FALSE; }
    return TRUE;
}
static LONG write_value(const wchar_t *key, const wchar_t *name, DWORD type, const void *data, DWORD bytes) {
    HKEY h; LONG rc = RegCreateKeyExW(HKEY_LOCAL_MACHINE, key, 0, NULL, 0, KEY_SET_VALUE | KEY_WOW64_32KEY, NULL, &h, NULL);
    if (rc == ERROR_SUCCESS) { rc = RegSetValueExW(h, name, 0, type, data, bytes); RegCloseKey(h); }
    if (rc) log_line(L"Registry write failed: %ls / %ls, error %ld. Check prefix access permissions.", key, name, rc);
    return rc;
}
static BOOL snapshot_registry(const wchar_t *install, const wchar_t *settings) {
    wchar_t path[CAP]; if (!file_in(path, base, L"registry-before.txt")) return FALSE;
    // Preserve the first snapshot; this is a diagnostic record, not a full COM rollback.
    HANDLE file = CreateFileW(path, GENERIC_WRITE, FILE_SHARE_READ, NULL, CREATE_NEW, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) return GetLastError() == ERROR_FILE_EXISTS;
    const char *header = "32-bit HKLM values before first repair. COM registrations are not backed up.\r\n";
    DWORD written; WriteFile(file, header, (DWORD)strlen(header), &written, NULL);
    const wchar_t *keys[] = { install, install, settings }, *names[] = { L"1000", L"0001", L"Language" };
    BOOL ok = TRUE;
    for (int i = 0; i < 3; i++) {
        HKEY h; DWORD type = 0, size = 4096; BYTE data[4096];
        LONG rc = RegOpenKeyExW(HKEY_LOCAL_MACHINE, keys[i], 0, KEY_QUERY_VALUE | KEY_WOW64_32KEY, &h);
        if (!rc) { rc = RegQueryValueExW(h, names[i], NULL, &type, data, &size); RegCloseKey(h); }
        char line[16384]; int pos = snprintf(line, sizeof(line), "%ls / %ls: status=%ld type=%lu bytes=%lu ", keys[i], names[i], rc, type, size);
        if (!rc) for (DWORD j = 0; j < size && pos < 15000; j++) pos += snprintf(line + pos, sizeof(line) - pos, "%02x", data[j]);
        if (rc && rc != ERROR_FILE_NOT_FOUND && rc != ERROR_PATH_NOT_FOUND) ok = FALSE;
        line[pos++] = '\r'; line[pos++] = '\n';
        if (!WriteFile(file, line, pos, &written, NULL) || written != (DWORD)pos) ok = FALSE;
    }
    CloseHandle(file); if (!ok) DeleteFileW(path); return ok;
}
static DWORD run_child(const wchar_t *exe, wchar_t *command, const wchar_t *cwd, DWORD flags, DWORD timeout) {
    STARTUPINFOW si = {0}; PROCESS_INFORMATION pi = {0}; si.cb = sizeof(si);
    if (!CreateProcessW(exe, command, NULL, NULL, FALSE, flags, NULL, cwd, &si, &pi)) { DWORD error = GetLastError(); log_line(L"Unable to start %ls: Windows error %lu", exe, error); return error ? error : 1; }
    CloseHandle(pi.hThread);
    DWORD result = WaitForSingleObject(pi.hProcess, timeout), code = 1;
    if (result == WAIT_OBJECT_0) GetExitCodeProcess(pi.hProcess, &code);
    else if (result == WAIT_TIMEOUT) { log_line(L"Registration timed out; stopping the registration worker."); TerminateProcess(pi.hProcess, ERROR_TIMEOUT); WaitForSingleObject(pi.hProcess, 5000); code = ERROR_TIMEOUT; }
    CloseHandle(pi.hProcess); return code;
}
static DWORD register_dll(const wchar_t *path) {
    wchar_t cwd[CAP]; if (wcslen(path) >= CAP) return 1; wcscpy(cwd, path); parent_of(cwd);
    SetCurrentDirectoryW(cwd); HRESULT init = CoInitialize(NULL);
    log_line(L"Registering %ls", path);
    HMODULE dll = LoadLibraryExW(path, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!dll) { DWORD error = GetLastError(); log_line(L"DLL load failed: error %lu (126 often means a missing dependency; 193 means an incompatible executable).", error); if (SUCCEEDED(init)) CoUninitialize(); return error ? error : 1; }
    typedef HRESULT (STDAPICALLTYPE *RegisterServer)(void);
    RegisterServer fn = (RegisterServer)(void *)GetProcAddress(dll, "DllRegisterServer");
    HRESULT hr = fn ? fn() : HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND);
    log_line(L"DllRegisterServer returned 0x%08lx", (unsigned long)hr);
    FreeLibrary(dll); if (SUCCEEDED(init)) CoUninitialize(); return SUCCEEDED(hr) ? 0 : (DWORD)hr;
}
static DWORD repair(void) {
    if (!preflight()) return 1;
    wchar_t install[192], settings[256];
    swprintf(install, 192, L"%ls\\InstallFolder", branch);
    swprintf(settings, 256, L"%ls\\%ls\\PlayOnlineViewer\\Settings", branch, !wcscmp(region, L"JP") ? L"Square" : L"SquareEnix");
    if (!snapshot_registry(install, settings)) { log_line(L"Cannot save registry-before.txt; repair stopped before changes."); return 1; }
    DWORD lang = !wcscmp(region, L"JP") ? 0 : !wcscmp(region, L"EU") ? 2 : 1;
    if (write_value(install, L"1000", REG_SZ, pol, (DWORD)(wcslen(pol) + 1) * 2) || write_value(install, L"0001", REG_SZ, game, (DWORD)(wcslen(game) + 1) * 2) || write_value(settings, L"Language", REG_DWORD, &lang, sizeof(lang))) return 1;
    wchar_t dlls[3][CAP]; wcscpy(dlls[0], core); file_in(dlls[1], game, L"FFXi.dll"); file_in(dlls[2], game, L"FFXiMain.dll");
    for (int i = 0; i < 3; i++) {
        wchar_t command[CAP * 2 + 64]; swprintf(command, CAP * 2 + 64, L"\"%ls\" --register-dll \"%ls\"", self, dlls[i]);
        DWORD code = run_child(self, command, base, CREATE_NO_WINDOW, 60000);
        if (code) { log_line(L"Repair stopped: registration worker exited 0x%08lx. Existing changes may be partial. No game was launched.", code); return code; }
    }
    log_line(L"Registration succeeded. Select Launch FFXI to test login separately."); return 0;
}
static DWORD launch(BOOL updater) {
    wchar_t executable[CAP], command[CAP * 2];
    if (!preflight()) return 1;
    if (updater) file_in(executable, pol, L"pol.exe"); else wcscpy(executable, loader);
    if (!exists(executable) || !is_x86(executable)) { log_line(L"Missing or incompatible launcher executable. Import xiloader and export a launcher update."); return 1; }
    if (updater) swprintf(command, CAP * 2, L"\"%ls\"", executable);
    else swprintf(command, CAP * 2, L"\"%ls\" --server %ls --lang %ls", executable, host, region);
    log_line(L"Starting %ls. Keep this launcher open while the game runs. Account entry remains in xiloader.", updater ? L"PlayOnline" : L"xiloader");
    DWORD code = run_child(executable, command, pol, CREATE_NEW_CONSOLE, INFINITE);
    log_line(L"Child process exited 0x%08lx. This is not a gameplay readiness check.", code); return code;
}
#ifdef LSB_TEST_MODE
static BOOL test_registry(void) {
    HKEY h; LONG rc = RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\LSBLauncherTests\\HKLM", 0, NULL, 0, KEY_ALL_ACCESS, NULL, &h, NULL);
    if (rc) return FALSE; rc = RegOverridePredefKey(HKEY_LOCAL_MACHINE, h); RegCloseKey(h); return !rc;
}
#endif
static void refresh(void) {
    FILE *f = _wfopen(logfile, L"rb"); if (!f) return;
    char bytes[65536]; fseek(f, 0, SEEK_END); long size = ftell(f); fseek(f, size > 60000 ? size - 60000 : 0, SEEK_SET);
    size_t count = fread(bytes, 1, sizeof(bytes) - 1, f); fclose(f); bytes[count] = 0;
    wchar_t text[65536]; MultiByteToWideChar(CP_UTF8, 0, bytes, -1, text, 65536);
    SetWindowTextW(output, text); SendMessageW(output, EM_SETSEL, (WPARAM)-1, (LPARAM)-1); SendMessageW(output, EM_SCROLLCARET, 0, 0);
}
static DWORD WINAPI worker(void *unused) {
    (void)unused; DWORD code = operation == 0 ? repair() : launch(operation == 2);
    PostMessageW(window, DONE, code, 0); return 0;
}
static LRESULT CALLBACK proc(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
    switch (message) {
    case WM_CREATE:
        for (int i = 0; i < 3; i++) {
            const wchar_t *names[] = {L"1. Repair registration", L"2. Launch FFXI", L"Open PlayOnline updater"};
            buttons[i] = CreateWindowW(L"BUTTON", names[i], WS_CHILD | WS_VISIBLE | WS_TABSTOP, 12 + i * 255, 12, 245, 42, hwnd, (HMENU)(INT_PTR)(100 + i), NULL, NULL);
        }
        CreateWindowW(L"STATIC", L"Use your working GameHub container settings. Repair changes this container's registry. Back up the container first.\r\nLog: lsb-launcher.log beside this EXE. Enter your account in the xiloader window; credentials are not recorded here.", WS_CHILD | WS_VISIBLE, 12, 62, 950, 42, hwnd, NULL, NULL, NULL);
        output = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_VSCROLL | ES_MULTILINE | ES_READONLY | ES_AUTOVSCROLL, 12, 112, 950, 400, hwnd, NULL, NULL, NULL);
        SendMessageW(output, WM_SETFONT, (WPARAM)GetStockObject(ANSI_FIXED_FONT), TRUE); refresh(); SetTimer(hwnd, 1, 1500, NULL); return 0;
    case WM_SIZE: if (output) MoveWindow(output, 12, 112, LOWORD(lp) - 24, HIWORD(lp) - 124, TRUE); return 0;
    case WM_COMMAND:
        if (LOWORD(wp) >= 100 && LOWORD(wp) <= 102 && !busy) {
            operation = LOWORD(wp) - 100;
            if (!operation && MessageBoxW(hwnd, L"Register the exported PlayOnline and FFXI files in this container? Back up your working container first. The registry snapshot is diagnostic only; it cannot undo COM registration.", L"Repair registration", MB_YESNO | MB_ICONQUESTION) != IDYES) return 0;
            busy = TRUE; for (int i = 0; i < 3; i++) EnableWindow(buttons[i], FALSE);
            HANDLE thread = CreateThread(NULL, 0, worker, NULL, 0, NULL);
            if (thread) CloseHandle(thread); else PostMessageW(hwnd, DONE, GetLastError(), 0);
        } return 0;
    case WM_TIMER: if (busy) refresh(); return 0;
    case DONE:
        busy = FALSE; for (int i = 0; i < 3; i++) EnableWindow(buttons[i], TRUE); refresh();
        if (wp) MessageBoxW(hwnd, L"The operation failed. See the log in this window, or send lsb-launcher.log from beside the EXE.", L"LSB FFXI Launcher", MB_OK | MB_ICONERROR); return 0;
    case WM_CLOSE:
        if (busy) { MessageBoxW(hwnd, L"Close the game or wait for registration to finish before closing this launcher.", L"Operation running", MB_OK); return 0; }
        DestroyWindow(hwnd); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(hwnd, message, wp, lp);
}
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR cmd, int show) {
    (void)instance; (void)show; (void)previous; (void)cmd;
    DWORD n = GetModuleFileNameW(NULL, self, CAP); if (!n || n >= CAP) return 1;
    wcscpy(base, self); parent_of(base);
    if (!file_in(ini, base, L"lsb-launcher.ini") || !file_in(logfile, base, L"lsb-launcher.log")) return 1;
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOOPENFILEERRORBOX);
    int argc; wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc); if (!argv) return 1;
#ifdef LSB_TEST_MODE
    if (!test_registry()) return 90;
#endif
    if (argc == 3 && !wcscmp(argv[1], L"--register-dll")) { DWORD code = register_dll(argv[2]); LocalFree(argv); return (int)code; }
    if (!load_config()) {
#ifndef LSB_TEST_MODE
        MessageBoxW(NULL, L"Cannot load lsb-launcher.ini. Extract the complete launcher ZIP beside its client folder. See lsb-launcher.log for details.", L"LSB FFXI Launcher", MB_OK | MB_ICONERROR);
#endif
        LocalFree(argv); return 2;
    }
#ifdef LSB_TEST_MODE
    int result = argc == 2 && !wcscmp(argv[1], L"--repair") ? (int)repair() : argc == 2 && !wcscmp(argv[1], L"--launch") ? (int)launch(FALSE) : 0;
    LocalFree(argv); return result;
#else
    LocalFree(argv);
    WNDCLASSW cls = {0}; cls.lpfnWndProc = proc; cls.hInstance = instance; cls.lpszClassName = L"LSBLauncher"; cls.hCursor = LoadCursor(NULL, IDC_ARROW); cls.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    if (!RegisterClassW(&cls)) return 1;
    window = CreateWindowW(cls.lpszClassName, L"LSB FFXI Launcher", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1000, 620, NULL, NULL, instance, NULL);
    if (!window) return 1; ShowWindow(window, show); UpdateWindow(window);
    MSG msg; while (GetMessageW(&msg, NULL, 0, 0) > 0) { if (!IsDialogMessageW(window, &msg)) { TranslateMessage(&msg); DispatchMessageW(&msg); } }
    return 0;
#endif
}
