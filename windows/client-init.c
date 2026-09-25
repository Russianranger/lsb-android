#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <stdio.h>
#include <wchar.h>

/* Class/interface identifiers: LandSandBoat/xiloader src/defines.h at
 * 370a1a11e4d3c5b58b793bd83096de34d5942ed6. No game methods are invoked. */
static const WCHAR *pol_classes[]={L"{07974581-0DF6-4EF0-BD05-604B3ADA9BE9}",L"{3501F5DD-7894-42DF-866A-A2B6527D8049}",L"{E5966FB3-C97B-42EB-84BF-37F95EE54A9F}"};
static const WCHAR *pol_interfaces[]={L"{9A30D565-A74C-4B56-B971-DCF02185B10D}",L"{E0516654-EF77-435D-AA7D-50D2C069CE34}",L"{DFEC2E93-4971-4A54-B8ED-63815C208C5A}"};
static const WCHAR *ffxi_class=L"{989D790D-6236-11D4-80E9-00105A81E890}";
static const WCHAR *ffxi_interface=L"{989D790C-6236-11D4-80E9-00105A81E890}";
static DWORD error_code;
static DWORD child_exit;
static WCHAR detail[2048],loaded[2048];
#ifdef LSB_CLIENT_INIT_TEST_MODE
/* Tests change receipt location only. Registration and class lookup still use
 * the production 32-bit Win32 APIs and canonical D: client-path rules. */
