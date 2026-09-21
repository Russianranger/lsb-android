#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <stdio.h>
#include <wchar.h>
#include <ctype.h>

/* Credentials enter only on stdin. No command interpreter, credentials file or
 * credential-bearing Unix argv is used. The imported Windows process necessarily
 * receives its own CLI in memory. Receipts contain only fixed fields/codes. */
static void quoted(FILE *f,const WCHAR *s){
    char b[4096];int n=WideCharToMultiByte(CP_UTF8,0,s,-1,b,sizeof(b),NULL,NULL);
    fputc('"',f);for(int i=0;i<n-1;i++){unsigned char c=b[i];if(c=='"'||c=='\\')fputc('\\',f);if(c<32)fprintf(f,"\\u%04x",c);else fputc(c,f);}fputc('"',f);
}
static BOOL directory(const WCHAR *path,WCHAR *out){
    WCHAR full[1024];DWORD n=GetFullPathNameW(path,1024,full,NULL);
    if(!n||n>=1024||wcsncmp(path,L"D:\\",3)||wcschr(path,L'"')||wcscmp(path,full))return FALSE;
    wcscpy(out,path);WCHAR *end=wcsrchr(out,L'\\');if(!end)return FALSE;*end=0;return SetCurrentDirectoryW(out)&&SetDllDirectoryW(out);
}
static int check(int argc,WCHAR **argv){
    WCHAR dir[1024];if(argc<4||!directory(argv[2],dir))return 80;
    FILE *f=_wfopen(L"Z:\\session\\loader-check.new",L"wb");if(!f)return 81;
    fputs("{\"format\":1,\"bits\":32,\"check_policy\":\"load_only\",\"dependencies\":[",f);BOOL passed=TRUE;
    for(int i=3;i<argc;i++){
        for(const WCHAR *p=argv[i];*p;p++)if(!((*p>=L'a'&&*p<=L'z')||(*p>=L'A'&&*p<=L'Z')||(*p>=L'0'&&*p<=L'9')||*p==L'.'||*p==L'_'||*p==L'-')){fclose(f);return 82;}
        HMODULE dll=LoadLibraryW(argv[i]);DWORD error=dll?0:GetLastError();WCHAR loaded[1024]={0};
        if(dll)GetModuleFileNameW(dll,loaded,1024);else passed=FALSE;
        if(i!=3)fputc(',',f);fputs("{\"name\":",f);quoted(f,argv[i]);
        fprintf(f,",\"ok\":%s,\"win32_error\":%lu,\"loaded_path\":",dll?"true":"false",(unsigned long)error);quoted(f,loaded);fputc('}',f);
        /* Keep references until this disposable process is terminated. Unloading
         * each import exercises a different lifetime/order than an executable's
         * import table, including Wine/Box64 CRT and crypto detach callbacks. */
    }
    fprintf(f,"],\"ok\":%s}\n",passed?"true":"false");BOOL written=!ferror(f);
    if(fclose(f))written=FALSE;if(!written)return 81;
    if(!MoveFileExW(L"Z:\\session\\loader-check.new",L"Z:\\session\\loader-check.json",MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))return 83;
    return passed?0:1;
}
static BOOL receipt(const char *phase,DWORD error,DWORD code){
    FILE *f=_wfopen(L"Z:\\session\\loader-process.new",L"wb");if(!f)return FALSE;
    fprintf(f,"{\"format\":1,\"bits\":32,\"phase\":\"%s\",\"win32_error\":%lu,\"child_exit\":%lu}\n",phase,(unsigned long)error,(unsigned long)code);fclose(f);
    return MoveFileExW(L"Z:\\session\\loader-process.new",L"Z:\\session\\loader-process.json",MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
}
/* MS CRT argument quoting, including quotes and trailing backslashes. */
static void arg(WCHAR *command,const WCHAR *value){
    WCHAR *out=command+wcslen(command);*out++=L' ';*out++=L'"';
    while(*value){unsigned slashes=0;while(*value==L'\\'){slashes++;value++;}
        unsigned count=(*value==L'"'||!*value)?slashes*2:slashes;
        while(count--)*out++=L'\\';if(*value==L'"')*out++=L'\\';if(*value)*out++=*value++;
    }
    *out++=L'"';*out=0;
}
static BOOL line(WCHAR *out,unsigned capacity){
    unsigned n=0;for(;;){unsigned char c;DWORD read=0;
        if(!ReadFile(GetStdHandle(STD_INPUT_HANDLE),&c,1,&read,NULL)||read!=1)return FALSE;
        if(c=='\n'){out[n]=0;return n>0;}if(c<32||c>126||n+1>=capacity)return FALSE;out[n++]=(WCHAR)c;
    }
}
static int launch(int argc,WCHAR **argv){
    WCHAR dir[1024],magic[32],host[254],user[129],pass[129],command[8192]={0};
    if(argc!=7||!directory(argv[2],dir)||wcscmp(argv[3],L"--server")||
       (wcscmp(argv[4],L"--username")&&wcscmp(argv[4],L"--user"))||
       (wcscmp(argv[5],L"--password")&&wcscmp(argv[5],L"--pass"))||
       (wcscmp(argv[6],L"0")&&wcscmp(argv[6],L"1")&&wcscmp(argv[6],L"2")))return 84;
    if(!line(magic,32)||wcscmp(magic,L"LSBLOGIN1")||!line(host,254)||!line(user,129)||!line(pass,129)){
        SecureZeroMemory(user,sizeof(user));SecureZeroMemory(pass,sizeof(pass));return 85;
    }
    arg(command,argv[2]);arg(command,argv[3]);arg(command,host);arg(command,argv[4]);arg(command,user);arg(command,argv[5]);arg(command,pass);arg(command,L"--lang");arg(command,argv[6]);
    STARTUPINFOW si={0};PROCESS_INFORMATION pi={0};si.cb=sizeof(si);
    SECURITY_ATTRIBUTES sa={sizeof(sa),NULL,TRUE};HANDLE input=CreateFileW(L"NUL",GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,&sa,OPEN_EXISTING,0,NULL);
    si.dwFlags=STARTF_USESTDHANDLES;si.hStdInput=input;si.hStdOutput=GetStdHandle(STD_OUTPUT_HANDLE);si.hStdError=GetStdHandle(STD_ERROR_HANDLE);
    SetHandleInformation(si.hStdOutput,HANDLE_FLAG_INHERIT,HANDLE_FLAG_INHERIT);SetHandleInformation(si.hStdError,HANDLE_FLAG_INHERIT,HANDLE_FLAG_INHERIT);
    /* lpApplicationName is explicit; a space-containing client path cannot select another EXE. */
    BOOL started=CreateProcessW(argv[2],command+1,NULL,NULL,TRUE,0,NULL,dir,&si,&pi);DWORD error=started?0:GetLastError();
    SecureZeroMemory(command,sizeof(command));SecureZeroMemory(user,sizeof(user));SecureZeroMemory(pass,sizeof(pass));if(input!=INVALID_HANDLE_VALUE)CloseHandle(input);
    if(!started){receipt("create_failed",error,0);return 1;}
    CloseHandle(pi.hThread);
    if(!receipt("running",0,0)){TerminateProcess(pi.hProcess,90);CloseHandle(pi.hProcess);return 90;}
    DWORD waited=WaitForSingleObject(pi.hProcess,INFINITE),code=0;
    BOOL ok=waited==WAIT_OBJECT_0&&GetExitCodeProcess(pi.hProcess,&code);error=ok?0:GetLastError();CloseHandle(pi.hProcess);
    if(!receipt(ok?"exited":"wait_failed",error,code))return 91;
    return ok&&code==0?0:1;
}
int wmain(int argc,WCHAR **argv){
    SetErrorMode(SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);
    if(argc>1&&!wcscmp(argv[1],L"check")){
        int code=check(argc,argv);
        /* Only the load-only checker skips CRT/DLL detach. Its receipt is closed
         * and atomically published before success. The OS reclaims its handles
         * and memory. Never use this path for the loader/game or an installer.
         * A failed load, receipt write or termination still has a nonzero exit. */
        TerminateProcess(GetCurrentProcess(),(UINT)code);return 86;
    }
    if(argc>1&&!wcscmp(argv[1],L"launch"))return launch(argc,argv);
    return 79;
}
