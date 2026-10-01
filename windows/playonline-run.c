/* Launch only the staged viewer. This helper never reads a window title,
 * account, console output or user input. All diagnostic fields are fixed or
 * numeric; the child inherits only the supervisor's private output handles. */
#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

#ifdef LSB_PLAYONLINE_TEST_MODE
/* Native Windows CI uses an isolated cwd; production always writes /session. */
#define RECEIPT_NEW L"playonline-process.new"
#define RECEIPT_JSON L"playonline-process.json"
#else
#define RECEIPT_NEW L"Z:\\session\\playonline-process.new"
#define RECEIPT_JSON L"Z:\\session\\playonline-process.json"
#endif

static DWORD child_pid,window_error;
static BOOL visible_window_seen;
static ULONGLONG launched_at;
static DWORD heartbeat_samples,cpu_error,window_probe_error;
static ULONGLONG heartbeat_elapsed_ms,cpu_user_ms,cpu_kernel_ms;
static BOOL window_found,window_responsive;
static HWND observed_window;

static BOOL receipt(const char *phase,DWORD error,DWORD code){
    char data[1024];
    int length=snprintf(data,sizeof(data),
        "{\"format\":2,\"bits\":32,\"phase\":\"%s\",\"win32_error\":%lu,\"child_exit\":%lu,\"child_pid\":%lu,\"visible_window_seen\":%s,\"window_error\":%lu,\"elapsed_ms\":%llu,\"heartbeat_samples\":%lu,\"heartbeat_elapsed_ms\":%llu,\"cpu_user_ms\":%llu,\"cpu_kernel_ms\":%llu,\"cpu_error\":%lu,\"window_found\":%s,\"window_responsive\":%s,\"window_probe_error\":%lu}\n",
        phase,(unsigned long)error,(unsigned long)code,(unsigned long)child_pid,
        visible_window_seen?"true":"false",(unsigned long)window_error,
        launched_at?(unsigned long long)(GetTickCount64()-launched_at):0ULL,
        (unsigned long)heartbeat_samples,(unsigned long long)heartbeat_elapsed_ms,
        (unsigned long long)cpu_user_ms,(unsigned long long)cpu_kernel_ms,(unsigned long)cpu_error,
        window_found?"true":"false",window_responsive?"true":"false",(unsigned long)window_probe_error);
    if(length<=0||(size_t)length>=sizeof(data))return FALSE;
    HANDLE file=CreateFileW(RECEIPT_NEW,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);
    if(file==INVALID_HANDLE_VALUE)return FALSE;
    DWORD written=0;BOOL ok=WriteFile(file,data,(DWORD)length,&written,NULL)&&written==(DWORD)length;
    if(ok)ok=FlushFileBuffers(file);
    if(!CloseHandle(file))ok=FALSE;
    if(!ok){DeleteFileW(RECEIPT_NEW);return FALSE;}
    return MoveFileExW(RECEIPT_NEW,RECEIPT_JSON,MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
}

static BOOL executable_path(const WCHAR *path,WCHAR *directory){
    size_t length=wcslen(path);
    if(length<10||length>=1024||wcsncmp(path,L"D:\\",3))return FALSE;
    const WCHAR *segment=path+3;
    for(const WCHAR *p=segment;;p++){
        if(*p&&(*p<32||wcschr(L"/\":*?<>|",*p)))return FALSE;
        if(*p==L'\\'||!*p){
            size_t size=(size_t)(p-segment);
            if(!size||segment[size-1]==L'.'||segment[size-1]==L' '||
               (size==1&&segment[0]==L'.')||(size==2&&segment[0]==L'.'&&segment[1]==L'.'))return FALSE;
            if(!*p)break;
            segment=p+1;
        }
    }
    if(_wcsicmp(segment,L"pol.exe"))return FALSE;
    WCHAR full[1024];DWORD size=GetFullPathNameW(path,1024,full,NULL);
    if(!size||size>=1024||wcscmp(full,path))return FALSE;
    wcscpy(directory,path);WCHAR *end=wcsrchr(directory,L'\\');
    /* D:\pol.exe has D:\ as cwd rather than the drive-relative D:. */
    if(end==directory+2)end[1]=0;else *end=0;
    return TRUE;
}

static BOOL CALLBACK observe_window(HWND window,LPARAM unused){
    (void)unused;DWORD pid=0;GetWindowThreadProcessId(window,&pid);
    if(!observed_window&&pid==child_pid&&IsWindowVisible(window))observed_window=window;
    return TRUE;
}
static void observe_visibility(void){
    observed_window=NULL;window_error=0;SetLastError(0);
    if(!EnumWindows(observe_window,0)){
        DWORD error=GetLastError();window_error=error?error:ERROR_GEN_FAILURE;
    }
    if(observed_window)visible_window_seen=TRUE;
}
static void observe_cpu(HANDLE process){
    FILETIME created,exited,kernel,user;
    cpu_error=0;cpu_user_ms=cpu_kernel_ms=0;
    if(GetProcessTimes(process,&created,&exited,&kernel,&user)){
        cpu_user_ms=(((ULONGLONG)user.dwHighDateTime<<32)|user.dwLowDateTime)/10000;
        cpu_kernel_ms=(((ULONGLONG)kernel.dwHighDateTime<<32)|kernel.dwLowDateTime)/10000;
    }else{cpu_error=GetLastError();if(!cpu_error)cpu_error=ERROR_GEN_FAILURE;}
}
static void observe(HANDLE process){
    window_found=window_responsive=FALSE;window_probe_error=0;
    observe_cpu(process);
    observe_visibility();
    if(observed_window){
        DWORD pid=0;GetWindowThreadProcessId(observed_window,&pid);
        if(pid==child_pid&&IsWindowVisible(observed_window)){
            visible_window_seen=window_found=TRUE;
            DWORD_PTR result=0;SetLastError(0);
            /* WM_NULL asks only whether this thread pumps messages. Probe one
             * own-PID window at most; never read its title, text or inputs.
             * Do not use SMTO_NOTIMEOUTIFNOTHUNG, which can exceed this bound. */
            window_responsive=SendMessageTimeoutW(observed_window,WM_NULL,0,0,
                SMTO_ABORTIFHUNG|SMTO_BLOCK,200,&result)!=0;
            if(!window_responsive){
                window_probe_error=GetLastError();
                if(!window_probe_error)window_probe_error=ERROR_TIMEOUT;
            }
        }
    }
    if(heartbeat_samples<MAXDWORD)heartbeat_samples++;
    heartbeat_elapsed_ms=GetTickCount64()-launched_at;
}

static BOOL duplicate_output(DWORD kind,HANDLE *result){
    HANDLE source=GetStdHandle(kind);
    if(!source||source==INVALID_HANDLE_VALUE){SetLastError(ERROR_INVALID_HANDLE);return FALSE;}
    return DuplicateHandle(GetCurrentProcess(),source,GetCurrentProcess(),result,0,TRUE,DUPLICATE_SAME_ACCESS);
}

int wmain(int argc,WCHAR **argv){
    SetErrorMode(SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);
    WCHAR directory[1024],command[1028];
    if(argc!=2||!executable_path(argv[1],directory))return 80;
    /* The only argument is argv[0]. Quotes/backslash ambiguity was rejected
     * above, and lpApplicationName explicitly selects the validated image. */
    command[0]=L'"';wcscpy(command+1,argv[1]);wcscat(command,L"\"");
    STARTUPINFOEXW startup={0};PROCESS_INFORMATION process={0};
    startup.StartupInfo.cb=sizeof(startup);startup.StartupInfo.dwFlags=STARTF_USESTDHANDLES;
    SECURITY_ATTRIBUTES security={sizeof(security),NULL,TRUE};
    HANDLE handles[3]={INVALID_HANDLE_VALUE,NULL,NULL};
    handles[0]=CreateFileW(L"NUL",GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,OPEN_EXISTING,0,NULL);
    BOOL ready=handles[0]!=INVALID_HANDLE_VALUE&&duplicate_output(STD_OUTPUT_HANDLE,&handles[1])&&duplicate_output(STD_ERROR_HANDLE,&handles[2]);
    DWORD error=ready?0:GetLastError();
    SIZE_T bytes=0;BOOL attributes_ready=FALSE,started=FALSE;
    if(ready){
        InitializeProcThreadAttributeList(NULL,1,0,&bytes);
        startup.lpAttributeList=HeapAlloc(GetProcessHeap(),0,bytes);
        if(!startup.lpAttributeList){ready=FALSE;error=ERROR_NOT_ENOUGH_MEMORY;}
        else{
            attributes_ready=InitializeProcThreadAttributeList(startup.lpAttributeList,1,0,&bytes);
            ready=attributes_ready&&UpdateProcThreadAttribute(startup.lpAttributeList,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,handles,sizeof(handles),NULL,NULL);
            if(!ready)error=GetLastError();
        }
    }
    if(ready){
        startup.StartupInfo.hStdInput=handles[0];startup.StartupInfo.hStdOutput=handles[1];startup.StartupInfo.hStdError=handles[2];
        started=CreateProcessW(argv[1],command,NULL,NULL,TRUE,EXTENDED_STARTUPINFO_PRESENT,NULL,directory,&startup.StartupInfo,&process);
        if(!started)error=GetLastError();
    }
    if(attributes_ready)DeleteProcThreadAttributeList(startup.lpAttributeList);
    if(startup.lpAttributeList)HeapFree(GetProcessHeap(),0,startup.lpAttributeList);
    for(unsigned i=0;i<3;i++)if(handles[i]&&handles[i]!=INVALID_HANDLE_VALUE)CloseHandle(handles[i]);
    if(!started)return receipt("create_failed",error,0)?1:90;
    CloseHandle(process.hThread);child_pid=process.dwProcessId;launched_at=GetTickCount64();
    if(!receipt("running",0,0)){TerminateProcess(process.hProcess,90);CloseHandle(process.hProcess);return 90;}
    DWORD waited,code=0;ULONGLONG recorded_at=launched_at,sampled_at=launched_at;
    while((waited=WaitForSingleObject(process.hProcess,visible_window_seen?5000:100))==WAIT_TIMEOUT){
        /* Preserve the startup-only visibility latency measurement, then stop
         * frequent scanning. CPU/message probes run at most once per 5 seconds
         * except the first visible-window observation. */
        BOOL sample=visible_window_seen;
        if(!sample){observe_visibility();sample=visible_window_seen||GetTickCount64()-sampled_at>=5000;}
        if(sample){observe(process.hProcess);sampled_at=GetTickCount64();}
        if(sample||GetTickCount64()-recorded_at>=1000){
            if(!receipt("running",0,0)){TerminateProcess(process.hProcess,90);CloseHandle(process.hProcess);return 90;}
            recorded_at=GetTickCount64();
        }
    }
    BOOL ok=waited==WAIT_OBJECT_0&&GetExitCodeProcess(process.hProcess,&code);
    error=ok?0:GetLastError();observe_cpu(process.hProcess);CloseHandle(process.hProcess);
    if(!receipt(ok?"exited":"wait_failed",error,code))return 91;
    return ok&&code==0?0:1;
}
