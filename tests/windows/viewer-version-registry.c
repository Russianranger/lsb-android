/* Exercise the real Win32 registry and files using independently generated
 * synthetic version records; no proprietary bytes or user state are included. */
#include <windows.h>
#include <stdio.h>
#include <wchar.h>
#include <assert.h>
#include "viewer-version-fixture.h"

static BOOL fail_open,fail_write,fail_readback,bad_readback,fail_delete,insert_during_open;
static LONG WINAPI test_open(HKEY root,LPCWSTR path,DWORD options,REGSAM access,PHKEY key){
    assert(access&KEY_WOW64_32KEY);
    if(fail_open){fail_open=FALSE;return ERROR_ACCESS_DENIED;}
    return RegOpenKeyExW(root,path,options,access,key);
}
static LONG WINAPI test_create(HKEY root,LPCWSTR path,DWORD reserved,LPWSTR cls,DWORD options,
                               REGSAM access,const LPSECURITY_ATTRIBUTES security,PHKEY key,LPDWORD disposition){
    assert(access&KEY_WOW64_32KEY);
    LONG result=RegCreateKeyExW(root,path,reserved,cls,options,access,security,key,disposition);
    if(!result&&insert_during_open){
        const BYTE data[]={0x12,0x34};
        assert(!RegSetValueExW(*key,L"1000",0,REG_BINARY,data,sizeof(data)));
        insert_during_open=FALSE;
    }
    return result;
}
static LONG WINAPI test_set(HKEY key,LPCWSTR name,DWORD reserved,DWORD type,const BYTE *data,DWORD size){
    if(fail_write){fail_write=FALSE;return ERROR_ACCESS_DENIED;}
    return RegSetValueExW(key,name,reserved,type,data,size);
}
static LONG WINAPI test_query(HKEY key,LPCWSTR name,LPDWORD reserved,LPDWORD type,LPBYTE data,LPDWORD size){
    if(data&&fail_readback){fail_readback=FALSE;return ERROR_READ_FAULT;}
    LONG result=RegQueryValueExW(key,name,reserved,type,data,size);
    if(!result&&data&&bad_readback){bad_readback=FALSE;*type=REG_DWORD;}
    return result;
}
static LONG WINAPI test_delete(HKEY key,LPCWSTR name){
    if(fail_delete){fail_delete=FALSE;return ERROR_ACCESS_DENIED;}
    return RegDeleteValueW(key,name);
}
#define RegOpenKeyExW test_open
#define RegCreateKeyExW test_create
#define RegSetValueExW test_set
#define RegQueryValueExW test_query
#define RegDeleteValueW test_delete
#define LSB_VIEWER_VERSION_TEST
#include "../../windows/viewer-version-registry.h"
#undef RegOpenKeyExW
#undef RegCreateKeyExW
#undef RegSetValueExW
#undef RegQueryValueExW
#undef RegDeleteValueW

static void write_blob(const WCHAR *path,const BYTE *data,size_t size){
    FILE *file=_wfopen(path,L"wb");assert(file);assert(fwrite(data,1,size,file)==size);assert(!fclose(file));
}
static void absent(HKEY key){
    DWORD size=0;assert(RegQueryValueExW(key,L"1000",NULL,NULL,NULL,&size)==ERROR_FILE_NOT_FOUND);
}
static void game_preserved(HKEY key){
    WCHAR data[16];DWORD size=sizeof(data),type=0;
    assert(!RegQueryValueExW(key,L"0001",NULL,&type,(BYTE*)data,&size));
    assert(type==REG_SZ&&size==20&&!wcscmp(data,L"keep-game"));
}
static void bytes_preserved(const WCHAR *path,const BYTE *blob){
    BYTE got[288];FILE *file=_wfopen(path,L"rb");assert(file);
    assert(fread(got,1,sizeof(got),file)==sizeof(got)&&!memcmp(got,blob,sizeof(got)));
    assert(fgetc(file)==EOF);assert(!fclose(file));
}

