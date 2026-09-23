/* Process-local, opt-in startup observation. No game code, network changes,
 * registry changes, credential reads, or suppression of errors/exceptions.
 * IFFXiEntry ABI: LandSandBoat/xiloader v2.0.0 src/ffxi.h (IUnknown + 3 methods).
 * IGameMain ABI: same source src/ffximain.h (IUnknown + 4 methods).
 */
#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <stdio.h>
#include <string.h>

typedef HRESULT (WINAPI *CreateInstance)(REFCLSID,IUnknown*,DWORD,REFIID,void**);
typedef HRESULT (WINAPI *Start)(void*,IUnknown*,void*);
static CreateInstance real_create;
static const IID entry_iid={0x989d790c,0x6236,0x11d4,{0x80,0xe9,0,0x10,0x5a,0x81,0xe8,0x90}};
static const IID main_iid={0x493bf7b9,0x0c3a,0x43b5,{0xbf,0xa6,0x28,0xfb,0xee,0x25,0x1e,0x3d}};
static const CLSID entry_class={0x989d790d,0x6236,0x11d4,{0x80,0xe9,0,0x10,0x5a,0x81,0xe8,0x90}};
static const CLSID main_class={0x1027dc46,0x750d,0x4b1f,{0x88,0x34,0x1d,0x25,0xb8,0xbe,0xba,0xb8}};
typedef struct {void **table;Start original;BOOL main;} Slot;
static Slot slots[16];
static unsigned slot_count;
static SRWLOCK slot_lock=SRWLOCK_INIT;

/* Only fixed labels and numeric fields. Write directly to the private stderr
 * pipe, not the console xiloader hides, and preserve the caller's LastError. */
