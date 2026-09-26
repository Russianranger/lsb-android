#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <stdio.h>

/* Disposable administrator CI fixture. Each known viewer DLL registers its own
 * class; the test refuses existing keys and removes only entries it creates. */
static HMODULE module;
static const WCHAR *class_string(void){
    WCHAR path[2048];GetModuleFileNameW(module,path,2048);
    const WCHAR *name=wcsrchr(path,L'\\');if(!name)return NULL;name++;
    if(!_wcsicmp(name,L"app.dll"))return L"{40555AAE-53AD-4ABC-AE65-8441755E7D69}";
    if(!_wcsicmp(name,L"PolContents.dll"))return L"{62021866-976B-49A3-A18B-7A44869008A2}";
    if(!_wcsicmp(name,L"polcontentsINT.dll"))return L"{3FC1EF9A-F346-413C-BB47-ED6F9A4BD52F}";
    return NULL;
}
static BOOL mark(const WCHAR *suffix){
    WCHAR path[2048];DWORD n=GetModuleFileNameW(module,path,1900);
    if(!n||n>=1900)return FALSE;wcscat(path,suffix);
    FILE *file=_wfopen(path,L"wb");if(!file)return FALSE;fputs("1",file);fclose(file);return TRUE;
}
static HRESULT STDMETHODCALLTYPE query(IClassFactory *self,REFIID iid,void **out){
    *out=NULL;if(!IsEqualIID(iid,&IID_IUnknown)&&!IsEqualIID(iid,&IID_IClassFactory))return E_NOINTERFACE;
    *out=self;return S_OK;
}
static ULONG STDMETHODCALLTYPE add(IClassFactory *self){(void)self;return 2;}
static ULONG STDMETHODCALLTYPE release(IClassFactory *self){(void)self;mark(L".released");return 1;}
static HRESULT STDMETHODCALLTYPE create(IClassFactory *self,IUnknown *outer,REFIID iid,void **out){
    (void)self;(void)outer;(void)iid;*out=NULL;mark(L".created");return E_UNEXPECTED;
}
static HRESULT STDMETHODCALLTYPE lock(IClassFactory *self,BOOL value){(void)self;(void)value;return S_OK;}
static IClassFactoryVtbl vtable={query,add,release,create,lock};
static IClassFactory factory={&vtable};
BOOL WINAPI DllMain(HINSTANCE h,DWORD reason,LPVOID reserved){(void)reserved;if(reason==DLL_PROCESS_ATTACH)module=h;return TRUE;}
__declspec(dllexport) HRESULT WINAPI DllRegisterServer(void){
    const WCHAR *cls=class_string();if(!cls)return E_INVALIDARG;
    WCHAR path[2048],key_name[256];DWORD n=GetModuleFileNameW(module,path,2048);if(!n||n>=2048)return E_FAIL;
    swprintf(key_name,256,L"Software\\Classes\\CLSID\\%ls\\InprocServer32",cls);
    HKEY key;LONG e=RegCreateKeyExW(HKEY_LOCAL_MACHINE,key_name,0,NULL,0,KEY_SET_VALUE|KEY_WOW64_32KEY,NULL,&key,NULL);
    if(e)return HRESULT_FROM_WIN32(e);
    e=RegSetValueExW(key,NULL,0,REG_SZ,(BYTE*)path,(n+1)*sizeof(WCHAR));
    if(!e)e=RegSetValueExW(key,L"ThreadingModel",0,REG_SZ,(BYTE*)L"Apartment",10*sizeof(WCHAR));
    RegCloseKey(key);return HRESULT_FROM_WIN32(e);
}
__declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID cls,REFIID iid,void **out){
    const WCHAR *text=class_string();CLSID expected;*out=NULL;if(!text)return E_INVALIDARG;
    CLSIDFromString(text,&expected);if(!IsEqualCLSID(cls,&expected))return CLASS_E_CLASSNOTAVAILABLE;
    if(!mark(L".factory"))return E_ACCESSDENIED;
    WCHAR path[2048];GetModuleFileNameW(module,path,1900);wcscat(path,L".fail-factory");
    if(GetFileAttributesW(path)!=INVALID_FILE_ATTRIBUTES)return E_NOINTERFACE;
    return query(&factory,iid,out);
}
__declspec(dllexport) HRESULT WINAPI DllCanUnloadNow(void){return S_FALSE;}
