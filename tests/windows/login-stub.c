#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <stdio.h>
#include <wchar.h>
#include <string.h>
#ifdef MISSING_IMPORT
__declspec(dllimport) int missing_fixture(void);
#endif
int wmain(int argc,WCHAR **wide){
#ifdef MISSING_IMPORT
    if(missing_fixture()!=1)return 98;
#endif
    char values[16][1024];char *argv[16];if(argc>16)return 94;
    for(int i=0;i<argc;i++){if(!WideCharToMultiByte(CP_UTF8,0,wide[i],-1,values[i],1024,NULL,NULL))return 94;argv[i]=values[i];}
    const WCHAR *user=L"LSB_private_account",*pass=L"LSB \"quoted\" & % ! \\ tail\\";
    WCHAR cwd[1024];GetCurrentDirectoryW(1024,cwd);
    BOOL good=argc==9&&!strcmp(argv[1],"--server")&&!strcmp(argv[2],"127.0.0.1")&&
        !strcmp(argv[3],"--username")&&!strcmp(argv[4],"LSB_private_account")&&!strcmp(argv[5],"--password")&&!strcmp(argv[6],"LSB \"quoted\" & % ! \\ tail\\")&&
        !strcmp(argv[7],"--lang")&&!strcmp(argv[8],"1")&&!wcscmp(cwd,L"D:\\FINAL FANTASY XI\\boot loader");
    FILE *f=_wfopen(L"Z:\\session\\login-fixture.json",L"wb");if(!f)return 95;
    fprintf(f,"{\"arguments_and_cwd_match\":%s}\n",good?"true":"false");fclose(f);
    /* Deliberately hostile output shapes must never reach persisted launch logs. */
    wprintf(L"username=%ls password=%ls\n",user,pass);fflush(stdout);
    for(int i=0;i<18000;i++)fputc('X',stdout);wprintf(L"%ls\n",pass);fflush(stdout);
    DWORD written;WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),pass,(DWORD)wcslen(pass)*2,&written,NULL);
    WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),"\n",1,&written,NULL);
    if(!good)return 96;
    if(GetFileAttributesW(L"D:\\launch-fail")!=INVALID_FILE_ATTRIBUTES)return (int)0xc0000135;
    FILE *failure=_wfopen(L"D:\\login-reject",L"rb");
    if(failure){
        int kind=fgetc(failure);fclose(failure);
        const char *reason=kind=='1'?" Invalid username or password.":kind=='2'?" Account already logged in.":
            kind=='3'?" Expected xiloader version mismatch; check with your provider.":"";
        if(kind=='4')puts("[09/21/26 01:00:00] Failed to connect to server!");
        else{
            fputs("[09/21/26 01:00:00] \x1b[31mFailed to login.",stdout);fflush(stdout);Sleep(650);
            printf("%s\x1b[0m\n",reason);
        }
        fflush(stdout);
        if(kind=='0')return 0; /* A zero exit must not hide a reported rejection. */
        for(;;)Sleep(100); /* Model xiloader waiting in its inaccessible menu. */
    }
    puts("Successfully logged in. Launching FINAL FANTASY XI.");fflush(stdout);
    while(GetFileAttributesW(L"D:\\launch-hang")!=INVALID_FILE_ATTRIBUTES)Sleep(100);
    FILE *post=_wfopen(L"D:\\post-login",L"rb");
    if(post){
        int kind=fgetc(post);fclose(post);
        if(kind=='e')RaiseException(EXCEPTION_INT_DIVIDE_BY_ZERO,EXCEPTION_NONCONTINUABLE,0,NULL);
        if(kind=='d'){
            /* Real Wine error diagnostics must survive the private output pipe.
             * A recoverable load failure must not abort an otherwise normal run. */
            LoadLibraryW(L"LSB_private_account_quoted_missing.dll");
            puts("err:   D3D9: fixture diagnostic LSB_private_account quoted");fflush(stdout);
        }
        if(kind=='p')puts("[09/21/26 01:00:01] Failed to initialize instance of polcore!");
        if(kind=='f')puts("[09/21/26 01:00:01] Failed to initialize instance of FFxi!");
        if(kind!='d'){puts("Closing...");fflush(stdout);return 0;}
    }
    HMODULE main_dll=LoadLibraryW(L"D:\\FINAL FANTASY XI\\FFXiMain.dll");if(!main_dll)return 97;
    WNDCLASSW cls={0};cls.lpfnWndProc=DefWindowProcW;cls.hInstance=GetModuleHandleW(NULL);cls.lpszClassName=L"FFXiClass";
    if(!RegisterClassW(&cls))return 97;
    HWND window=CreateWindowW(cls.lpszClassName,pass,WS_OVERLAPPEDWINDOW|WS_VISIBLE,0,0,640,480,NULL,NULL,cls.hInstance,NULL);
    if(!window)return 97;
    ULONGLONG end=GetTickCount64()+2000;MSG msg;
    while(GetTickCount64()<end){while(PeekMessageW(&msg,NULL,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}Sleep(20);}
    DestroyWindow(window);
    /* Model the game saving a changed user preference. Relaunch must preserve it. */
    HKEY key;if(RegOpenKeyExW(HKEY_LOCAL_MACHINE,L"Software\\PlayOnlineUS\\SquareEnix\\FinalFantasyXI",0,KEY_SET_VALUE|KEY_WOW64_32KEY,&key)==0){
        DWORD width=1024;RegSetValueExW(key,L"0001",0,REG_DWORD,(BYTE*)&width,4);RegCloseKey(key);
    }
    FreeLibrary(main_dll);return 0;
}
