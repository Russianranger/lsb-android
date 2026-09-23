/* App-owned finite PE32 proof: native Wine host must be ARM64, not emulated AMD64. */
#include <windows.h>
#include <stdio.h>
int wmain(void) {
    typedef BOOL (WINAPI *machine_fn)(HANDLE,USHORT *,USHORT *);
    machine_fn machine=(machine_fn)(void *)GetProcAddress(GetModuleHandleW(L"kernel32.dll"),"IsWow64Process2");
    USHORT process=0,native=0;
    if(sizeof(void *)!=4||!machine||!machine(GetCurrentProcess(),&process,&native)||process!=0x014c||native!=0xaa64)return 21;
    HKEY key;unsigned long long isar0=0,isar1=0,ctr=0;DWORD size=8,type=0;
    if(RegOpenKeyExW(HKEY_LOCAL_MACHINE,L"HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",0,KEY_READ|KEY_WOW64_64KEY,&key))return 22;
    LONG result=RegQueryValueExW(key,L"CP 4030",NULL,&type,(BYTE *)&isar0,&size);
    if(result||type!=REG_QWORD||size!=8||!((isar0>>16)&15)){RegCloseKey(key);return 23;}
    size=8;result=RegQueryValueExW(key,L"CP 4031",NULL,&type,(BYTE *)&isar1,&size);
    if(result||type!=REG_QWORD||size!=8){RegCloseKey(key);return 24;}
    size=8;result=RegQueryValueExW(key,L"CP 5801",NULL,&type,(BYTE *)&ctr,&size);RegCloseKey(key);
    if(result||type!=REG_QWORD||size!=8)return 25;
    printf("LSB_FEX_HOST isar0=%016llx isar1=%016llx ctr=%016llx\n",isar0,isar1,ctr);
    printf("LSB_FEX_CHECK bits=32 process=%04x native=%04x PASS\n",process,native);
    return 0;
}
