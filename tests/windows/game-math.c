/* Independent routines validate the observer's oracle and isolation. No game. */
#include <windows.h>
#include <stdio.h>
#include <string.h>
static unsigned events;
static void event(const char *name,DWORD code,DWORD detail){(void)name;(void)code;(void)detail;events++;}
#include "../../windows/game-math.h"
static void *vector(void *out,const void *in,const void *matrix,unsigned dimensions){
    float *o=out;const float *v=in,*m=matrix;
    for(unsigned j=0;j<4;j++){float sum=m[12+j];for(unsigned k=0;k<dimensions;k++)sum+=v[k]*m[k*4+j];o[j]=sum;}
    return out;
}
static void *WINAPI vec2(void *o,const void *v,const void *m){return vector(o,v,m,2);}
static void *WINAPI vec3(void *o,const void *v,const void *m){return vector(o,v,m,3);}
static void *WINAPI matrix(void *out,const void *a,const void *b){
    const float *x=a,*y=b;float tmp[16];
    for(unsigned i=0;i<4;i++)for(unsigned j=0;j<4;j++){
        float sum=0;for(unsigned k=0;k<4;k++)sum+=x[i*4+k]*y[k*4+j];tmp[i*4+j]=sum;
    }
    memcpy(out,tmp,sizeof(tmp));return out;
}
static void *WINAPI wrong(void *o,const void *v,const void *m){vec2(o,v,m);((DWORD*)o)[0]=0x7fc00000;return NULL;}
int wmain(void){
    MathRoutine routines[3]={vec2,vec3,matrix};MathResult result;
    unsigned char before[512] __attribute__((aligned(16))),after[512] __attribute__((aligned(16)));
    /* Box64's FXSAVE32 serializes its 8-byte internal x87 representation and
     * leaves the rest of each slot untouched. Define those bytes before the
     * comparison; uninitialized padding is not saved floating-point state. */
    memset(before,0,sizeof(before));memset(after,0,sizeof(after));
    /* Non-default rounding, nonempty x87 stack, and sticky SSE flags survive. */
    unsigned short cw=0x077f;DWORD mxcsr=0x5fa0;
    __asm__ volatile("fninit; fldcw %0; fld1; fldpi; ldmxcsr %1; fxsave %2"::"m"(cw),"m"(mxcsr),"m"(before):"memory");
    math_run(routines,&result);
    __asm__ volatile("fxsave %0; fninit":"=m"(after)::"memory");
    if(result.samples!=192||result.failures||result.returns){puts("LSB_GAME_MATH oracle FAIL");return 1;}
    BOOL preserved=!memcmp(before,after,5)&&!memcmp(before+24,after+24,4);
    for(unsigned i=0;i<8;i++)if(memcmp(before+32+i*16,after+32+i*16,10))preserved=FALSE;
    if(!preserved){
        for(unsigned i=0;i<160;i++)if(before[i]!=after[i])printf("LSB_GAME_MATH state_byte=%u before=%02x after=%02x\n",i,before[i],after[i]);
        puts("LSB_GAME_MATH floating_state FAIL");return 2;
    }
    routines[0]=wrong;math_run(routines,&result);
    if(result.samples!=192||result.failures!=16||result.returns!=16||result.actual!=0x7fc00000){puts("LSB_GAME_MATH detection FAIL");return 3;}
    BYTE data[32];
    if(gm_read((BYTE*)1,data,sizeof(data))||gm_image((BYTE*)1)||gm_image((BYTE*)GetModuleHandleW(NULL))||gm_file(GetModuleHandleW(NULL))){
        puts("LSB_GAME_MATH identity_guard FAIL");return 4;
    }
    SetLastError(1234);gm_observe(FALSE);gm_observe(FALSE);gm_observe(TRUE);
    if(gm_base||gm_checked||gm_attempts||events!=1||GetLastError()!=1234){puts("LSB_GAME_MATH unsupported_guard FAIL");return 5;}
    puts("LSB_GAME_MATH samples=192 guards=4 fp_preserved=1 corruptions_detected=16 PASS");return 0;
}
