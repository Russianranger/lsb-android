/* Synthetic version data, produced by the independently inspected format.
 * No game assets, user files or installation-specific IDs are in this fixture. */
#include <windows.h>
#include <stdio.h>
#include <wchar.h>
#include <assert.h>
static BOOL fail_write,fail_readback;
static LONG WINAPI test_set(HKEY k,LPCWSTR name,DWORD r,DWORD type,const BYTE *data,DWORD size){
    if(fail_write){fail_write=FALSE;return ERROR_ACCESS_DENIED;}
    return RegSetValueExW(k,name,r,type,data,size);
}
static LONG WINAPI test_query(HKEY k,LPCWSTR name,LPDWORD r,LPDWORD type,LPBYTE data,LPDWORD size){
    if(fail_readback&&data){fail_readback=FALSE;return ERROR_READ_FAULT;}
    return RegQueryValueExW(k,name,r,type,data,size);
}
#define RegSetValueExW test_set
#define RegQueryValueExW test_query
#define LSB_VERSION_TEST
#include "../../windows/version-registry.h"
#undef RegSetValueExW
#undef RegQueryValueExW
static const char fixture_hex[]=
"23f1451d97e2aea05c60a225e0b9326647927c5a874e883c1c14e7c0404d4dcfcb8d145ac110a252ce8cb57eedfc72b16dd796f9ee8d5b8692b6fce928d7e8b72bf1c9219790d831582836c83f1266fc20733c53f1025143db12ee518f5ff298697c3c1c243b4b6bb59cf87e95387ac99c183ee1107e2ba6e83ec4d496ad6dba1431208c4175b9fb0da02135717d7a291e8029fc0a5a6cde8555e9961c829a1ec7f4f229694dca6a9b24c7cf68af192c355691bd16ef4a6ab5be6a5245a9e04e0158593b62c8b2c6b8c4c0032446287cdaafe5cb6897aa11591741796777348e05cb56b2fd9dc27146d2aab584628006dfed2a2c51c2bc0ea03478708963e26703d07810daafe3ed7c43fd8a8fd65d0967b55741dc15d367cd2cf9f1db27c95d";
static void write_blob(const WCHAR *path,const BYTE *data,size_t size){FILE *f=_wfopen(path,L"wb");assert(f);assert(fwrite(data,1,size,f)==size);assert(!fclose(f));}
static void absent(HKEY key){DWORD size=0;assert(RegQueryValueExW(key,L"0001",NULL,NULL,NULL,&size)==ERROR_FILE_NOT_FOUND);}
int wmain(int argc,WCHAR **argv){
    BYTE blob[288],changed[288];char version[18];assert(sizeof(fixture_hex)==577);
    for(unsigned i=0;i<288;i++){unsigned v;assert(sscanf(fixture_hex+2*i,"%2x",&v)==1);blob[i]=(BYTE)v;}
    assert(patch_version_zero(blob,288,version)&&!strcmp(version,"20260921_1"));
    assert(!patch_version_zero(blob,287,version)&&!version[0]);
    for(unsigned i=0;i<288;i++){
        memcpy(changed,blob,288);changed[i]^=0x80;
        assert(!patch_version_zero(changed,288,version)&&!version[0]);
    }
    WCHAR tmp[MAX_PATH],game[MAX_PATH],path[MAX_PATH];assert(GetTempPathW(MAX_PATH,tmp));
    swprintf(game,MAX_PATH,L"%lslsb-version-%lu",tmp,(unsigned long)GetCurrentProcessId());assert(CreateDirectoryW(game,NULL));
    swprintf(path,MAX_PATH,L"%ls\\patch.ver",game);
    const WCHAR *regions[]={L"0",L"1",L"2"},*areas[]={L"",L"US",L"EU"};
    for(unsigned i=0;i<3;i++){
        WCHAR branch[128];swprintf(branch,128,VERSION_BRANCH,areas[i]);
        RegDeleteKeyExW(VERSION_ROOT,branch,KEY_WOW64_32KEY,0);
        assert(!version_registry(regions[i],game)&&!strcmp(version_state,"file_unavailable"));
        HKEY key;assert(!RegCreateKeyExW(VERSION_ROOT,branch,0,NULL,0,KEY_ALL_ACCESS|KEY_WOW64_32KEY,NULL,&key,NULL));
        write_blob(path,blob,287);assert(!version_registry(regions[i],game)&&!strcmp(version_state,"format_not_matched"));absent(key);
        memcpy(changed,blob,288);changed[283]^=0x80;write_blob(path,changed,288);
        assert(!version_registry(regions[i],game)&&!strcmp(version_state,"format_not_matched"));absent(key);
        write_blob(path,blob,288);fail_write=TRUE;
        assert(version_registry(regions[i],game)==ERROR_ACCESS_DENIED&&!fail_write);absent(key);
        fail_readback=TRUE;assert(version_registry(regions[i],game)==ERROR_READ_FAULT&&!fail_readback&&!version_rollback_error);absent(key);
        assert(!version_registry(regions[i],game)&&!strcmp(version_state,"restored_missing")&&!strcmp(client_version,"20260921_1"));
        WCHAR value[20];DWORD type,size=sizeof(value);assert(!RegQueryValueExW(key,L"0001",NULL,&type,(BYTE*)value,&size));
        assert(type==REG_SZ&&size==4&&value[0]==L'0'&&!value[1]);
        assert(!version_registry(regions[i],game)&&!strcmp(version_state,"existing_preserved"));
        assert(!RegSetValueExW(key,L"0001",0,REG_SZ,(BYTE*)L"keep-me",16));
        assert(!version_registry(regions[i],game)&&!strcmp(version_state,"existing_preserved"));
        size=sizeof(value);assert(!RegQueryValueExW(key,L"0001",NULL,&type,(BYTE*)value,&size)&&!wcscmp(value,L"keep-me"));
        DWORD other=42;assert(!RegSetValueExW(key,L"0001",0,REG_DWORD,(BYTE*)&other,4));
        assert(!version_registry(regions[i],game)&&!strcmp(version_state,"existing_preserved"));
        size=4;DWORD got=0;assert(!RegQueryValueExW(key,L"0001",NULL,&type,(BYTE*)&got,&size)&&type==REG_DWORD&&got==42);
        RegCloseKey(key);assert(!RegDeleteKeyExW(VERSION_ROOT,branch,KEY_WOW64_32KEY,0));
        FILE *f=_wfopen(path,L"rb");assert(f&&fread(changed,1,288,f)==288&&!memcmp(changed,blob,288));assert(!fclose(f));
        assert(DeleteFileW(path));
    }
    assert(RemoveDirectoryW(game));
    if(argc==2)write_blob(argv[1],blob,288);
    puts("PASS: version registry JP/US/EU missing-only repair, existing value/type preservation, invalid files, write failure, readback rollback and source preservation; 288 corruption checks");
    return 0;
}
