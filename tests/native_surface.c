#include "../native/presentation/frame.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
    uint32_t good[8]={LSB_MAGIC,1280,720,5120,1000,1,1280*720*4,1}, h[8];
    assert(lsb_frame_valid(good));
    const uint32_t bad[][2]={{0,0},{1,0},{1,1281},{1,0xffffffff},{2,0},{2,721},{3,5119},{6,0},{6,1280*720*4-1},{7,4}};
    for(size_t i=0;i<sizeof(bad)/sizeof(bad[0]);i++){
        memcpy(h,good,sizeof(h));h[bad[i][0]]=bad[i][1];assert(!lsb_frame_valid(h));
    }
    memcpy(h,good,sizeof(h));h[6]=0;h[7]=3;assert(lsb_frame_valid(h));
    h[7]=2;assert(lsb_frame_valid(h));h[6]=4;assert(!lsb_frame_valid(h));
    const uint32_t input[]={0x00112233,0x00ff0000,0x0000ff00,0x000000ff,0xffffffff};
    const uint32_t expected[]={0xff332211,0xff0000ff,0xff00ff00,0xffff0000,0xffffffff};
    uint32_t output[7]={42,0,0,0,0,0,43};lsb_bgra_rgba(output+1,input,5);
    assert(output[0]==42&&output[6]==43);assert(!memcmp(output+1,expected,sizeof(expected)));
    puts("PASS: native Surface bounds, unchanged-frame protocol and exact RGBA pixels");
}
