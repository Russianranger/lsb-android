/* Owned, credential-free second-chance exception fixture. */
#include <windows.h>
#include <stdio.h>
int wmain(int argc, WCHAR **argv) {
    (void)argv;
    SetErrorMode(argc>1?0:SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);
    printf("LSB_FEX_CRASH bits=%u error_mode=%lu raising=c0000094\n",(unsigned)(8*sizeof(void*)),GetErrorMode());
    fflush(stdout);
    RaiseException(EXCEPTION_INT_DIVIDE_BY_ZERO,EXCEPTION_NONCONTINUABLE,0,NULL);
    return 99;
}
