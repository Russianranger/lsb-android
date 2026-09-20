#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <stdio.h>
#include <wchar.h>
#ifdef MISSING_IMPORT
__declspec(dllimport) int missing_fixture(void);
#endif
int wmain(int argc,WCHAR **argv){
#ifdef MISSING_IMPORT
    if(missing_fixture()!=1)return 98;
#endif
    const WCHAR *user=L"LSB_private_account",*pass=L"LSB \"quoted\" & % ! \\ tail\\";
    WCHAR cwd[1024];GetCurrentDirectoryW(1024,cwd);
    BOOL good=argc==9&&!wcscmp(argv[1],L"--server")&&!wcscmp(argv[2],L"127.0.0.1")&&
        !wcscmp(argv[3],L"--username")&&!wcscmp(argv[4],user)&&!wcscmp(argv[5],L"--password")&&!wcscmp(argv[6],pass)&&
        !wcscmp(argv[7],L"--lang")&&!wcscmp(argv[8],L"1")&&!wcscmp(cwd,L"D:\\FINAL FANTASY XI\\boot loader");
    FILE *f=_wfopen(L"Z:\\session\\login-fixture.json",L"wb");if(!f)return 95;
    fprintf(f,"{\"arguments_and_cwd_match\":%s}\n",good?"true":"false");fclose(f);
    /* Deliberately hostile output shapes must never reach persisted launch logs. */
    wprintf(L"username=%ls password=%ls\n",user,pass);fflush(stdout);
    for(int i=0;i<18000;i++)fputc('X',stdout);wprintf(L"%ls\n",pass);fflush(stdout);
    DWORD written;WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),pass,(DWORD)wcslen(pass)*2,&written,NULL);
    if(!good)return 96;
    if(GetFileAttributesW(L"D:\\launch-fail")!=INVALID_FILE_ATTRIBUTES)return (int)0xc0000135;
    puts("Successfully logged in. Launching FINAL FANTASY XI.");fflush(stdout);
    while(GetFileAttributesW(L"D:\\launch-hang")!=INVALID_FILE_ATTRIBUTES)Sleep(100);
    Sleep(500);return 0;
}
