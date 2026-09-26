/* Synthetic, integer-exact oracle for three ordinary row-vector/matrix APIs.
 * No client code or data. Only the supplied routines perform floating math. */
#include <stdint.h>
typedef void *(WINAPI *MathRoutine)(void*,const void*,const void*);
typedef struct {DWORD samples,failures,first_case,expected,actual,returns;} MathResult;
static uint32_t math_bits(int value){
    if(!value)return 0;
    uint32_t sign=value<0?0x80000000u:0,n=(uint32_t)(value<0?-value:value),power=0;
    for(uint32_t p=n;p>1;p>>=1)power++;
    return sign|((power+127)<<23)|((n<<(23-power))&0x7fffff);
}
static void math_compare(MathResult *r,const uint32_t *out,const int *expected,unsigned n,unsigned id){
    for(unsigned i=0;i<n;i++){
        uint32_t want=math_bits(expected[i]);r->samples++;
        /* Both signs of an exact zero are acceptable. */
        if(out[i]!=want&&(want||(out[i]&0x7fffffff))){
            if(!r->failures){r->first_case=id*16+i;r->expected=want;r->actual=out[i];}r->failures++;
        }
    }
}
static __attribute__((noinline)) void math_run(const MathRoutine routines[3],MathResult *r){
    memset(r,0,sizeof(*r));
    const int m[16]={2,-3,4,1,5,6,-2,3,-4,2,7,-1,11,-5,3,2};
    uint32_t storage[20] __attribute__((aligned(16))),v[4],out[16];
    for(unsigned alignment=0;alignment<2;alignment++){
        uint32_t *matrix=storage+alignment;
        for(unsigned i=0;i<16;i++)matrix[i]=math_bits(m[i]);
        for(unsigned kind=0;kind<2;kind++)for(int c=0;c<8;c++){
            int input[3]={c-4,7-c,2*c-5},expected[4];
            for(unsigned i=0;i<3;i++)v[i]=math_bits(input[i]);
            for(unsigned j=0;j<4;j++)expected[j]=input[0]*m[j]+input[1]*m[4+j]+(kind?input[2]*m[8+j]:0)+m[12+j];
            memset(out,0xcc,sizeof(out));
            if(routines[kind](out,v,matrix)!=out)r->returns++;
            math_compare(r,out,expected,4,alignment*16+kind*8+c);
        }
    }
    /* Exercise separate output and both permitted alias directions, including
     * squaring in place. These take distinct paths in older math libraries. */
    const int b[16]={1,2,0,-1,-3,1,4,2,2,0,-2,3,4,-1,2,1};
    for(unsigned alias=0;alias<4;alias++){
        uint32_t left[16],right[16];int expected[16];
        const int *second=alias==3?m:b;
        for(unsigned i=0;i<16;i++){left[i]=math_bits(m[i]);right[i]=math_bits(second[i]);}
        for(unsigned y=0;y<4;y++)for(unsigned x=0;x<4;x++){
            expected[y*4+x]=0;for(unsigned k=0;k<4;k++)expected[y*4+x]+=m[y*4+k]*second[k*4+x];
        }
        uint32_t *dest=alias==0?out:alias==2?right:left;
        if(routines[2](dest,left,alias==3?left:right)!=dest)r->returns++;
        math_compare(r,dest,expected,16,32+alias);
    }
}
/* Pure kernels run on a disposable thread. Do not use FXRSTOR to repair the
 * game thread: translators need not implement its full x87 context correctly.
 * The job must outlive the thread; the observer uses one static job per process. */
typedef struct {
    MathRoutine routines[3];MathResult result;
    unsigned short cw,observed_cw;DWORD mxcsr,observed_mxcsr;
    HMODULE module_hold;
} MathJob;
static DWORD WINAPI math_worker(void *context){
    MathJob *job=context;
    __asm__ volatile("fninit; fldcw %2; ldmxcsr %3; fnstcw %0; stmxcsr %1"
        :"=m"(job->observed_cw),"=m"(job->observed_mxcsr)
        :"m"(job->cw),"m"(job->mxcsr):"memory");
    math_run(job->routines,&job->result);
    if(job->module_hold)FreeLibrary(job->module_hold);
    return 0;
}
static HANDLE math_start(MathJob *job,const MathRoutine routines[3],HMODULE module){
    memset(job,0,sizeof(*job));memcpy(job->routines,routines,sizeof(job->routines));
    __asm__ volatile("fnstcw %0; stmxcsr %1":"=m"(job->cw),"=m"(job->mxcsr));
    if(module&&!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,(LPCWSTR)module,&job->module_hold))return NULL;
    HANDLE thread=CreateThread(NULL,0,math_worker,job,0,NULL);
    if(!thread&&job->module_hold){FreeLibrary(job->module_hold);job->module_hold=NULL;}
    return thread;
}
