#include <assert.h>
#include <stdio.h>
#include "../windows/draw-stats.h"
int main(void){
    DrawLayout v;DrawStats s={0};
    assert(ds_layout(0x144,28,&v));
    const uint32_t a[]={0xc1200000,0x41a00000,0x3f000000,0x3f800000,0x00ffffff,0x80000000,0};
    const uint32_t b[]={0x42c80000,0xc1a00000,0x3f800000,0xbf800000,0xffffffff,0x41a00000,0x7fc00000};
    ds_vertex(&s,(const unsigned char*)a,&v);ds_vertex(&s,(const unsigned char*)b,&v);
    assert(s.vertices==2&&s.position_nonfinite==0&&s.rhw_bad==1);
    assert(s.uv_vertices==2&&s.uv_nonfinite==1&&s.uv_large==1&&s.uv_zero==1);
    assert(s.color_vertices==2&&s.alpha_zero==1&&s.alpha_opaque==1);
    assert(s.range_mask==63&&s.low[0]==a[0]&&s.high[0]==b[0]);
    assert(s.low[1]==b[1]&&s.high[1]==a[1]&&s.low[4]==0&&s.high[4]==b[5]);
    assert(!ds_layout(0x144,24,&v)&&!ds_layout(0x146,32,&v));
    assert(!ds_layout(0x80000144,28,&v)&&!ds_layout(0x144,257,&v));
    assert(ds_layout(0x112,32,&v)&&v.color_offset==UINT32_MAX&&v.uv_offset==24);
    assert(ds_layout(0x30144,24,&v)&&v.uv_components==1);
    assert(ds_layout(0x10144,32,&v)&&v.uv_components==3);
    assert(ds_layout(0x20144,36,&v)&&v.uv_components==4);
    assert(ds_sample_index(0,64,1000)==0&&ds_sample_index(63,64,1000)==999);
    assert(ds_sample_index(0,1,1)==0&&ds_sample_index(1,3,3)==1);
    puts("PASS: draw summaries detect UV/alpha/RHW faults, finite bounds, layouts and distributed samples");
    return 0;
}
