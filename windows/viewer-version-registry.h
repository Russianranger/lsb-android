#include "patch-version.h"
#ifdef LSB_VIEWER_VERSION_TEST
#define VIEWER_VERSION_ROOT HKEY_CURRENT_USER
#define VIEWER_VERSION_BRANCH L"Software\\LSBViewerVersionTests\\PlayOnline%ls\\Interface"
#else
#define VIEWER_VERSION_ROOT HKEY_LOCAL_MACHINE
#define VIEWER_VERSION_BRANCH L"Software\\PlayOnline%ls\\Interface"
#endif
static const char *viewer_version_state="not_checked",*viewer_version_candidate="none";
static char viewer_version[18];
static DWORD viewer_version_error,viewer_version_rollback_error;

/* The viewer uses its own Interface\\1000 value, separate from FFXI's 0001.
 * Restore only a missing value in the staged prefix's 32-bit view. The value
 * must be uniquely validated against the unchanged full viewer patch.ver;
 * existing values of every type are left untouched. */
static LONG viewer_version_registry(const WCHAR *region,const WCHAR *viewer){
    WCHAR branch[128],path[1100];HKEY key;LONG error;DWORD type=0,size=0;
    viewer_version_state="not_checked";viewer_version_candidate="none";viewer_version[0]=0;
    viewer_version_error=viewer_version_rollback_error=0;
    const WCHAR *area=!wcscmp(region,L"JP")?L"":!wcscmp(region,L"US")?L"US":!wcscmp(region,L"EU")?L"EU":NULL;
    if(!area||!viewer[0]||wcslen(viewer)>1000){viewer_version_state="invalid_path";viewer_version_error=ERROR_INVALID_PARAMETER;return ERROR_INVALID_PARAMETER;}
    swprintf(branch,128,VIEWER_VERSION_BRANCH,area);
    error=RegOpenKeyExW(VIEWER_VERSION_ROOT,branch,0,KEY_QUERY_VALUE|KEY_WOW64_32KEY,&key);
    if(!error){error=RegQueryValueExW(key,L"1000",NULL,&type,NULL,&size);RegCloseKey(key);}
    if(!error||error==ERROR_MORE_DATA){viewer_version_state="existing_preserved";return 0;}
    if(error!=ERROR_FILE_NOT_FOUND&&error!=ERROR_PATH_NOT_FOUND){
        viewer_version_state="registry_unavailable";viewer_version_error=(DWORD)error;return error;
    }
    swprintf(path,1100,L"%ls%lspatch.ver",viewer,viewer[0]&&viewer[wcslen(viewer)-1]==L'\\'?L"":L"\\");
    HANDLE file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
    if(file==INVALID_HANDLE_VALUE){viewer_version_state="file_unavailable";viewer_version_error=GetLastError();return 0;}
    LARGE_INTEGER length;unsigned char blob[288];DWORD got=0;
    BOOL sized=GetFileSizeEx(file,&length),read=FALSE;
    if(!sized)viewer_version_error=GetLastError();
    if(sized&&length.QuadPart==sizeof(blob)){
        read=ReadFile(file,blob,sizeof(blob),&got,NULL);
        if(!read)viewer_version_error=GetLastError();
        else if(got!=sizeof(blob))viewer_version_error=ERROR_READ_FAULT;
    }
    CloseHandle(file);
    if(viewer_version_error){viewer_version_state="file_unavailable";return 0;}
    const char *matched=NULL;
    if(!sized||!read||got!=sizeof(blob)||!patch_version_viewer(blob,sizeof(blob),viewer_version,&matched)){
        viewer_version_state="format_not_matched";return 0;
    }
    /* The decoder accepts only these two exact candidates, and rejects any
     * ambiguous match. Never use arbitrary decoded/registry bytes as a key. */
    const WCHAR *value=NULL;
    if(matched&&!strcmp(matched,"0")){value=L"0";viewer_version_candidate="zero";}
    else if(matched&&!strcmp(matched,"001b1394")){value=L"001b1394";viewer_version_candidate="official_installer";}
    if(!value){viewer_version[0]=0;viewer_version_state="format_not_matched";return 0;}
    error=RegCreateKeyExW(VIEWER_VERSION_ROOT,branch,0,NULL,0,KEY_QUERY_VALUE|KEY_SET_VALUE|KEY_WOW64_32KEY,NULL,&key,NULL);
    if(error){viewer_version_state="restore_failed";viewer_version_error=(DWORD)error;return error;}
    /* Recheck immediately before writing; a value created during validation
     * is preserved too. The staged viewer is not running during this step. */
    type=0;size=0;error=RegQueryValueExW(key,L"1000",NULL,&type,NULL,&size);
    if(!error||error==ERROR_MORE_DATA){
        RegCloseKey(key);viewer_version[0]=0;viewer_version_candidate="none";
        viewer_version_state="existing_preserved";return 0;
    }
    if(error!=ERROR_FILE_NOT_FOUND){RegCloseKey(key);viewer_version_state="restore_failed";viewer_version_error=(DWORD)error;return error;}
    DWORD bytes=(DWORD)(wcslen(value)+1)*sizeof(WCHAR);
    error=RegSetValueExW(key,L"1000",0,REG_SZ,(const BYTE*)value,bytes);
    if(!error){
        WCHAR actual[9]={0};type=0;size=sizeof(actual);
        error=RegQueryValueExW(key,L"1000",NULL,&type,(BYTE*)actual,&size);
        if(!error&&(type!=REG_SZ||size!=bytes||memcmp(actual,value,bytes)))error=ERROR_INVALID_DATA;
        if(error)viewer_version_rollback_error=(DWORD)RegDeleteValueW(key,L"1000");
    }
    RegCloseKey(key);viewer_version_error=(DWORD)error;
    viewer_version_state=error?"restore_failed":"restored_missing";return error;
}
