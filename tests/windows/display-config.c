/* Run only in the synthetic CI prefix. Exercise the real Windows registry API. */
#define wmain bridge_main
#include "../../windows/client-launch.c"
#undef wmain
#include <assert.h>

int wmain(void){
    const WCHAR *languages[]={L"0",L"1",L"2"};
    const WCHAR *branches[]={L"Software\\PlayOnline\\Square\\FinalFantasyXI",L"Software\\PlayOnlineUS\\SquareEnix\\FinalFantasyXI",L"Software\\PlayOnlineEU\\SquareEnix\\FinalFantasyXI"};
    for(int region=0;region<3;region++){
        RegDeleteKeyExW(HKEY_LOCAL_MACHINE,branches[region],KEY_WOW64_32KEY,0);
        assert(display_config(languages[region])==0&&config_checked);
        for(int i=0;i<5;i++)assert(setting_added[i]);
        HKEY key;assert(RegOpenKeyExW(HKEY_LOCAL_MACHINE,branches[region],0,KEY_ALL_ACCESS|KEY_WOW64_32KEY,&key)==0);
        DWORD width=960;assert(RegSetValueExW(key,L"0001",0,REG_DWORD,(BYTE*)&width,4)==0);
        assert(display_config(languages[region])==0&&setting_values[0]==960);
        for(int i=0;i<5;i++)assert(!setting_added[i]);
        /* A malformed existing value must neither be overwritten nor result in
         * other missing fields being partially initialized. */
        assert(RegDeleteValueW(key,L"0002")==0);
        assert(RegSetValueExW(key,L"0034",0,REG_SZ,(BYTE*)L"x",4)==0);
        assert(display_config(languages[region])==ERROR_INVALID_DATA&&!config_checked);
        DWORD type=0,size=4,value=0;
        assert(RegQueryValueExW(key,L"0002",NULL,&type,(BYTE*)&value,&size)==ERROR_FILE_NOT_FOUND);
        size=4;assert(RegQueryValueExW(key,L"0001",NULL,&type,(BYTE*)&value,&size)==0&&value==960);
        size=4;assert(RegQueryValueExW(key,L"0034",NULL,&type,(BYTE*)&value,&size)==0&&type==REG_SZ);
        RegCloseKey(key);assert(RegDeleteKeyExW(HKEY_LOCAL_MACHINE,branches[region],KEY_WOW64_32KEY,0)==0);
    }
    puts("PASS: display defaults, existing preference preservation and invalid-type atomicity for JP/US/EU");return 0;
}
