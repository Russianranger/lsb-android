/* Read-only observation of the startup APIs used by FFXiMain. No paths,
 * file contents, window titles or user input enter the diagnostic pipe. */
static HANDLE (WINAPI *real_open)(LPCSTR,DWORD,DWORD,LPSECURITY_ATTRIBUTES,DWORD,DWORD,HANDLE);
static BOOL (WINAPI *real_read)(HANDLE,LPVOID,DWORD,LPDWORD,LPOVERLAPPED);
static DWORD (WINAPI *real_size)(HANDLE,LPDWORD);
static BOOL (WINAPI *real_close)(HANDLE);
static HMODULE (WINAPI *real_load)(LPCSTR);
static BOOL (WINAPI *real_version)(LPOSVERSIONINFOA);
static ATOM (WINAPI *real_register)(const WNDCLASSA*);
static HWND (WINAPI *real_window)(DWORD,LPCSTR,LPCSTR,DWORD,int,int,int,int,HWND,HMENU,HINSTANCE,LPVOID);
static void* (WINAPI *real_d3d8)(UINT);
static SRWLOCK file_lock=SRWLOCK_INIT;
static struct {HANDLE handle;unsigned kind;BOOL read;} file_slots[16];
static const char *basename_a(const char *path){
    const char *name=path;for(const char *p=path;*p;p++)if(*p=='\\'||*p=='/')name=p+1;return name;
}
static unsigned file_kind(const char *path){
    if(!path)return 0;const char *name=basename_a(path);
    return !_stricmp(name,"patch.ver")?1:!_stricmp(name,"FTABLE.DAT")?2:!_stricmp(name,"VTABLE.DAT")?3:0;
}
static DWORD file_detail(unsigned kind,DWORD value){return (kind<<24)|(value>0xffffff?0xffffff:value);}
static unsigned tracked_file(HANDLE h,BOOL reading,BOOL close){
    unsigned kind=0;AcquireSRWLockExclusive(&file_lock);
    for(unsigned i=0;i<16;i++)if(file_slots[i].kind&&file_slots[i].handle==h){
        if(!reading||!file_slots[i].read)kind=file_slots[i].kind;
        if(reading)file_slots[i].read=TRUE;
        if(close)file_slots[i].kind=0;break;
    }
    ReleaseSRWLockExclusive(&file_lock);return kind;
}
static HANDLE WINAPI observed_open(LPCSTR path,DWORD access,DWORD share,LPSECURITY_ATTRIBUTES sa,DWORD disposition,DWORD flags,HANDLE template){
    HANDLE h=real_open(path,access,share,sa,disposition,flags,template);DWORD error=GetLastError();
    unsigned kind=file_kind(path);
    if(kind){
        event("main_file_open",h==INVALID_HANDLE_VALUE?error:0,file_detail(kind,h!=INVALID_HANDLE_VALUE));
        if(h!=INVALID_HANDLE_VALUE){
            AcquireSRWLockExclusive(&file_lock);
            unsigned i;for(i=0;i<16;i++)if(!file_slots[i].kind)break;
            if(i<16){file_slots[i].handle=h;file_slots[i].kind=kind;file_slots[i].read=FALSE;}
            ReleaseSRWLockExclusive(&file_lock);
            if(i==16)event("observer_failed",ERROR_NOT_ENOUGH_MEMORY,1);
        }
    }
    SetLastError(error);return h;
}
static BOOL WINAPI observed_read(HANDLE h,LPVOID data,DWORD count,LPDWORD read,LPOVERLAPPED overlap){
    BOOL ok=real_read(h,data,count,read,overlap);DWORD error=GetLastError();
    unsigned kind=tracked_file(h,TRUE,FALSE);
    if(kind)event("main_file_read",ok?0:error,file_detail(kind,ok&&read?*read:0));
    SetLastError(error);return ok;
}
static DWORD WINAPI observed_size(HANDLE h,LPDWORD high){
    DWORD size=real_size(h,high),error=GetLastError();unsigned kind=tracked_file(h,FALSE,FALSE);
    if(kind)event("main_file_size",size==INVALID_FILE_SIZE?error:0,file_detail(kind,size));
    SetLastError(error);return size;
}
static BOOL WINAPI observed_close(HANDLE h){
    BOOL ok=real_close(h);DWORD error=GetLastError();if(ok)tracked_file(h,FALSE,TRUE);SetLastError(error);return ok;
}
static HMODULE WINAPI observed_load(LPCSTR path){
    HMODULE h=real_load(path);DWORD error=GetLastError();
    if(path&&!_stricmp(basename_a(path),"dpnhpast.dll"))event("main_directplay_load",h?0:error,h!=NULL);
    SetLastError(error);return h;
}
static BOOL WINAPI observed_version(LPOSVERSIONINFOA info){
    BOOL ok=real_version(info);DWORD error=GetLastError();
    event("main_windows_version",ok?0:error,ok?((info->dwMajorVersion&255)<<24)|((info->dwMinorVersion&255)<<16)|(info->dwPlatformId&65535):0);
    SetLastError(error);return ok;
}
static ATOM WINAPI observed_register(const WNDCLASSA *info){
    ATOM atom=real_register(info);DWORD error=GetLastError();
    event("main_window_class",atom?0:error,atom!=0);SetLastError(error);return atom;
}
static HWND WINAPI observed_window(DWORD ex,LPCSTR cls,LPCSTR title,DWORD style,int x,int y,int w,int h,HWND parent,HMENU menu,HINSTANCE instance,LPVOID param){
    HWND hwnd=real_window(ex,cls,title,style,x,y,w,h,parent,menu,instance,param);DWORD error=GetLastError();
    event("main_window_create",hwnd?0:error,hwnd!=NULL);SetLastError(error);return hwnd;
}
static void* WINAPI observed_d3d8(UINT sdk){
    void *object=real_d3d8(sdk);DWORD error=GetLastError();event("main_d3d8_create",0,object!=NULL);gt_attach((IDirect3D8*)object);SetLastError(error);return object;
}
static void observe_directory(void){
    DWORD saved=GetLastError();char module[MAX_PATH],cwd[MAX_PATH];
    DWORD n=GetModuleFileNameA(GetModuleHandleW(L"FFXiMain.dll"),module,MAX_PATH);
    DWORD m=GetCurrentDirectoryA(MAX_PATH,cwd),error=GetLastError();
    DWORD match=0;
    if(n&&n<MAX_PATH&&m&&m<MAX_PATH){
        char *end=(char*)basename_a(module);if(end>module){end[-1]=0;match=_stricmp(module,cwd)?2:1;}
    }
    event("main_directory",m&&m<MAX_PATH?0:error,match);SetLastError(saved);
}
