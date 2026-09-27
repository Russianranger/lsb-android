/* Finite, credential-free PE32 I/O and clock sample. Only app-owned fixtures. */
#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#define _WIN32_WINNT 0x0600
#include <windows.h>
#include <stdio.h>
#include <stdint.h>
#include <wchar.h>

#ifdef LSB_REPAIR_IO_TEST_MODE
#define BASE L"repair-io"
#define RESULT L"repair-io-result.json"
#define TEMP L"repair-io-result.new"
#else
#define BASE L"Z:\\session\\repair-io"
#define RESULT L"Z:\\session\\repair-io-result.json"
#define TEMP L"Z:\\session\\repair-io-result.new"
#endif
#ifndef LSB_REPAIR_IO_BUDGET_MS
#define LSB_REPAIR_IO_BUDGET_MS 10000
#endif

typedef struct {const char *name;DWORD operations,error,cpu_error;ULONGLONG bytes,qpc_us,tick_ms,user_us,kernel_us;BOOL timed_out;} Phase;
static Phase phases[8];static unsigned count;static LARGE_INTEGER frequency;
static ULONGLONG started;static BYTE buffer[65536];static uint32_t crc_table[256];
static ULONGLONG ft(FILETIME t){ULARGE_INTEGER u;u.LowPart=t.dwLowDateTime;u.HighPart=t.dwHighDateTime;return u.QuadPart;}
static BOOL cpu(ULONGLONG *user,ULONGLONG *kernel){FILETIME a,b,c,d;if(!GetProcessTimes(GetCurrentProcess(),&a,&b,&c,&d))return FALSE;*user=ft(d);*kernel=ft(c);return TRUE;}
static BOOL room(Phase *p){if(GetTickCount64()-started<LSB_REPAIR_IO_BUDGET_MS)return TRUE;p->timed_out=TRUE;return FALSE;}
static BOOL publish(const char *state){
    char data[4096];int used=snprintf(data,sizeof(data),"{\"format\":1,\"bits\":32,\"state\":\"%s\",\"qpc_frequency\":%llu,\"elapsed_ms\":%llu,\"phases\":[",state,(unsigned long long)frequency.QuadPart,(unsigned long long)(GetTickCount64()-started));
    for(unsigned i=0;i<count;i++){
        Phase *p=&phases[i];int n=snprintf(data+used,sizeof(data)-used,"%s{\"name\":\"%s\",\"operations\":%lu,\"bytes\":%llu,\"qpc_us\":%llu,\"tick_ms\":%llu,\"cpu_user_us\":%llu,\"cpu_kernel_us\":%llu,\"cpu_error\":%lu,\"error\":%lu,\"timed_out\":%s}",i?",":"",p->name,(unsigned long)p->operations,(unsigned long long)p->bytes,(unsigned long long)p->qpc_us,(unsigned long long)p->tick_ms,(unsigned long long)p->user_us,(unsigned long long)p->kernel_us,(unsigned long)p->cpu_error,(unsigned long)p->error,p->timed_out?"true":"false");
        if(n<0||(size_t)n>=sizeof(data)-used)return FALSE;used+=n;
    }
    if(used+4>=(int)sizeof(data))return FALSE;data[used++]=']';data[used++]='}';data[used++]='\n';
    HANDLE h=CreateFileW(TEMP,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);if(h==INVALID_HANDLE_VALUE)return FALSE;
    DWORD written=0;BOOL ok=WriteFile(h,data,(DWORD)used,&written,NULL)&&written==(DWORD)used;
    if(!CloseHandle(h))ok=FALSE;return ok&&MoveFileExW(TEMP,RESULT,MOVEFILE_REPLACE_EXISTING);
}
static BOOL attributes(const WCHAR *path,DWORD size,Phase *p){
    WIN32_FILE_ATTRIBUTE_DATA a;if(!GetFileAttributesExW(path,GetFileExInfoStandard,&a)){p->error=GetLastError();return FALSE;}
    if((a.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))||a.nFileSizeHigh||a.nFileSizeLow!=size){p->error=ERROR_INVALID_DATA;return FALSE;}return TRUE;
}
static BOOL read_file(const WCHAR *path,DWORD size,Phase *p){
    HANDLE h=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,NULL);
    if(h==INVALID_HANDLE_VALUE){p->error=GetLastError();return FALSE;}
    BY_HANDLE_FILE_INFORMATION a;BOOL ok=GetFileInformationByHandle(h,&a);
    if(!ok)p->error=GetLastError();
    else if((a.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))||a.nFileSizeHigh||a.nFileSizeLow!=size){ok=FALSE;p->error=ERROR_INVALID_DATA;}
    DWORD total=0;uint64_t sum=0;
    while(ok&&total<size&&room(p)){
        DWORD got=0,wanted=size-total;if(wanted>sizeof(buffer))wanted=sizeof(buffer);
        if(!ReadFile(h,buffer,wanted,&got,NULL)){p->error=GetLastError();ok=FALSE;break;}
        if(got!=wanted){p->error=ERROR_HANDLE_EOF;ok=FALSE;break;}
        for(DWORD j=0;j<got;j++)sum+=buffer[j];total+=got;p->bytes+=got;
    }
    if(!CloseHandle(h)&&ok){p->error=GetLastError();ok=FALSE;}
    if(ok&&!p->timed_out&&sum!=(uint64_t)size/256*32640){p->error=ERROR_CRC;ok=FALSE;}
    return ok&&!p->timed_out;
}
static void perform(unsigned which,Phase *p){
    WCHAR path[128];
    if(which==0){
        WIN32_FIND_DATAW a;HANDLE h=FindFirstFileW(BASE L"\\case\\*",&a);if(h==INVALID_HANDLE_VALUE){p->error=GetLastError();return;}
        do{if(!room(p))break;if(a.cFileName[0]!=L'.')p->operations++;}while(FindNextFileW(h,&a));
        DWORD e=GetLastError();FindClose(h);if(!p->timed_out&&e!=ERROR_NO_MORE_FILES)p->error=e;
        if(!p->error&&!p->timed_out&&p->operations!=256)p->error=ERROR_INVALID_DATA;
    }else if(which==1||which==2){
        unsigned n=which==1?128:256;
        for(unsigned i=0;i<n&&room(p);i++){
            if(which==1)swprintf(path,128,BASE L"\\f%02u.bin",i%32);
            else swprintf(path,128,BASE L"\\case\\entry%03u.dat",i);
            if(!attributes(path,which==1?4096:0,p))break;p->operations++;
        }
    }else if(which==3||which==4){
        unsigned n=which==3?128:4;
        for(unsigned i=0;i<n&&room(p);i++){
            if(which==3)swprintf(path,128,BASE L"\\f%02u.bin",i%32);else wcscpy(path,BASE L"\\large.bin");
            if(!read_file(path,which==3?4096:1048576,p))break;p->operations++;
        }
    }else if(which==5){for(unsigned i=0;i<8&&room(p);i++){Sleep(10);p->operations++;}}
    else if(which==6){
        HANDLE event=CreateEventW(NULL,TRUE,TRUE,NULL);if(!event){p->error=GetLastError();return;}
        for(unsigned i=0;i<1024&&room(p);i++){DWORD r=WaitForSingleObject(event,0);if(r!=WAIT_OBJECT_0){p->error=r==WAIT_FAILED?GetLastError():ERROR_INVALID_DATA;break;}p->operations++;}
        CloseHandle(event);
    }else{
        for(unsigned i=0;i<sizeof(buffer);i++)buffer[i]=(BYTE)i;
        for(unsigned i=0;i<64&&room(p);i++){
            uint32_t crc=0xffffffffu;for(unsigned j=0;j<sizeof(buffer);j++)crc=crc_table[(crc^buffer[j])&255]^(crc>>8);
            if((crc^0xffffffffu)!=0xb11de6a1u){p->error=ERROR_CRC;break;}p->operations++;p->bytes+=sizeof(buffer);
        }
    }
}
int wmain(int argc,WCHAR **argv){
    (void)argv;if(argc!=1)return 80;
    if(!QueryPerformanceFrequency(&frequency)||frequency.QuadPart<=0)return 81;
    DWORD attr=GetFileAttributesW(BASE);if(attr==INVALID_FILE_ATTRIBUTES||!(attr&FILE_ATTRIBUTE_DIRECTORY)||(attr&FILE_ATTRIBUTE_REPARSE_POINT))return 82;
    attr=GetFileAttributesW(BASE L"\\case");if(attr==INVALID_FILE_ATTRIBUTES||!(attr&FILE_ATTRIBUTE_DIRECTORY)||(attr&FILE_ATTRIBUTE_REPARSE_POINT))return 82;
    for(unsigned i=0;i<256;i++){uint32_t c=i;for(unsigned j=0;j<8;j++)c=(c>>1)^((c&1)?0xedb88320u:0);crc_table[i]=c;}
    const char *names[]={"enumerate","metadata","case_lookup","small_reads","sequential","sleep","event_wait","cpu_crc"};
    started=GetTickCount64();if(!publish("running"))return 90;
    for(unsigned i=0;i<8;i++){
        Phase *p=&phases[count++];p->name=names[i];LARGE_INTEGER before,after;ULONGLONG tick=GetTickCount64(),u0=0,k0=0,u1=0,k1=0;
        if(!cpu(&u0,&k0))p->cpu_error=GetLastError();QueryPerformanceCounter(&before);
        if(room(p))perform(i,p);QueryPerformanceCounter(&after);
        p->tick_ms=GetTickCount64()-tick;p->qpc_us=(ULONGLONG)((after.QuadPart-before.QuadPart)*1000000/frequency.QuadPart);
        if(!cpu(&u1,&k1))p->cpu_error=GetLastError();else if(!p->cpu_error){p->user_us=(u1-u0)/10;p->kernel_us=(k1-k0)/10;}
        BOOL failed=p->error||p->timed_out;if(!publish(failed?(p->timed_out?"timed_out":"failed"):(i==7?"completed":"running")))return 90;
        if(failed)return 1;
    }
    return 0;
}
