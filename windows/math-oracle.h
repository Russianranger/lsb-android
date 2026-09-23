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
    unsigned char fp[512] __attribute__((aligned(16)));
    unsigned short cw;memset(r,0,sizeof(*r));
    __asm__ volatile("fxsave %0; fnstcw %1; fninit; fldcw %1":"=m"(fp),"=m"(cw)::"memory");
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
    __asm__ volatile("fxrstor %0"::"m"(fp):"memory","st","st(1)","st(2)","st(3)","st(4)","st(5)","st(6)","st(7)","xmm0","xmm1","xmm2","xmm3","xmm4","xmm5","xmm6","xmm7");
}
