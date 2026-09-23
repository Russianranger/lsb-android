#include <stdint.h>
#include <stdio.h>
static unsigned check(uint32_t value,unsigned form){
    unsigned char result;float f;double d;
#define CHECK(store) __asm__ volatile("fninit; fld1; cmpl $0,%1; " store "; setne %0; fninit" : "=qm"(result) : "r"(value),"m"(f),"m"(d) : "memory","cc","st")
    switch(form){
    case 0:CHECK("fsts %2");break;
    case 1:CHECK("fstps %2");break;
    case 2:CHECK("fstl %3");break;
    default:CHECK("fstpl %3");break;
    }
#undef CHECK
    return result;
}
int main(void){
    unsigned failures=0;
    const uint32_t inputs[]={0,1,0xffffffffu};
    for(unsigned form=0;form<4;form++)for(unsigned i=0;i<3;i++){
        unsigned expected=inputs[i]!=0,actual=check(inputs[i],form);
        if(actual!=expected){printf("LSB_X87_FLAGS form=%u case=%u expected=%u actual=%u MISMATCH\n",form,i,expected,actual);failures++;}
    }
    printf("LSB_X87_FLAGS samples=12 failures=%u %s\n",failures,failures?"FAIL":"PASS");
    return failures?21:0;
}
