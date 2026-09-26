/* Independent CPU-feature/affine-math regression. No client code or assets.
 * A legacy dispatcher checks the Windows feature API before CPUID. A false
 * 3DNow advertisement can stop it before the usable SSE2 implementation. */
#include <windows.h>
#include <cpuid.h>
#include <xmmintrin.h>
#include <stdio.h>
#include <string.h>

static void x87_affine(float *out,const float *v,const float *m){
    /* Four simultaneous dot products fill all eight x87 stack entries.
     * Pairwise pops target ST(4); repeat calls exercise stack wraparound. */
    __asm__ volatile(
        "flds 0(%1); fmuls 0(%2); flds 0(%1); fmuls 4(%2);"
        "flds 0(%1); fmuls 8(%2); flds 0(%1); fmuls 12(%2);"
        "flds 4(%1); fmuls 16(%2); flds 4(%1); fmuls 20(%2);"
        "flds 4(%1); fmuls 24(%2); flds 4(%1); fmuls 28(%2);"
        "faddp %%st,%%st(4); faddp %%st,%%st(4);"
        "faddp %%st,%%st(4); faddp %%st,%%st(4);"
        "fadds 60(%2); fstps 12(%0); fadds 56(%2); fstps 8(%0);"
        "fadds 52(%2); fstps 4(%0); fadds 48(%2); fstps 0(%0);"
        : : "r"(out),"r"(v),"r"(m) : "memory","st","st(1)","st(2)","st(3)","st(4)","st(5)","st(6)","st(7)");
}

static void sse_affine(float *out,const float *v,const float *m){
    __m128 x=_mm_mul_ps(_mm_set1_ps(v[0]),_mm_loadu_ps(m));
    __m128 y=_mm_mul_ps(_mm_set1_ps(v[1]),_mm_loadu_ps(m+4));
    _mm_storeu_ps(out,_mm_add_ps(_mm_add_ps(x,y),_mm_loadu_ps(m+12)));
}

int main(void){
    unsigned a=0,b=0,c=0,d=0;BOOL mmx=FALSE,sse=FALSE,sse2=FALSE,three=FALSE;
    if(__get_cpuid(1,&a,&b,&c,&d)){mmx=!!(d&bit_MMX);sse=!!(d&bit_SSE);sse2=!!(d&bit_SSE2);}
    if(__get_cpuid(0x80000001,&a,&b,&c,&d))three=!!(d&0x80000000u);
    BOOL api=IsProcessorFeaturePresent(PF_3DNOW_INSTRUCTIONS_AVAILABLE);
    BOOL api_sse2=IsProcessorFeaturePresent(PF_XMMI64_INSTRUCTIONS_AVAILABLE);
    /* An advertised but unusable first choice suppresses later choices. */
    const char *path=api?(three?"3dnow":"x87-fallback"):(sse2?"sse2":"x87");
    printf("LSB_CPU_FEATURES cpuid_3dnow=%u api_3dnow=%u mmx=%u sse=%u sse2=%u api_sse2=%u path=%s %s\n",
        (unsigned)three,(unsigned)api,(unsigned)mmx,(unsigned)sse,(unsigned)sse2,(unsigned)api_sse2,path,api==three?"PASS":"MISMATCH");
    if(!sse2||!api_sse2){puts("LSB_CPU_MATH missing_sse2 FAIL");return 20;}
    unsigned short cw=0x003f;__asm__ volatile("fninit; fldcw %0"::"m"(cw));
    const float m[16]={2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17};
    unsigned samples=0,failed=0;
    for(int i=-4;i<=4;i++){
        float v[2]={(float)i,(float)(7-i)},x[4],s[4];
        x87_affine(x,v,m);sse_affine(s,v,m);
        for(unsigned j=0;j<4;j++){
            /* Integer oracle; chosen inputs/results are exactly representable. */
            float expected=(float)(i*(2+(int)j)+(7-i)*(6+(int)j)+14+(int)j);
            samples+=2;
            if(x[j]!=expected||s[j]!=expected){
                unsigned xb,sb,eb;memcpy(&xb,x+j,4);memcpy(&sb,s+j,4);memcpy(&eb,&expected,4);
                printf("LSB_CPU_MATH case=%d lane=%u expected=%08x x87=%08x sse=%08x FAIL\n",i,j,eb,xb,sb);failed++;
            }
        }
    }
    printf("LSB_CPU_MATH samples=%u failures=%u %s\n",samples,failed,failed?"FAIL":"PASS");
    fflush(stdout);return failed?20:api!=three?10:0;
}
