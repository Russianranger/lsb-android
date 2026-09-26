#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <wchar.h>

static DWORD number(FILE *file){
    ULONGLONG parsed=0;
    int value=fgetc(file);unsigned digits=0;
    while(value>='0'&&value<='9'){
        parsed=parsed*10+(value-'0');if(++digits>10||parsed>0xffffffffULL)ExitProcess(71);
        value=fgetc(file);
    }
    if(!digits||value!='\n')ExitProcess(71);
    return (DWORD)parsed;
}

int wmain(int argc,WCHAR **argv){
    WCHAR image[1024],directory[1024];
    if(argc!=1||!GetModuleFileNameW(NULL,image,1024)||!GetCurrentDirectoryW(1024,directory))return 72;
    if(_wcsicmp(image,argv[0]))return 73;
    WCHAR *end=wcsrchr(image,L'\\');if(!end)return 74;
    if(end==image+2)end[1]=0;else *end=0;
    if(_wcsicmp(image,directory))return 75;
    DWORD code=0,visible=0,delay=0;
    FILE *fixture=_wfopen(L"playonline-fixture.txt",L"rb");
    if(fixture){code=number(fixture);visible=number(fixture);delay=number(fixture);if(fgetc(fixture)!=EOF)return 76;fclose(fixture);}
    if(delay>30000||visible>1)return 76;
    const char output[]="PRIVATE-POL-STDOUT-SENTINEL\n",error[]="PRIVATE-POL-STDERR-SENTINEL\n";DWORD written=0;
    WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),output,sizeof(output)-1,&written,NULL);
    WriteFile(GetStdHandle(STD_ERROR_HANDLE),error,sizeof(error)-1,&written,NULL);
    HWND window=NULL;
    if(visible){
        /* The title intentionally resembles private data. Neither runner nor
         * receipt may read or preserve it. */
        window=CreateWindowExW(0,L"STATIC",L"PRIVATE-ACCOUNT-TITLE-MUST-NOT-BE-RECORDED",WS_OVERLAPPEDWINDOW|WS_VISIBLE,
            10,10,320,200,NULL,NULL,GetModuleHandleW(NULL),NULL);
        if(!window)return 77;
        ShowWindow(window,SW_SHOW);UpdateWindow(window);
    }
    DWORD began=GetTickCount();
    while(GetTickCount()-began<delay){
        MSG message;while(PeekMessageW(&message,NULL,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}
        Sleep(10);
    }
    if(window)DestroyWindow(window);
    ExitProcess(code);return 78;
}
