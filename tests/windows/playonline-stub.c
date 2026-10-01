#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <wchar.h>

/* Only this synthetic fixture accepts an internal child argument. Production
 * playonline-run still passes exactly the validated pol.exe image and no args. */
static BOOL restart_receipt(const char *phase,DWORD code,DWORD began){
    WCHAR local[2];
    BOOL native_test=GetEnvironmentVariableW(L"LSB_PLAYONLINE_FIXTURE_RECEIPT_LOCAL",local,2)==1&&local[0]==L'1';
    const WCHAR *temporary=native_test?L"playonline-restarted.new":L"Z:\\session\\playonline-restarted.new";
    const WCHAR *target=native_test?L"playonline-restarted.json":L"Z:\\session\\playonline-restarted.json";
    char data[256];
    int size=snprintf(data,sizeof(data),
        "{\"format\":1,\"phase\":\"%s\",\"child_pid\":%lu,\"elapsed_ms\":%lu,\"child_exit\":%lu,\"canonical_image\":true,\"working_directory\":true}\n",
        phase,(unsigned long)GetCurrentProcessId(),(unsigned long)(GetTickCount()-began),(unsigned long)code);
    if(size<=0||(size_t)size>=sizeof(data))return FALSE;
    HANDLE file=CreateFileW(temporary,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);
    if(file==INVALID_HANDLE_VALUE)return FALSE;
    DWORD written=0;
    BOOL ok=WriteFile(file,data,(DWORD)size,&written,NULL)&&written==(DWORD)size&&FlushFileBuffers(file);
    if(!CloseHandle(file))ok=FALSE;
    if(!ok){DeleteFileW(temporary);return FALSE;}
    return MoveFileExW(temporary,target,MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
}

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
    BOOL restarted=argc==2&&!wcscmp(argv[1],L"--fixture-restarted");
    WCHAR image[1024],image_directory[1024],directory[1024];
    DWORD image_size=GetModuleFileNameW(NULL,image,1024),directory_size=GetCurrentDirectoryW(1024,directory);
    if((argc!=1&&!restarted)||!image_size||image_size>=1024||!directory_size||directory_size>=1024)return 72;
    if(_wcsicmp(image,argv[0]))return 73;
    wcscpy(image_directory,image);
    WCHAR *end=wcsrchr(image_directory,L'\\');if(!end)return 74;
    if(end==image_directory+2)end[1]=0;else *end=0;
    if(_wcsicmp(image_directory,directory))return 75;
    DWORD code=0,visible=0,delay=0,restart=0;
    FILE *fixture=_wfopen(L"playonline-fixture.txt",L"rb");
    if(fixture){
        code=number(fixture);visible=number(fixture);delay=number(fixture);
        int next=fgetc(fixture);
        if(next!=EOF){ungetc(next,fixture);restart=number(fixture);if(fgetc(fixture)!=EOF)return 76;}
        fclose(fixture);
    }
    if(delay>30000||visible>2||restart>1||(restarted&&!restart))return 76;
    if(restart&&!restarted){
        WCHAR command[1100];
        wcscpy(command,L"\"");wcscat(command,image);wcscat(command,L"\" --fixture-restarted");
        STARTUPINFOW startup={0};PROCESS_INFORMATION process={0};startup.cb=sizeof(startup);
        /* No inherited pipe handles: the original runner can finish while the
         * independent replacement stays alive, just like a viewer self-update. */
        if(!CreateProcessW(image,command,NULL,NULL,FALSE,CREATE_NO_WINDOW,NULL,directory,&startup,&process))return 79;
        CloseHandle(process.hThread);CloseHandle(process.hProcess);
        return 0;
    }
    DWORD restarted_at=GetTickCount();
    if(restarted&&!restart_receipt("running",0,restarted_at))return 79;
    const char output[]="PRIVATE-POL-STDOUT-SENTINEL\n",error[]="PRIVATE-POL-STDERR-SENTINEL\n";DWORD written=0;
    WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),output,sizeof(output)-1,&written,NULL);
    WriteFile(GetStdHandle(STD_ERROR_HANDLE),error,sizeof(error)-1,&written,NULL);
    /* Give native CPU accounting a deterministic nonzero workload before the
     * window exists. Mode 2 then deliberately never pumps that UI thread. */
    if(visible){DWORD began=GetTickCount();volatile DWORD work=0;while(GetTickCount()-began<100)work++;(void)work;}
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
        if(visible!=2){MSG message;while(PeekMessageW(&message,NULL,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}}
        Sleep(10);
    }
    if(window)DestroyWindow(window);
    if(restarted&&!restart_receipt("exited",code,restarted_at))return 79;
    ExitProcess(code);return 78;
}
