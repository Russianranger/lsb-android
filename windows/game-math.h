/* One bounded diagnostic of already-loaded client math, on a disposable thread.
 * Only exact file/PE identities AND complete pure-kernel hashes permit calls.
 * Never initialize dispatch, replace a game routine, or export client bytes.
 * Unknown clients/routines are observed as unsupported and never invoked. */
#include <wincrypt.h>
#include <cpuid.h>
#include "math-oracle.h"
static BYTE *gm_base;
static BOOL gm_prepared,gm_checked;
static unsigned gm_attempts;
static MathJob gm_job;
static HANDLE gm_thread;
static const char gm_file_sha[]="d528142a9bfb7767b4d4574dde531ea87a673670c81892f1eab37563b82c0235";
typedef struct {DWORD rva,size;const char *sha;} GameKernel;
static const GameKernel gm_kernels[2][3]={
    {{0x2f210b,0x69,"f0aedbc843d5238c7e83d1e7469715ac8befb94e989f78280bbfea95da5346ac"},
     {0x2f21a9,0x8b,"7cb3e78dce201353fae9a1ead4f6393fc05a7b862f199e37b7ed2d7c47de6465"},
     {0x2f243b,0xe3,"7045ca54660b7851e8281e7e4e478343850b97b2f370d8d3b08cfe34e15b2463"}},
    {{0x2f6930,0x40,"7c46c104985501afd61b58c8b39c16558b344430d34f706f754112862e23f9aa"},
     {0x2f8640,0xb1,"e52b2b4d00cec7741f21321a1a706a50fbd631aad748e3907a95aaf1a744368e"},
     {0x2f88c0,0x146,"63512349cc3bd37f37668d4d47d8fabe516e1ceb56fc824ee2b472a841bbe2db"}}
};
static BOOL gm_hash_equal(HCRYPTHASH hash,const char *expected){
    BYTE bytes[32];DWORD size=sizeof(bytes);char hex[65];static const char digits[]="0123456789abcdef";
    if(!CryptGetHashParam(hash,HP_HASHVAL,bytes,&size,0)||size!=32)return FALSE;
    for(unsigned i=0;i<32;i++){hex[i*2]=digits[bytes[i]>>4];hex[i*2+1]=digits[bytes[i]&15];}hex[64]=0;
    return !strcmp(hex,expected);
}
static BOOL gm_hash_start(HCRYPTPROV *provider,HCRYPTHASH *hash){
    *provider=0;*hash=0;
    if(!CryptAcquireContextW(provider,NULL,MS_ENH_RSA_AES_PROV_W,PROV_RSA_AES,CRYPT_VERIFYCONTEXT))return FALSE;
    if(CryptCreateHash(*provider,CALG_SHA_256,0,0,hash))return TRUE;
    CryptReleaseContext(*provider,0);*provider=0;return FALSE;
}
static BOOL gm_read(const BYTE *address,void *out,SIZE_T size){
    SIZE_T copied=0;return ReadProcessMemory(GetCurrentProcess(),address,out,size,&copied)&&copied==size;
}
static BOOL gm_image(BYTE *base){
    IMAGE_DOS_HEADER dos;IMAGE_NT_HEADERS32 nt;
    if(!gm_read(base,&dos,sizeof(dos))||dos.e_magic!=IMAGE_DOS_SIGNATURE||dos.e_lfanew<0||dos.e_lfanew>4096)return FALSE;
    if(!gm_read(base+dos.e_lfanew,&nt,sizeof(nt)))return FALSE;
    return nt.Signature==IMAGE_NT_SIGNATURE&&nt.FileHeader.Machine==IMAGE_FILE_MACHINE_I386&&
        nt.FileHeader.TimeDateStamp==0x6930e372&&nt.OptionalHeader.Magic==IMAGE_NT_OPTIONAL_HDR32_MAGIC&&
        nt.OptionalHeader.SizeOfImage==0xbdf000&&nt.OptionalHeader.AddressOfEntryPoint==0xbae8a0;
}
static BOOL gm_file(HMODULE module){
    WCHAR path[1024];DWORD n=GetModuleFileNameW(module,path,1024);
    if(!n||n>=1024)return FALSE;
    HANDLE file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,0,NULL);
    if(file==INVALID_HANDLE_VALUE)return FALSE;
    LARGE_INTEGER length;HCRYPTPROV provider=0;HCRYPTHASH hash=0;BOOL ok=FALSE;
    if(!GetFileSizeEx(file,&length)||length.QuadPart!=2896464||!gm_hash_start(&provider,&hash))goto done;
    BYTE buffer[16384];DWORD total=0,got;
    while(total<2896464){
        DWORD wanted=2896464-total;if(wanted>sizeof(buffer))wanted=sizeof(buffer);
        if(!ReadFile(file,buffer,wanted,&got,NULL)||got!=wanted||!CryptHashData(hash,buffer,got,0))goto done;
        total+=got;
    }
    ok=gm_hash_equal(hash,gm_file_sha);
