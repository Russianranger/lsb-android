/* App-owned finite PE32 proof: native Wine host must be ARM64, not emulated AMD64. */
#include <windows.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <cpuid.h>

static int cpu_features(int child){
    unsigned a=0,b=0,c=0,d=0,three=0,sse2=0;
    if(__get_cpuid(1,&a,&b,&c,&d))sse2=!!(d&bit_SSE2);
    if(__get_cpuid(0x80000001,&a,&b,&c,&d))three=!!(d&0x80000000u);
    unsigned api_three=!!IsProcessorFeaturePresent(PF_3DNOW_INSTRUCTIONS_AVAILABLE);
    unsigned api_sse2=!!IsProcessorFeaturePresent(PF_XMMI64_INSTRUCTIONS_AVAILABLE);
    int passed=three==api_three&&sse2&&api_sse2;
    printf("LSB_FEX_FEATURES child=%d three_cpuid=%u three_api=%u sse2_cpuid=%u sse2_api=%u %s\n",
        child,three,api_three,sse2,api_sse2,passed?"PASS":"FAIL");
    return passed?0:33;
}

/* Only fixed runtime controls; never enumerate the environment or print values. */
static const char *env_keys[]={"FEX_X87REDUCEDPRECISION","FEX_X87STRICTREDUCEDPRECISION",
    "DXVK_HUD","DXVK_CONFIG","MESA_VK_WSI_DEBUG","TU_DEBUG","WINEDLLOVERRIDES"};
static uint64_t environment_hash(void) {
    uint64_t hash=14695981039346656037ULL;
    for(unsigned i=0;i<sizeof(env_keys)/sizeof(env_keys[0]);i++) {
        char value[4096]={0};DWORD n=GetEnvironmentVariableA(env_keys[i],value,sizeof(value));
        if(n>=sizeof(value))return 0;
        const char *key=env_keys[i];while(*key){hash^=(unsigned char)*key++;hash*=1099511628211ULL;}
        hash^='=';hash*=1099511628211ULL;
        for(DWORD j=0;j<=n;j++){hash^=(unsigned char)value[j];hash*=1099511628211ULL;}
    }
    return hash;
}
static int arithmetic(int mode) {
    unsigned short saved,cw=0x037f;double large=9007199254740992.0,delta=0,quarter=.25,sum=0;
    __asm__ volatile("fnstcw %0":"=m"(saved));
    __asm__ volatile("fldcw %0"::"m"(cw));
    /* Detect actual translated precision, not merely an inherited variable. */
    __asm__ volatile("fldl %1; fld1; faddp; fsubl %1; fstpl %0":"=m"(delta):"m"(large):"st");
    LARGE_INTEGER frequency,before,after,sleep_before,sleep_after;
    if(!QueryPerformanceFrequency(&frequency)||frequency.QuadPart<=0)return 30;
    QueryPerformanceCounter(&before);
    for(unsigned i=0;i<100000;i++)
        __asm__ volatile("fldl %0; faddl %1; fstpl %0":"+m"(sum):"m"(quarter):"st");
    QueryPerformanceCounter(&after);
    __asm__ volatile("fldcw %0"::"m"(saved));
    if(delta!=(mode?0.0:1.0)||sum!=25000.0)return 31;
    QueryPerformanceCounter(&sleep_before);Sleep(20);QueryPerformanceCounter(&sleep_after);
    if(after.QuadPart<before.QuadPart||sleep_after.QuadPart<=sleep_before.QuadPart)return 32;
    printf("LSB_FEX_ARITH mode=%d iterations=100000 elapsed_us=%llu qpc_frequency=%llu sleep_us=%llu PASS\n",mode,
        (unsigned long long)((after.QuadPart-before.QuadPart)*1000000/frequency.QuadPart),
        (unsigned long long)frequency.QuadPart,
        (unsigned long long)((sleep_after.QuadPart-sleep_before.QuadPart)*1000000/frequency.QuadPart));
    return 0;
}
int wmain(int argc,wchar_t **argv) {
    if(argc!=3&&argc!=4)return 26;
    int mode=!wcscmp(argv[1],L"1");
    if(!mode&&wcscmp(argv[1],L"0"))return 26;
    uint64_t expected=wcstoull(argv[2],NULL,16),actual=environment_hash();
    if(!actual||actual!=expected){printf("LSB_FEX_ENV mismatch expected=%016llx actual=%016llx\n",(unsigned long long)expected,(unsigned long long)actual);return 27;}
    typedef BOOL (WINAPI *machine_fn)(HANDLE,USHORT *,USHORT *);
    machine_fn machine=(machine_fn)(void *)GetProcAddress(GetModuleHandleW(L"kernel32.dll"),"IsWow64Process2");
    USHORT process=0,native=0;
    if(sizeof(void *)!=4||!machine||!machine(GetCurrentProcess(),&process,&native)||process!=0x014c||native!=0xaa64)return 21;
    HKEY key;unsigned long long isar0=0,isar1=0,ctr=0;DWORD size=8,type=0;
    if(RegOpenKeyExW(HKEY_LOCAL_MACHINE,L"HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",0,KEY_READ|KEY_WOW64_64KEY,&key))return 22;
    LONG result=RegQueryValueExW(key,L"CP 4030",NULL,&type,(BYTE *)&isar0,&size);
    if(result||type!=REG_QWORD||size!=8||!((isar0>>16)&15)){RegCloseKey(key);return 23;}
    size=8;result=RegQueryValueExW(key,L"CP 4031",NULL,&type,(BYTE *)&isar1,&size);
    if(result||type!=REG_QWORD||size!=8){RegCloseKey(key);return 24;}
    size=8;result=RegQueryValueExW(key,L"CP 5801",NULL,&type,(BYTE *)&ctr,&size);RegCloseKey(key);
    if(result||type!=REG_QWORD||size!=8)return 25;
    printf("LSB_FEX_HOST isar0=%016llx isar1=%016llx ctr=%016llx\n",isar0,isar1,ctr);
    printf("LSB_FEX_CHECK bits=32 process=%04x native=%04x PASS\n",process,native);
    int feature_result=cpu_features(argc==4);if(feature_result)return feature_result;
    if(argc==4){
        printf("LSB_FEX_ENV child=1 hash=%016llx PASS\n",(unsigned long long)actual);
        return arithmetic(mode);
    }
    /* Exercise the same CreateProcess/inherited environment boundary as the loader. */
    wchar_t command[160];swprintf(command,160,L"\"P:\\fex-check.exe\" %d %016llx --child",mode,(unsigned long long)expected);
    STARTUPINFOW startup={0};PROCESS_INFORMATION child={0};startup.cb=sizeof(startup);
    fflush(stdout);
    if(!CreateProcessW(L"P:\\fex-check.exe",command,NULL,NULL,TRUE,0,NULL,NULL,&startup,&child))return 28;
    DWORD code=29;
    if(WaitForSingleObject(child.hProcess,30000)==WAIT_OBJECT_0)GetExitCodeProcess(child.hProcess,&code);
    else TerminateProcess(child.hProcess,29);
    CloseHandle(child.hThread);CloseHandle(child.hProcess);return code;
}
