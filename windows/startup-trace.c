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
typedef struct {void *object;void *table[7];Start original;BOOL main;} Slot;
static Slot slots[16];
static LONG slot_count;

/* Only fixed labels and numeric fields. Write directly to the private stderr
 * pipe, not the console xiloader hides, and preserve the caller's LastError. */
static void event(const char *name,DWORD code,DWORD detail){
    DWORD saved=GetLastError(),written;char row[160];
    int n=snprintf(row,sizeof(row),"lsb-startup-v1 %s %08lx %08lx %08lx %08lx\n",name,
        (unsigned long)GetCurrentProcessId(),(unsigned long)GetCurrentThreadId(),(unsigned long)code,(unsigned long)detail);
    if(n>0&&n<(int)sizeof(row))WriteFile(GetStdHandle(STD_ERROR_HANDLE),row,(DWORD)n,&written,NULL);
    SetLastError(saved);
}
static HRESULT WINAPI start(void *self,IUnknown *pol,void *message){
    Slot *slot=NULL;
    for(int i=0;i<16;i++)if(slots[i].object==self&&slots[i].table==*(void***)self){slot=&slots[i];break;}
    if(!slot){event("observer_failed",ERROR_INVALID_DATA,0);return E_UNEXPECTED;}
    event(slot->main?"game_main_enter":"game_start_enter",0,0);
    ULONGLONG before=GetTickCount64();
    HRESULT hr=slot->original(self,pol,message);
    DWORD error=GetLastError();ULONGLONG duration=GetTickCount64()-before;
    event(slot->main?"game_main_return":"game_start_return",(DWORD)hr,duration>0xffffffffULL?0xffffffff:(DWORD)duration);
    SetLastError(error);return hr;
}
static BOOL wrap(void *object,BOOL main){
    for(int n=0;n<16;n++)if(slots[n].object==object&&slots[n].table==*(void***)object)return TRUE;
    LONG i=InterlockedIncrement(&slot_count)-1;
    if(i<0||i>=16){event("observer_failed",ERROR_NOT_ENOUGH_MEMORY,0);return FALSE;}
    Slot *slot=&slots[i];void **table=*(void***)object;
    slot->original=(Start)table[3];slot->main=main;
    memcpy(slot->table,table,(main?7:6)*sizeof(void*));slot->table[3]=(void*)start;
    slot->object=object;
    /* Object identity and all other interface slots remain unchanged. Keep the
     * tiny replacement table until process exit; Release may destroy the object. */
    InterlockedExchangePointer((void *volatile*)object,slot->table);
    return TRUE;
}
static unsigned hook(HMODULE module);
static HRESULT WINAPI create(REFCLSID cls,IUnknown *outer,DWORD context,REFIID iid,void **out){
    BOOL entry=IsEqualGUID(cls,&entry_class)&&IsEqualGUID(iid,&entry_iid);
    BOOL main=IsEqualGUID(cls,&main_class)&&IsEqualGUID(iid,&main_iid);
    if(entry||main)event(main?"game_main_com_enter":"ffxi_com_enter",0,0);
    HRESULT hr=real_create(cls,outer,context,iid,out);DWORD error=GetLastError();
    if(entry||main){
        event(main?"game_main_com_return":"ffxi_com_return",(DWORD)hr,SUCCEEDED(hr)&&out&&*out?1:0);
        if(SUCCEEDED(hr)&&out&&*out){
            if(wrap(*out,main))event(main?"game_main_hook_ready":"game_start_hook_ready",0,0);
            if(entry)event("ffxi_import_hooks",0,hook(GetModuleHandleW(L"FFXi.dll")));
        }
    }
    SetLastError(error);return hr;
}
static unsigned hook(HMODULE module){
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
            if(IMAGE_SNAP_BY_ORDINAL32(rva)||rva>=size||size-rva<19)continue;
            if(memcmp(base+rva+2,"CoCreateInstance",17))continue;
            void **target=(void**)(base+(DWORD)b);if(*target==(void*)create)continue;
            DWORD old;
            if(!VirtualProtect(target,sizeof(*target),PAGE_READWRITE,&old)){event("observer_failed",GetLastError(),0);continue;}
            if(!real_create)real_create=(CreateInstance)*target;
            InterlockedExchangePointer(target,(void*)create);
            DWORD unused;if(!VirtualProtect(target,sizeof(*target),old,&unused))event("observer_failed",GetLastError(),0);
            count++;
        }
    }
    return count;
}
__declspec(dllexport) void WINAPI LsbStartupTrace(void){}
BOOL WINAPI DllMain(HINSTANCE dll,DWORD reason,LPVOID reserved){
    (void)reserved;
    if(reason==DLL_PROCESS_ATTACH){DisableThreadLibraryCalls(dll);event("observer_loaded",0,0);event("loader_import_hooks",0,hook(GetModuleHandleW(NULL)));}
    return TRUE;
}
