#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <stdio.h>

/* Synthetic client-interface fixture for isolated Wine CI prefixes only. */
static HMODULE module;
static CLSID probe;
static IID client_interface;
static WCHAR class_string[64];
static void identity(void){
 WCHAR path[2048];GetModuleFileNameW(module,path,2048);
 const WCHAR *name=wcsrchr(path,L'\\');name=name?name+1:path;
 const WCHAR *cls=L"{989D790D-6236-11D4-80E9-00105A81E890}",*iid=L"{989D790C-6236-11D4-80E9-00105A81E890}";
 if(!_wcsicmp(name,L"polcore.dll")){cls=L"{3501F5DD-7894-42DF-866A-A2B6527D8049}";iid=L"{E0516654-EF77-435D-AA7D-50D2C069CE34}";}
 if(!_wcsicmp(name,L"polcoreeu.dll")){cls=L"{E5966FB3-C97B-42EB-84BF-37F95EE54A9F}";iid=L"{DFEC2E93-4971-4A54-B8ED-63815C208C5A}";}
 if(!_wcsicmp(name,L"polcore.dll")&&GetFileAttributesW(L"D:\\region-jp")!=INVALID_FILE_ATTRIBUTES){cls=L"{07974581-0DF6-4EF0-BD05-604B3ADA9BE9}";iid=L"{9A30D565-A74C-4B56-B971-DCF02185B10D}";}
 CLSIDFromString(cls,&probe);IIDFromString(iid,&client_interface);wcscpy(class_string,cls);
}

static HRESULT STDMETHODCALLTYPE qi(IUnknown *self, REFIID iid, void **out) {
    *out=NULL; identity(); if(!IsEqualIID(iid,&IID_IUnknown)&&!IsEqualIID(iid,&client_interface))return E_NOINTERFACE;*out=self;return S_OK;
}
static ULONG STDMETHODCALLTYPE add(IUnknown *self){(void)self;return 2;}
static ULONG STDMETHODCALLTYPE release(IUnknown *self){(void)self;return 1;}
static IUnknownVtbl object_vtable={qi,add,release};
static IUnknown object={&object_vtable};
static HRESULT STDMETHODCALLTYPE factory_qi(IClassFactory *self,REFIID iid,void **out){
    *out=NULL;if(!IsEqualIID(iid,&IID_IUnknown)&&!IsEqualIID(iid,&IID_IClassFactory))return E_NOINTERFACE;*out=self;return S_OK;
}
static ULONG STDMETHODCALLTYPE factory_add(IClassFactory *self){(void)self;return 2;}
static ULONG STDMETHODCALLTYPE factory_release(IClassFactory *self){(void)self;return 1;}
static HRESULT STDMETHODCALLTYPE create(IClassFactory *self,IUnknown *outer,REFIID iid,void **out){
    (void)self;if(outer)return CLASS_E_NOAGGREGATION;return qi(&object,iid,out);
}
static HRESULT STDMETHODCALLTYPE lock(IClassFactory *self,BOOL value){(void)self;(void)value;return S_OK;}
static IClassFactoryVtbl factory_vtable={factory_qi,factory_add,factory_release,create,lock};
static IClassFactory factory={&factory_vtable};
BOOL WINAPI DllMain(HINSTANCE instance,DWORD reason,LPVOID reserved){(void)reserved;if(reason==DLL_PROCESS_ATTACH)module=instance;return TRUE;}
__declspec(dllexport) HRESULT WINAPI DllRegisterServer(void){
    identity();
    if(GetFileAttributesW(L"D:\\fail-registration")!=INVALID_FILE_ATTRIBUTES)return E_ACCESSDENIED;
    HKEY key;WCHAR path[32768];DWORD n=GetModuleFileNameW(module,path,32768);
    if(!n||n>=32768)return E_FAIL;
    const WCHAR *name=wcsrchr(path,L'\\');
    if(name&&!_wcsicmp(name+1,L"FFXiMain.dll"))return S_OK;
    WCHAR key_path[256];swprintf(key_path,256,L"Software\\Classes\\CLSID\\%ls\\InprocServer32",class_string);
    LONG e=RegCreateKeyExW(HKEY_LOCAL_MACHINE,key_path,0,NULL,0,KEY_SET_VALUE|KEY_WOW64_32KEY,NULL,&key,NULL);
    if(e)return HRESULT_FROM_WIN32(e);
    e=RegSetValueExW(key,NULL,0,REG_SZ,(BYTE*)path,(n+1)*sizeof(WCHAR));RegCloseKey(key);return HRESULT_FROM_WIN32(e);
}
__declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID clsid,REFIID iid,void **out){
    identity();if(GetFileAttributesW(L"D:\\fail-com")!=INVALID_FILE_ATTRIBUTES)return E_NOINTERFACE;
    if(!IsEqualCLSID(clsid,&probe))return CLASS_E_CLASSNOTAVAILABLE;return factory_qi(&factory,iid,out);
}
__declspec(dllexport) HRESULT WINAPI DllCanUnloadNow(void){return S_FALSE;}
