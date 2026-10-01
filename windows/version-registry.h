#include "patch-version.h"
#ifdef LSB_VERSION_TEST
#define VERSION_ROOT HKEY_CURRENT_USER
#define VERSION_BRANCH L"Software\\LSBVersionTests\\PlayOnline%ls\\Interface"
#else
#define VERSION_ROOT HKEY_LOCAL_MACHINE
#define VERSION_BRANCH L"Software\\PlayOnline%ls\\Interface"
#endif
static const char *version_state="not_checked";
static char client_version[18];
static DWORD version_error,version_rollback_error;

/* Only a missing value may be restored. Existing values of any type are kept.
 * All access is in the selected app-owned prefix's 32-bit registry view. */
static LONG version_registry(const WCHAR *region,const WCHAR *game){
    WCHAR branch[128],path[1100];HKEY key;LONG error;DWORD type=0,size=0;
    version_state="not_checked";client_version[0]=0;version_error=version_rollback_error=0;
    const WCHAR *area=!wcscmp(region,L"0")?L"":!wcscmp(region,L"1")?L"US":!wcscmp(region,L"2")?L"EU":NULL;
    if(!area)return ERROR_INVALID_PARAMETER;
    swprintf(branch,128,VERSION_BRANCH,area);
    error=RegOpenKeyExW(VERSION_ROOT,branch,0,KEY_QUERY_VALUE|KEY_WOW64_32KEY,&key);
    if(!error){error=RegQueryValueExW(key,L"0001",NULL,&type,NULL,&size);RegCloseKey(key);}
    if(!error||error==ERROR_MORE_DATA){version_state="existing_preserved";return 0;}
    if(error!=ERROR_FILE_NOT_FOUND&&error!=ERROR_PATH_NOT_FOUND){version_state="registry_unavailable";version_error=(DWORD)error;return error;}
    if(wcslen(game)>1000){version_state="invalid_path";return ERROR_INVALID_PARAMETER;}
    swprintf(path,1100,L"%ls\\patch.ver",game);
    HANDLE file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
    if(file==INVALID_HANDLE_VALUE){version_state="file_unavailable";version_error=GetLastError();return 0;}
    LARGE_INTEGER length;unsigned char blob[288];DWORD got=0;
    BOOL valid=GetFileSizeEx(file,&length)&&length.QuadPart==sizeof(blob)&&ReadFile(file,blob,sizeof(blob),&got,NULL)&&got==sizeof(blob);
    CloseHandle(file);
    if(!valid||!patch_version_zero(blob,sizeof(blob),client_version)){version_state="format_not_matched";return 0;}
    error=RegCreateKeyExW(VERSION_ROOT,branch,0,NULL,0,KEY_QUERY_VALUE|KEY_SET_VALUE|KEY_WOW64_32KEY,NULL,&key,NULL);
    if(error){version_state="restore_failed";version_error=(DWORD)error;return error;}
    /* Recheck after opening for write; never replace an existing value. */
    error=RegQueryValueExW(key,L"0001",NULL,&type,NULL,&size);
    if(!error||error==ERROR_MORE_DATA){RegCloseKey(key);version_state="existing_preserved";return 0;}
    if(error!=ERROR_FILE_NOT_FOUND){RegCloseKey(key);version_state="restore_failed";version_error=(DWORD)error;return error;}
    error=RegSetValueExW(key,L"0001",0,REG_SZ,(const BYTE*)L"0",2*sizeof(WCHAR));
    if(!error){
        WCHAR value[2]={0};type=0;size=sizeof(value);
        error=RegQueryValueExW(key,L"0001",NULL,&type,(BYTE*)value,&size);
        if(!error&&(type!=REG_SZ||size!=sizeof(value)||value[0]!=L'0'||value[1]))error=ERROR_INVALID_DATA;
        if(error)version_rollback_error=(DWORD)RegDeleteValueW(key,L"0001");
    }
    RegCloseKey(key);version_error=(DWORD)error;version_state=error?"restore_failed":"restored_missing";return error;
}