static WCHAR receipt_new[2048],receipt_final[2048];
#else
static const WCHAR *receipt_new=L"Z:\\session\\client-step.new",*receipt_final=L"Z:\\session\\client-step.json";
#endif
static void quoted(FILE *f,const WCHAR *s){
    char out[8192];int n=WideCharToMultiByte(CP_UTF8,0,s,-1,out,sizeof(out),NULL,NULL);
    fputc('"',f);for(int i=0;i<n-1;i++){unsigned char c=out[i];if(c=='"'||c=='\\')fputc('\\',f);if(c<32)fprintf(f,"\\u%04x",c);else fputc(c,f);}fputc('"',f);
}
static int report(const WCHAR *op,HRESULT hr){
    FILE *f=_wfopen(receipt_new,L"wb");if(!f)return 91;
    fprintf(f,"{\"format\":1,\"bits\":32,\"operation\":");quoted(f,op);
    fprintf(f,",\"ok\":%s,\"hresult\":%lu,\"win32_error\":%lu,\"child_exit\":%lu,\"detail\":",SUCCEEDED(hr)?"true":"false",(unsigned long)hr,(unsigned long)error_code,(unsigned long)child_exit);quoted(f,detail);
    fputs(",\"loaded_path\":",f);quoted(f,loaded);fputs("}\n",f);fclose(f);
    if(!MoveFileExW(receipt_new,receipt_final,MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))return 92;
    wprintf(L"%ls: HRESULT=0x%08lx Win32=%lu %ls\n",op,(unsigned long)hr,(unsigned long)error_code,detail);fflush(stdout);
    return SUCCEEDED(hr)?0:1;
}
static int region(const WCHAR *s){return !wcscmp(s,L"JP")?0:!wcscmp(s,L"US")?1:!wcscmp(s,L"EU")?2:-1;}
static BOOL client_path(const WCHAR *path){
    if(wcslen(path)>=1800||wcsncmp(path,L"D:\\",3)||wcschr(path,L'"')||wcschr(path+2,L':')||wcschr(path,L'/'))return FALSE;
    WCHAR full[2048];DWORD n=GetFullPathNameW(path,2048,full,NULL);
    return n>0&&n<2048&&!wcscmp(full,path);
}
static HRESULT value(HKEY root,const WCHAR *key,const WCHAR *name,DWORD type,const void *data,DWORD count){
    HKEY h;LONG rc=RegCreateKeyExW(root,key,0,NULL,0,KEY_READ|KEY_WRITE|KEY_WOW64_32KEY,NULL,&h,NULL);
    if(rc){error_code=rc;return HRESULT_FROM_WIN32(rc);}
    rc=RegSetValueExW(h,name,0,type,data,count);
    BYTE actual[4096];DWORD len=sizeof(actual),kind=0;
    if(!rc)rc=RegQueryValueExW(h,name,NULL,&kind,actual,&len);
    if(!rc&&(kind!=type||len!=count||memcmp(data,actual,count)))rc=ERROR_INVALID_DATA;
    RegCloseKey(h);error_code=rc;return HRESULT_FROM_WIN32(rc);
}
static HRESULT registry(const WCHAR *area,const WCHAR *pol,const WCHAR *game){
    int language=region(area);if(language<0||!client_path(pol)||!client_path(game))return E_INVALIDARG;
    WCHAR branch[256];swprintf(branch,256,L"Software\\PlayOnline%ls\\InstallFolder",language==0?L"":area);
    HRESULT hr=value(HKEY_LOCAL_MACHINE,branch,L"1000",REG_SZ,pol,(DWORD)(wcslen(pol)+1)*2);if(FAILED(hr))return hr;
    hr=value(HKEY_LOCAL_MACHINE,branch,L"0001",REG_SZ,game,(DWORD)(wcslen(game)+1)*2);if(FAILED(hr))return hr;
    swprintf(branch,256,L"Software\\PlayOnline%ls\\%ls\\PlayOnlineViewer\\Settings",language==0?L"":area,language==0?L"Square":L"SquareEnix");
    DWORD lang=(DWORD)language;hr=value(HKEY_LOCAL_MACHINE,branch,L"Language",REG_DWORD,&lang,4);if(FAILED(hr))return hr;
    hr=value(HKEY_CURRENT_USER,L"Software\\Wine",L"Version",REG_SZ,L"win7",10);
    wcscpy(detail,L"32-bit installation paths and language read back; Wine win7 profile recorded");return hr;
}
static HRESULT register_file(const WCHAR *path){
    if(!client_path(path))return E_INVALIDARG;
    WCHAR dir[2048];wcscpy(dir,path);WCHAR *p=wcsrchr(dir,L'\\');if(!p)return E_INVALIDARG;*p=0;
    if(!SetCurrentDirectoryW(dir)){error_code=GetLastError();return HRESULT_FROM_WIN32(error_code);}
    HMODULE dll=LoadLibraryExW(path,NULL,LOAD_WITH_ALTERED_SEARCH_PATH);
    if(!dll){error_code=GetLastError();wcscpy(detail,L"DLL load failed; Wine log lists missing imports (126) or invalid architecture (193)");return HRESULT_FROM_WIN32(error_code);}
    GetModuleFileNameW(dll,loaded,2048);
    HRESULT (WINAPI *fn)(void)=(void*)GetProcAddress(dll,"DllRegisterServer");
    HRESULT hr=fn?fn():HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND);
    wcscpy(detail,fn?L"DllRegisterServer returned":L"DllRegisterServer export is absent");FreeLibrary(dll);return hr;
}
static HRESULT com(const WCHAR *area,const WCHAR *kind,const WCHAR *expected){
    int lang=region(area);if(lang<0||!client_path(expected))return E_INVALIDARG;
    BOOL pol=!wcscmp(kind,L"pol");if(!pol&&wcscmp(kind,L"ffxi"))return E_INVALIDARG;
    const WCHAR *cls=pol?pol_classes[lang]:ffxi_class,*iface=pol?pol_interfaces[lang]:ffxi_interface;
    WCHAR key[256];swprintf(key,256,L"CLSID\\%ls\\InprocServer32",cls);
    HKEY h;LONG rc=RegOpenKeyExW(HKEY_CLASSES_ROOT,key,0,KEY_READ|KEY_WOW64_32KEY,&h);DWORD bytes=sizeof(loaded),type=0;
    if(!rc){rc=RegQueryValueExW(h,NULL,NULL,&type,(BYTE*)loaded,&bytes);RegCloseKey(h);}
    loaded[2047]=0;
    if(rc){error_code=rc;wcscpy(detail,L"Expected 32-bit COM registration is absent");return HRESULT_FROM_WIN32(rc);}
    if(type!=REG_SZ||bytes<2||bytes>sizeof(loaded)||_wcsicmp(loaded,expected)){wcscpy(detail,L"COM registration points outside the selected working DLL");return E_FAIL;}
    WCHAR dir[2048];wcscpy(dir,expected);*wcsrchr(dir,L'\\')=0;SetCurrentDirectoryW(dir);
    CLSID class_id;IID interface_id;CLSIDFromString(cls,&class_id);IIDFromString(iface,&interface_id);
    IUnknown *instance=NULL;HRESULT hr=CoCreateInstance(&class_id,NULL,CLSCTX_INPROC_SERVER,&interface_id,(void**)&instance);
    if(SUCCEEDED(hr)&&!instance)hr=E_FAIL;
    if(instance)IUnknown_Release(instance);
    wcscpy(detail,L"CoCreateInstance with the client's interface ID; no GameStart or login invoked");return hr;
}
static HRESULT viewer_class(const WCHAR *kind,const WCHAR *expected){
    /* Class identities from the official viewer MSI's App.PolAppCom,
     * PolContents.PolContentsCom and PolContentsINT.PolContentsCom classes.
     * Verify factories only: creating these application objects can initialize
     * viewer/account state and is not a dependency preflight. */
    static const struct {const WCHAR *kind,*name,*cls;} classes[]={
        {L"pol-app",L"app.dll",L"{40555AAE-53AD-4ABC-AE65-8441755E7D69}"},
        {L"pol-contents",L"PolContents.dll",L"{62021866-976B-49A3-A18B-7A44869008A2}"},
        {L"pol-contents-int",L"polcontentsINT.dll",L"{3FC1EF9A-F346-413C-BB47-ED6F9A4BD52F}"}
    };
    const WCHAR *cls=NULL,*name=wcsrchr(expected,L'\\');
    if(!client_path(expected)||!name)return E_INVALIDARG;
    const WCHAR *segment=expected+3;
    for(const WCHAR *p=segment;;p++){
        if(*p&&(*p<32||wcschr(L"<>|?*",*p)))return E_INVALIDARG;
        if(!*p||*p==L'\\'){
            if(p==segment||p[-1]==L' '||p[-1]==L'.')return E_INVALIDARG;
            if(!*p)break;segment=p+1;
        }
    }
    for(unsigned int i=0;i<sizeof(classes)/sizeof(classes[0]);i++)
        if(!wcscmp(kind,classes[i].kind)&&!_wcsicmp(name+1,classes[i].name))cls=classes[i].cls;
    if(!cls)return E_INVALIDARG;
    WCHAR key[256];swprintf(key,256,L"CLSID\\%ls\\InprocServer32",cls);
    HKEY h;LONG rc=RegOpenKeyExW(HKEY_CLASSES_ROOT,key,0,KEY_READ|KEY_WOW64_32KEY,&h);
    DWORD bytes=sizeof(loaded),type=0;memset(loaded,0,sizeof(loaded));
    if(!rc){rc=RegQueryValueExW(h,NULL,NULL,&type,(BYTE*)loaded,&bytes);RegCloseKey(h);}
    loaded[2047]=0;
    if(rc){error_code=rc;wcscpy(detail,L"Expected 32-bit PlayOnline class registration is absent");return HRESULT_FROM_WIN32(rc);}
    if(type!=REG_SZ||bytes!=(wcslen(expected)+1)*sizeof(WCHAR)||bytes>sizeof(loaded)||
       loaded[bytes/sizeof(WCHAR)-1]!=0||_wcsicmp(loaded,expected)){
        loaded[2047]=0;wcscpy(detail,L"PlayOnline class registration does not match the selected DLL");return E_FAIL;
    }
    WCHAR dir[2048];wcscpy(dir,expected);WCHAR *last=wcsrchr(dir,L'\\');
    if(last==dir+2)last[1]=0;else *last=0;
    if(!SetCurrentDirectoryW(dir)){error_code=GetLastError();return HRESULT_FROM_WIN32(error_code);}
    CLSID class_id;HRESULT hr=CLSIDFromString(cls,&class_id);if(FAILED(hr))return hr;
    IClassFactory *factory=NULL;
    hr=CoGetClassObject(&class_id,CLSCTX_INPROC_SERVER,NULL,&IID_IClassFactory,(void**)&factory);
    if(SUCCEEDED(hr)&&!factory)hr=E_FAIL;
    if(factory)IClassFactory_Release(factory);
    wcscpy(detail,L"PlayOnline class factory checked; no application object, game or login created");return hr;
}
static HRESULT installer(void){
    WCHAR command[]=L"\"Z:\\session\\prerequisite.exe\"";
    STARTUPINFOW si={0};PROCESS_INFORMATION pi={0};si.cb=sizeof(si);
    if(!CreateProcessW(L"Z:\\session\\prerequisite.exe",command,NULL,NULL,FALSE,0,NULL,L"Z:\\session",&si,&pi)){
        error_code=GetLastError();return HRESULT_FROM_WIN32(error_code);
    }
    CloseHandle(pi.hThread);DWORD wait=WaitForSingleObject(pi.hProcess,INFINITE);
    BOOL result=wait==WAIT_OBJECT_0&&GetExitCodeProcess(pi.hProcess,&child_exit);CloseHandle(pi.hProcess);
    if(!result){error_code=GetLastError();return E_FAIL;}
    wcscpy(detail,child_exit==3010?L"Installer completed; Windows restart requested":L"Installer exited; see full Windows child_exit");
    return child_exit==0||child_exit==3010?S_OK:HRESULT_FROM_WIN32(child_exit);
}
int wmain(int argc,WCHAR **argv){
#ifdef LSB_CLIENT_INIT_TEST_MODE
    DWORD n=GetCurrentDirectoryW(1900,receipt_new);if(!n||n>=1900)return 93;
    wcscpy(receipt_final,receipt_new);wcscat(receipt_new,L"\\client-step.new");wcscat(receipt_final,L"\\client-step.json");
#endif
    SetErrorMode(SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);
    HRESULT init=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);if(FAILED(init))return report(L"com-apartment",init);
    HRESULT hr=E_INVALIDARG;const WCHAR *op=argc>1?argv[1]:L"arguments";
    if(argc==5&&!wcscmp(op,L"registry"))hr=registry(argv[2],argv[3],argv[4]);
    else if(argc==3&&!wcscmp(op,L"register"))hr=register_file(argv[2]);
    else if(argc==5&&!wcscmp(op,L"com"))hr=com(argv[2],argv[3],argv[4]);
    else if(argc==4&&!wcscmp(op,L"class"))hr=viewer_class(argv[2],argv[3]);
    else if(argc==2&&!wcscmp(op,L"installer"))hr=installer();
    int result=report(op,hr);CoUninitialize();return result;
}
