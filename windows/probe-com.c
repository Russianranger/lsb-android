#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <stdio.h>

/* Open test class, deliberately unrelated to PlayOnline/FFXI registration. */
static const CLSID probe = {0x97191a4b,0xb2c7,0x47b1,{0x95,0x0d,0x10,0x57,0xa8,0xb5,0x1f,0x01}};
static HMODULE module;
static HRESULT STDMETHODCALLTYPE qi(IUnknown *self, REFIID iid, void **out) {
    *out=NULL; if(!IsEqualIID(iid,&IID_IUnknown))return E_NOINTERFACE;*out=self;return S_OK;
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
    HKEY key;WCHAR path[32768];DWORD n=GetModuleFileNameW(module,path,32768);
    if(!n||n>=32768)return E_FAIL;
    LONG e=RegCreateKeyExW(HKEY_LOCAL_MACHINE,L"Software\\Classes\\CLSID\\{97191A4B-B2C7-47B1-950D-1057A8B51F01}\\InprocServer32",0,NULL,0,KEY_SET_VALUE|KEY_WOW64_32KEY,NULL,&key,NULL);
    if(e)return HRESULT_FROM_WIN32(e);
    e=RegSetValueExW(key,NULL,0,REG_SZ,(BYTE*)path,(n+1)*sizeof(WCHAR));RegCloseKey(key);return HRESULT_FROM_WIN32(e);
}
__declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID clsid,REFIID iid,void **out){
    if(!IsEqualCLSID(clsid,&probe))return CLASS_E_CLASSNOTAVAILABLE;return factory_qi(&factory,iid,out);
}
__declspec(dllexport) HRESULT WINAPI DllCanUnloadNow(void){return S_FALSE;}
