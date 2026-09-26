#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <stdio.h>

/* Disposable administrator CI fixture. Each known viewer DLL registers its own
 * class; the test refuses existing keys and removes only entries it creates. */
static HMODULE module;
static const WCHAR *core_classes[]={L"{07974581-0DF6-4EF0-BD05-604B3ADA9BE9}",L"{3501F5DD-7894-42DF-866A-A2B6527D8049}",L"{E5966FB3-C97B-42EB-84BF-37F95EE54A9F}"};
static const WCHAR *core_interfaces[]={L"{9A30D565-A74C-4B56-B971-DCF02185B10D}",L"{E0516654-EF77-435D-AA7D-50D2C069CE34}",L"{DFEC2E93-4971-4A54-B8ED-63815C208C5A}"};
static BOOL is_core(void){WCHAR path[2048];GetModuleFileNameW(module,path,2048);const WCHAR *name=wcsrchr(path,L'\\');return name&&(!_wcsicmp(name+1,L"polcore.dll")||!_wcsicmp(name+1,L"polcoreeu.dll"));}
static BOOL failing(const WCHAR *suffix){WCHAR path[2048];GetModuleFileNameW(module,path,1900);wcscat(path,suffix);return GetFileAttributesW(path)!=INVALID_FILE_ATTRIBUTES;}

static const WCHAR *class_string(void){
    WCHAR path[2048];GetModuleFileNameW(module,path,2048);
    const WCHAR *name=wcsrchr(path,L'\\');if(!name)return NULL;name++;
    if(!_wcsicmp(name,L"app.dll"))return L"{40555AAE-53AD-4ABC-AE65-8441755E7D69}";
    if(!_wcsicmp(name,L"PolContents.dll"))return L"{62021866-976B-49A3-A18B-7A44869008A2}";
    if(!_wcsicmp(name,L"polcontentsINT.dll"))return L"{3FC1EF9A-F346-413C-BB47-ED6F9A4BD52F}";
    if(is_core())return core_classes[1];
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
static HRESULT STDMETHODCALLTYPE object_query(IUnknown *self,REFIID iid,void **out){
    *out=NULL;for(unsigned i=0;i<3;i++){IID expected;IIDFromString(core_interfaces[i],&expected);if(IsEqualIID(iid,&expected)){*out=self;return S_OK;}}
    if(IsEqualIID(iid,&IID_IUnknown)){*out=self;return S_OK;}return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE object_add(IUnknown *self){(void)self;return 2;}
static ULONG STDMETHODCALLTYPE object_release(IUnknown *self){(void)self;mark(L".object-released");return 1;}
static IUnknownVtbl object_vtable={object_query,object_add,object_release};
static IUnknown object={&object_vtable};
static HRESULT STDMETHODCALLTYPE create(IClassFactory *self,IUnknown *outer,REFIID iid,void **out){
    (void)self;(void)outer;*out=NULL;mark(L".created");return is_core()?object_query(&object,iid,out):E_UNEXPECTED;
}
static HRESULT STDMETHODCALLTYPE lock(IClassFactory *self,BOOL value){(void)self;(void)value;return S_OK;}
static IClassFactoryVtbl vtable={query,add,release,create,lock};
static IClassFactory factory={&vtable};
BOOL WINAPI DllMain(HINSTANCE h,DWORD reason,LPVOID reserved){(void)reserved;if(reason==DLL_PROCESS_ATTACH)module=h;return TRUE;}
static HRESULT register_class(const WCHAR *cls){
    WCHAR path[2048],key_name[256];DWORD n=GetModuleFileNameW(module,path,2048);if(!n||n>=2048)return E_FAIL;
    swprintf(key_name,256,L"Software\\Classes\\CLSID\\%ls\\InprocServer32",cls);
    HKEY key;LONG e=RegCreateKeyExW(HKEY_LOCAL_MACHINE,key_name,0,NULL,0,KEY_SET_VALUE|KEY_WOW64_32KEY,NULL,&key,NULL);
    if(e)return HRESULT_FROM_WIN32(e);
    e=RegSetValueExW(key,NULL,0,REG_SZ,(BYTE*)path,(n+1)*sizeof(WCHAR));
    if(!e)e=RegSetValueExW(key,L"ThreadingModel",0,REG_SZ,(BYTE*)L"Apartment",10*sizeof(WCHAR));
    RegCloseKey(key);return HRESULT_FROM_WIN32(e);
}
__declspec(dllexport) HRESULT WINAPI DllRegisterServer(void){
    if(failing(L".fail-register"))return E_ACCESSDENIED;
    const WCHAR *cls=class_string();if(!cls)return E_INVALIDARG;
    mark(L".registered");HRESULT hr=S_OK;
    if(is_core())for(unsigned i=0;i<3&&SUCCEEDED(hr);i++)hr=register_class(core_classes[i]);
    else hr=register_class(cls);return hr;
}
__declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID cls,REFIID iid,void **out){
    const WCHAR *text=class_string();CLSID expected;*out=NULL;if(!text)return E_INVALIDARG;
    BOOL matches=FALSE;CLSIDFromString(text,&expected);matches=IsEqualCLSID(cls,&expected);
    if(is_core())for(unsigned i=0;i<3;i++){CLSIDFromString(core_classes[i],&expected);if(IsEqualCLSID(cls,&expected))matches=TRUE;}
    if(!matches)return CLASS_E_CLASSNOTAVAILABLE;
    if(!mark(L".factory"))return E_ACCESSDENIED;
    WCHAR path[2048];GetModuleFileNameW(module,path,1900);wcscat(path,L".fail-factory");
    if(GetFileAttributesW(path)!=INVALID_FILE_ATTRIBUTES)return E_NOINTERFACE;
    return query(&factory,iid,out);
}
__declspec(dllexport) HRESULT WINAPI DllCanUnloadNow(void){return S_FALSE;}