done:
    if(hash)CryptDestroyHash(hash);if(provider)CryptReleaseContext(provider,0);CloseHandle(file);return ok;
}
static void gm_prepare(void){
    if(gm_prepared)return;gm_prepared=TRUE;
    DWORD saved=GetLastError();HMODULE module=GetModuleHandleW(L"FFXiMain.dll");
    if(module&&gm_image((BYTE*)module)&&gm_file(module))gm_base=(BYTE*)module;
    event("main_math_profile",gm_base?0:1,1);SetLastError(saved);
}
static unsigned gm_kernel(DWORD pointer,unsigned slot){
    if(!gm_base||slot>=3||pointer<(DWORD)(uintptr_t)gm_base)return 0;
    DWORD rva=pointer-(DWORD)(uintptr_t)gm_base;
    for(unsigned path=0;path<2;path++){
        const GameKernel *k=&gm_kernels[path][slot];if(rva!=k->rva)continue;
        BYTE code[512];MEMORY_BASIC_INFORMATION region;
        if(k->size>sizeof(code)||!VirtualQuery(gm_base+rva,&region,sizeof(region))||
            region.AllocationBase!=gm_base||region.State!=MEM_COMMIT||region.Type!=MEM_IMAGE||
            (region.Protect&(PAGE_GUARD|PAGE_NOACCESS))||
            !(region.Protect&(PAGE_EXECUTE_READ|PAGE_EXECUTE_READWRITE|PAGE_EXECUTE_WRITECOPY))||
            region.RegionSize<(SIZE_T)(gm_base+rva-(BYTE*)region.BaseAddress)+k->size||
            !gm_read(gm_base+rva,code,k->size))return 0;
        HCRYPTPROV provider;HCRYPTHASH hash;if(!gm_hash_start(&provider,&hash))return 0;
        BOOL ok=CryptHashData(hash,code,k->size,0)&&gm_hash_equal(hash,k->sha);
        CryptDestroyHash(hash);CryptReleaseContext(provider,0);return ok?path+1:0;
    }
    return 0;
}
static void gm_collect(BOOL final){
    if(!gm_thread)return;
    DWORD status=WaitForSingleObject(gm_thread,0);
    if(status==WAIT_TIMEOUT){
        if(final){event("main_math_pending",1,0);CloseHandle(gm_thread);gm_thread=NULL;}
        return;
    }
    DWORD code=1;
    if(status!=WAIT_OBJECT_0||!GetExitCodeThread(gm_thread,&code)||code)event("main_math_unavailable",2,0);
    else{
        MathResult *r=&gm_job.result;
        event("main_math_controls",gm_job.observed_cw,gm_job.observed_mxcsr);
        event("main_math_result",r->failures,r->samples);event("main_math_returns",r->returns,0);
        if(r->failures){event("main_math_case",r->first_case,0);event("main_math_expected",r->expected,0);event("main_math_actual",r->actual,0);}
    }
    CloseHandle(gm_thread);gm_thread=NULL;
}
static void gm_observe(BOOL final){
    DWORD saved=GetLastError();gm_prepare();
    gm_collect(final);
    if(!gm_base||(!final&&(gm_checked||gm_attempts>=2))){SetLastError(saved);return;}
    DWORD pointers[4],mode;unsigned paths[3];
    if(!gm_read(gm_base+0x3ca2c0,pointers,sizeof(pointers))||!gm_read(gm_base+0x3cab04,&mode,4)){
        event("main_math_unavailable",1,0);SetLastError(saved);return;
    }
    paths[0]=gm_kernel(pointers[0],0);paths[1]=gm_kernel(pointers[1],1);paths[2]=gm_kernel(pointers[3],2);
    DWORD selection=paths[0]|(paths[1]<<4)|(paths[2]<<8);
    event(final?"main_math_final":"main_math_dispatch",mode<=3||mode==0xffff?mode:0xfffe,selection);
    if(final){SetLastError(saved);return;}
    gm_attempts++;
    unsigned a=0,b=0,c=0,d=0;DWORD features=0;
    if(__get_cpuid(0x80000001,&a,&b,&c,&d)&&(d&0x80000000u))features|=1;
    if(IsProcessorFeaturePresent(PF_3DNOW_INSTRUCTIONS_AVAILABLE))features|=2;
    if(__get_cpuid(1,&a,&b,&c,&d)&&(d&bit_SSE2))features|=4;
    if(IsProcessorFeaturePresent(PF_XMMI64_INSTRUCTIONS_AVAILABLE))features|=8;
    event("main_math_cpu",0,features);
    if(!paths[0]||!paths[1]||!paths[2]||(features&12)!=12){event("main_math_skipped",1,gm_attempts);SetLastError(saved);return;}
    gm_checked=TRUE;
    MathRoutine routines[3]={(MathRoutine)(uintptr_t)pointers[0],(MathRoutine)(uintptr_t)pointers[1],(MathRoutine)(uintptr_t)pointers[3]};
    event("main_math_begin",0,192);gm_thread=math_start(&gm_job,routines,(HMODULE)gm_base);
    if(!gm_thread)event("main_math_unavailable",3,0);
    SetLastError(saved);
}