static void event(const char *name,DWORD code,DWORD detail){
    DWORD saved=GetLastError(),written;char row[160];
    int n=snprintf(row,sizeof(row),"lsb-startup-v1 %s %08lx %08lx %08lx %08lx\n",name,
        (unsigned long)GetCurrentProcessId(),(unsigned long)GetCurrentThreadId(),(unsigned long)code,(unsigned long)detail);
    if(n>0&&n<(int)sizeof(row))WriteFile(GetStdHandle(STD_ERROR_HANDLE),row,(DWORD)n,&written,NULL);
    SetLastError(saved);
}
#include "graphics-trace.h"
#include "startup-files.h"
static HRESULT WINAPI start(void *self,IUnknown *pol,void *message){
    DWORD incoming=GetLastError();Slot *slot=NULL;
    AcquireSRWLockShared(&slot_lock);
    for(unsigned i=slot_count;i>0;i--)if(slots[i-1].table==*(void***)self){slot=&slots[i-1];break;}
    ReleaseSRWLockShared(&slot_lock);
    if(!slot){event("observer_failed",ERROR_INVALID_DATA,0);return E_UNEXPECTED;}
    event(slot->main?"game_main_enter":"game_start_enter",0,0);
    if(slot->main)observe_directory();
    ULONGLONG before=GetTickCount64();
    SetLastError(incoming);
    HRESULT hr=slot->original(self,pol,message);
    DWORD error=GetLastError();ULONGLONG duration=GetTickCount64()-before;
    event(slot->main?"game_main_return":"game_start_return",(DWORD)hr,duration>0xffffffffULL?0xffffffff:(DWORD)duration);
    SetLastError(error);return hr;
}
static BOOL wrap(void *object,BOOL main){
    void **table=*(void***)object;BOOL ok=FALSE;DWORD old;
    AcquireSRWLockExclusive(&slot_lock);
    for(unsigned n=0;n<slot_count;n++)if(slots[n].table==table&&table[3]==(void*)start){ok=TRUE;goto done;}
    if(slot_count>=16){event("observer_failed",ERROR_NOT_ENOUGH_MEMORY,0);goto done;}
    if(!VirtualProtect(&table[3],sizeof(void*),PAGE_READWRITE,&old)){event("observer_failed",GetLastError(),0);goto done;}
    Slot *slot=&slots[slot_count++];slot->table=table;slot->original=(Start)table[3];slot->main=main;
    /* Change only the documented call slot, preserving the complete original
     * table, including any private implementation slots beyond the COM ABI.
     * The loaded image page is modified only within this diagnostic process. */
    InterlockedExchangePointer(&table[3],(void*)start);
    DWORD unused;if(!VirtualProtect(&table[3],sizeof(void*),old,&unused))event("observer_failed",GetLastError(),0);
    ok=TRUE;
done:
    ReleaseSRWLockExclusive(&slot_lock);return ok;
}
static unsigned hook(HMODULE module,BOOL main);
static HRESULT WINAPI create(REFCLSID cls,IUnknown *outer,DWORD context,REFIID iid,void **out){
    BOOL entry=IsEqualGUID(cls,&entry_class)&&IsEqualGUID(iid,&entry_iid);
    BOOL main=IsEqualGUID(cls,&main_class)&&IsEqualGUID(iid,&main_iid);
    if(entry||main)event(main?"game_main_com_enter":"ffxi_com_enter",0,0);
    HRESULT hr=real_create(cls,outer,context,iid,out);DWORD error=GetLastError();
    if(entry||main){
        event(main?"game_main_com_return":"ffxi_com_return",(DWORD)hr,SUCCEEDED(hr)&&out&&*out?1:0);
        if(SUCCEEDED(hr)&&out&&*out){
            if(wrap(*out,main))event(main?"game_main_hook_ready":"game_start_hook_ready",0,0);
            if(entry)event("ffxi_import_hooks",0,hook(GetModuleHandleW(L"FFXi.dll"),FALSE));
            if(main)event("main_import_hooks",0,hook(GetModuleHandleW(L"FFXiMain.dll"),TRUE));
        }
    }
    SetLastError(error);return hr;
}
typedef struct {const char *name;void *wrapper;void **original;} Api;
static Api apis[]={
    {"CoCreateInstance",(void*)create,(void**)&real_create},
    {"CreateFileA",(void*)observed_open,(void**)&real_open},
    {"ReadFile",(void*)observed_read,(void**)&real_read},
    {"GetFileSize",(void*)observed_size,(void**)&real_size},
    {"CloseHandle",(void*)observed_close,(void**)&real_close},
    {"LoadLibraryA",(void*)observed_load,(void**)&real_load},
    {"GetVersionExA",(void*)observed_version,(void**)&real_version},
    {"RegisterClassA",(void*)observed_register,(void**)&real_register},
    {"CreateWindowExA",(void*)observed_window,(void**)&real_window},
    {"Direct3DCreate8",(void*)observed_d3d8,(void**)&real_d3d8}
};
static unsigned hook(HMODULE module,BOOL main){
    if(!module)return 0;
    BYTE *base=(BYTE*)module;IMAGE_DOS_HEADER *dos=(IMAGE_DOS_HEADER*)base;
    if(dos->e_magic!=IMAGE_DOS_SIGNATURE)return 0;
    IMAGE_NT_HEADERS32 *nt=(IMAGE_NT_HEADERS32*)(base+dos->e_lfanew);
    if(nt->Signature!=IMAGE_NT_SIGNATURE||nt->OptionalHeader.Magic!=IMAGE_NT_OPTIONAL_HDR32_MAGIC)return 0;
    DWORD size=nt->OptionalHeader.SizeOfImage;
    IMAGE_DATA_DIRECTORY dir=nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if(!dir.VirtualAddress||dir.VirtualAddress>=size||dir.Size>size-dir.VirtualAddress)return 0;
    unsigned count=0;
    for(DWORD n=0;n+sizeof(IMAGE_IMPORT_DESCRIPTOR)<=dir.Size;n+=sizeof(IMAGE_IMPORT_DESCRIPTOR)){
        IMAGE_IMPORT_DESCRIPTOR *d=(IMAGE_IMPORT_DESCRIPTOR*)(base+dir.VirtualAddress+n);
        if(!d->Name)break;
        if(!d->OriginalFirstThunk||!d->FirstThunk)continue;
        for(DWORD j=0;j<size/4;j++){
            ULONGLONG a=(ULONGLONG)d->OriginalFirstThunk+j*4,b=(ULONGLONG)d->FirstThunk+j*4;
            if(a+4>size||b+4>size)break;
            DWORD rva=*(DWORD*)(base+(DWORD)a);if(!rva)break;
            if(IMAGE_SNAP_BY_ORDINAL32(rva)||rva>=size||size-rva<3)continue;
            Api *api=NULL;
            for(unsigned k=0;k<(main?sizeof(apis)/sizeof(apis[0]):1);k++){
                size_t length=strlen(apis[k].name)+1;
                if(length<=size-rva-2&&!memcmp(base+rva+2,apis[k].name,length)){api=&apis[k];break;}
            }
            if(!api)continue;
            void **target=(void**)(base+(DWORD)b);if(*target==api->wrapper)continue;
            DWORD old;
            if(!VirtualProtect(target,sizeof(*target),PAGE_READWRITE,&old)){event("observer_failed",GetLastError(),0);continue;}
            if(!*api->original)*api->original=*target;
            InterlockedExchangePointer(target,api->wrapper);
            DWORD unused;if(!VirtualProtect(target,sizeof(*target),old,&unused))event("observer_failed",GetLastError(),0);
            count++;
        }
    }
    return count;
}
__declspec(dllexport) void WINAPI LsbStartupTrace(void){}
/* App-owned fixture entry; real launches attach through Direct3DCreate8. */
__declspec(dllexport) void WINAPI LsbGraphicsTrace(IDirect3D8 *api){gt_attach(api);}
BOOL WINAPI DllMain(HINSTANCE dll,DWORD reason,LPVOID reserved){
    (void)reserved;
    if(reason==DLL_PROCESS_ATTACH){DisableThreadLibraryCalls(dll);event("observer_loaded",0,0);event("loader_import_hooks",0,hook(GetModuleHandleW(NULL),FALSE));}
    return TRUE;
}