int wmain(void){
    WCHAR temp[MAX_PATH],viewer[MAX_PATH],path[MAX_PATH];assert(GetTempPathW(MAX_PATH,temp));
    swprintf(viewer,MAX_PATH,L"%lslsb-viewer-version-%lu",temp,(unsigned long)GetCurrentProcessId());
    assert(CreateDirectoryW(viewer,NULL));swprintf(path,MAX_PATH,L"%ls\\patch.ver",viewer);
    const WCHAR *regions[]={L"JP",L"US",L"EU"},*areas[]={L"",L"US",L"EU"};
    const BYTE *fixtures[]={viewer_fixture_zero,viewer_fixture_official};
    const WCHAR *expected[]={L"0",L"001b1394"};
    const char *labels[]={"zero","official_installer"};
    assert(sizeof(viewer_fixture_zero)==288&&sizeof(viewer_fixture_official)==288);
    assert(viewer_version_registry(L"invalid",viewer)==ERROR_INVALID_PARAMETER);
    for(unsigned region=0;region<3;region++){
        WCHAR branch[128];swprintf(branch,128,VIEWER_VERSION_BRANCH,areas[region]);
        RegDeleteKeyExW(VIEWER_VERSION_ROOT,branch,KEY_WOW64_32KEY,0);
        HKEY key;assert(!RegCreateKeyExW(VIEWER_VERSION_ROOT,branch,0,NULL,0,KEY_ALL_ACCESS|KEY_WOW64_32KEY,NULL,&key,NULL));
        assert(!RegSetValueExW(key,L"0001",0,REG_SZ,(const BYTE*)L"keep-game",20));
        fail_open=TRUE;assert(viewer_version_registry(regions[region],viewer)==ERROR_ACCESS_DENIED&&!fail_open);
        assert(!strcmp(viewer_version_state,"registry_unavailable"));absent(key);
        assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"file_unavailable"));absent(key);
        for(unsigned candidate=0;candidate<2;candidate++){
            const BYTE *blob=fixtures[candidate];BYTE changed[289];
            write_blob(path,blob,287);
            assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"format_not_matched"));absent(key);
            memcpy(changed,blob,288);changed[288]=0;write_blob(path,changed,289);
            assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"format_not_matched"));absent(key);
            changed[283]^=0x80;write_blob(path,changed,288);
            assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"format_not_matched"));absent(key);
            write_blob(path,blob,288);
            fail_write=TRUE;assert(viewer_version_registry(regions[region],viewer)==ERROR_ACCESS_DENIED&&!fail_write);absent(key);
            fail_readback=TRUE;
            assert(viewer_version_registry(regions[region],viewer)==ERROR_READ_FAULT&&!fail_readback&&!viewer_version_rollback_error);absent(key);
            bad_readback=TRUE;
            assert(viewer_version_registry(regions[region],viewer)==ERROR_INVALID_DATA&&!bad_readback&&!viewer_version_rollback_error);absent(key);
            fail_readback=fail_delete=TRUE;
            assert(viewer_version_registry(regions[region],viewer)==ERROR_READ_FAULT&&!fail_readback&&!fail_delete);
            assert(viewer_version_rollback_error==ERROR_ACCESS_DENIED&&!strcmp(viewer_version_state,"restore_failed"));
            assert(!RegDeleteValueW(key,L"1000"));
            insert_during_open=TRUE;
            assert(!viewer_version_registry(regions[region],viewer)&&!insert_during_open&&!strcmp(viewer_version_state,"existing_preserved"));
            BYTE binary[2]={0};DWORD size=sizeof(binary),type=0;
            assert(!RegQueryValueExW(key,L"1000",NULL,&type,binary,&size)&&type==REG_BINARY&&size==2&&binary[0]==0x12&&binary[1]==0x34);
            assert(!viewer_version[0]&&!strcmp(viewer_version_candidate,"none"));assert(!RegDeleteValueW(key,L"1000"));
            assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"restored_missing"));
            assert(!strcmp(viewer_version,VIEWER_FIXTURE_VERSION)&&!strcmp(viewer_version_candidate,labels[candidate]));
            WCHAR value[20];size=sizeof(value);
            assert(!RegQueryValueExW(key,L"1000",NULL,&type,(BYTE*)value,&size));
            assert(type==REG_SZ&&size==(wcslen(expected[candidate])+1)*sizeof(WCHAR)&&!wcscmp(value,expected[candidate]));
            assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"existing_preserved"));
            assert(!viewer_version[0]&&!strcmp(viewer_version_candidate,"none"));
            bytes_preserved(path,blob);assert(DeleteFileW(path));
            /* Preserve existing values without relying on patch.ver presence. */
            assert(!RegSetValueExW(key,L"1000",0,REG_SZ,(const BYTE*)L"keep-private",26));
            assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"existing_preserved"));
            size=sizeof(value);assert(!RegQueryValueExW(key,L"1000",NULL,&type,(BYTE*)value,&size)&&!wcscmp(value,L"keep-private"));
            DWORD number=42;assert(!RegSetValueExW(key,L"1000",0,REG_DWORD,(const BYTE*)&number,4));
            assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"existing_preserved"));
            DWORD actual=0;size=4;assert(!RegQueryValueExW(key,L"1000",NULL,&type,(BYTE*)&actual,&size)&&type==REG_DWORD&&actual==42);
            assert(!RegSetValueExW(key,L"1000",0,REG_SZ,NULL,0));
            assert(!viewer_version_registry(regions[region],viewer)&&!strcmp(viewer_version_state,"existing_preserved"));
            size=0;assert(!RegQueryValueExW(key,L"1000",NULL,&type,NULL,&size)&&type==REG_SZ&&!size);
            assert(!RegDeleteValueW(key,L"1000"));game_preserved(key);
        }
        RegCloseKey(key);assert(!RegDeleteKeyExW(VIEWER_VERSION_ROOT,branch,KEY_WOW64_32KEY,0));
    }
    assert(RemoveDirectoryW(viewer));
    puts("PASS: viewer Interface1000 JP/US/EU two validated candidates, missing-only repair, existing type/value and game preservation, invalid files, registry/write/readback failures and rollback, source preservation");
    return 0;
}
