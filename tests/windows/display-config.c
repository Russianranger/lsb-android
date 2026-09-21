/* Synthetic CI prefix only: real Wine registry reads, writes and recovery. */
#include <windows.h>
#include <string.h>
static BOOL fail_next_display_write;
static LONG WINAPI fixture_set(HKEY key,LPCWSTR name,DWORD reserved,DWORD type,const BYTE *data,DWORD size){
    if(fail_next_display_write&&!wcscmp(name,L"0003")&&type==REG_DWORD&&size==4&&*(const DWORD*)data==1280){
        fail_next_display_write=FALSE;return ERROR_ACCESS_DENIED;
    }
    return RegSetValueExW(key,name,reserved,type,data,size);
}
#define RegSetValueExW fixture_set
#define wmain bridge_main
#include "../../windows/client-launch.c"
#undef wmain
#undef RegSetValueExW
#include <assert.h>

static const DWORD device_values[]={640,480,512,512,0};
static void seed(HKEY key){for(int i=0;i<5;i++)assert(display_write(key,i,device_values[i],TRUE)==0);}
static void verify(HKEY key,const DWORD *expected,BOOL populated){
    DWORD actual[5];BOOL present[5];assert(display_read(key,actual,present)==0);
    for(int i=0;i<5;i++)assert(present[i]==populated&&(!populated||actual[i]==expected[i]));
}
int wmain(void){
    const WCHAR *languages[]={L"0",L"1",L"2"};
    const WCHAR *branches[]={L"Software\\PlayOnline\\Square\\FinalFantasyXI",L"Software\\PlayOnlineUS\\SquareEnix\\FinalFantasyXI",L"Software\\PlayOnlineEU\\SquareEnix\\FinalFantasyXI"};
    const WCHAR *backups[]={L"Software\\LSBAndroid\\DisplayBackup\\JP",L"Software\\LSBAndroid\\DisplayBackup\\US",L"Software\\LSBAndroid\\DisplayBackup\\EU"};
    for(int region=0;region<3;region++){
        RegDeleteKeyExW(HKEY_LOCAL_MACHINE,branches[region],KEY_WOW64_32KEY,0);
        RegDeleteKeyExW(HKEY_LOCAL_MACHINE,backups[region],KEY_WOW64_32KEY,0);
        assert(display_config(languages[region],L"preserve")==0&&config_checked);
        for(int i=0;i<5;i++)assert(!setting_present[i]);
        assert(display_config(languages[region],L"restore")==ERROR_NOT_FOUND);
        HKEY key;assert(RegCreateKeyExW(HKEY_LOCAL_MACHINE,branches[region],0,NULL,0,KEY_ALL_ACCESS|KEY_WOW64_32KEY,NULL,&key,NULL)==0);
        seed(key);
        assert(display_config(languages[region],L"preserve")==0);verify(key,device_values,TRUE);
        assert(display_config(languages[region],L"windowed720")==0&&config_checked&&config_backup_ready);
        verify(key,windowed_values,TRUE);
        for(int i=0;i<5;i++)assert(setting_changed[i]&&setting_before_present[i]&&setting_before[i]==device_values[i]);
        DWORD width=960;assert(RegSetValueExW(key,L"0001",0,REG_DWORD,(BYTE*)&width,4)==0);
        assert(display_config(languages[region],L"preserve")==0&&setting_values[0]==960);
        assert(display_config(languages[region],L"windowed720")==0);verify(key,windowed_values,TRUE);
        /* Reapplying must not replace the original fullscreen backup. */
        assert(display_config(languages[region],L"restore")==0);verify(key,device_values,TRUE);
        fail_next_display_write=TRUE;
        assert(display_config(languages[region],L"windowed720")==ERROR_ACCESS_DENIED&&!config_checked&&!config_rollback_error);
        assert(!fail_next_display_write);verify(key,device_values,TRUE);
        /* An app stop halfway through application still leaves a complete backup. */
        assert(display_write(key,0,1280,TRUE)==0);
        assert(display_config(languages[region],L"restore")==0);verify(key,device_values,TRUE);
        /* Existing unexpected types are never silently overwritten. */
        assert(RegSetValueExW(key,L"0034",0,REG_SZ,(BYTE*)L"x",4)==0);
        assert(display_config(languages[region],L"windowed720")==ERROR_INVALID_DATA&&!config_checked);
        assert(!setting_before_present[4]&&setting_before[4]==0); /* No invalid string bytes in receipts. */
        DWORD type=0,size=4,value=0;assert(RegQueryValueExW(key,L"0001",NULL,&type,(BYTE*)&value,&size)==0&&value==640);
        seed(key);
        /* Interrupted backup construction is retried before touching the game. */
        HKEY backup;assert(RegOpenKeyExW(HKEY_LOCAL_MACHINE,backups[region],0,KEY_ALL_ACCESS|KEY_WOW64_32KEY,&backup)==0);
        assert(RegDeleteValueW(backup,L"Complete")==0);assert(display_write(backup,0,999,TRUE)==0);RegCloseKey(backup);
        assert(display_config(languages[region],L"windowed720")==0);
        assert(display_config(languages[region],L"restore")==0);verify(key,device_values,TRUE);
        /* Saving and restoring missing values restores absence, not a zero DWORD. */
        RegCloseKey(key);assert(RegDeleteKeyExW(HKEY_LOCAL_MACHINE,branches[region],KEY_WOW64_32KEY,0)==0);
        assert(RegDeleteKeyExW(HKEY_LOCAL_MACHINE,backups[region],KEY_WOW64_32KEY,0)==0);
        assert(display_config(languages[region],L"windowed720")==0);
        assert(display_config(languages[region],L"restore")==0);
        assert(RegOpenKeyExW(HKEY_LOCAL_MACHINE,branches[region],0,KEY_ALL_ACCESS|KEY_WOW64_32KEY,&key)==0);
        verify(key,NULL,FALSE);RegCloseKey(key);
        assert(RegDeleteKeyExW(HKEY_LOCAL_MACHINE,branches[region],KEY_WOW64_32KEY,0)==0);
        assert(RegDeleteKeyExW(HKEY_LOCAL_MACHINE,backups[region],KEY_WOW64_32KEY,0)==0);
    }
    /* Seed the Thor's exact starting values for the subsequent launch tests. */
    HKEY key;assert(RegCreateKeyExW(HKEY_LOCAL_MACHINE,branches[1],0,NULL,0,KEY_ALL_ACCESS|KEY_WOW64_32KEY,NULL,&key,NULL)==0);seed(key);RegCloseKey(key);
    puts("PASS: JP/US/EU fullscreen override, preserve, original restoration, write rollback and interruption recovery");return 0;
}
