/* Integer-only summaries of submitted vertices. No FP state changes, vertex
 * dumps, texture reads, or pointer values in the output. FVF constants are
 * numeric so this pure helper can also be tested on the build host. */
#include <stdint.h>
#include <string.h>
typedef struct {
    uint32_t vertices,position_nonfinite,rhw_bad,uv_vertices,uv_nonfinite;
    uint32_t uv_large,uv_zero,color_vertices,alpha_zero,alpha_opaque;
    uint32_t range_mask,low[6],high[6]; /* x,y,z,rhw,u,v: finite float bits */
} DrawStats;
typedef struct {uint32_t position_bytes,color_offset,uv_offset,uv_components;} DrawLayout;
static int ds_layout(uint32_t fvf,uint32_t stride,DrawLayout *out){
    DrawLayout v={0,UINT32_MAX,UINT32_MAX,0};
    uint32_t p=fvf&0x00e,offset;
    /* Reject shader handles, reserved flags and blended-position layouts. */
    if((fvf&0x0000f021)||stride>256||(p!=2&&p!=4))return 0;
    v.position_bytes=offset=p==4?16:12;
    if(fvf&0x10)offset+=12;
    if(fvf&0x40){v.color_offset=offset;offset+=4;}
    if(fvf&0x80)offset+=4;
    uint32_t textures=(fvf>>8)&15;if(textures>8)return 0;
    if(textures<8&&(fvf>>(16+2*textures)))return 0;
    for(uint32_t i=0;i<textures;i++){
        const uint32_t dimensions[4]={2,3,4,1};
        uint32_t n=dimensions[(fvf>>(16+2*i))&3];
        if(!i){v.uv_offset=offset;v.uv_components=n;}
        offset+=n*4;
    }
    if(offset>stride)return 0;
    *out=v;return 1;
}
static uint32_t ds_bits(const unsigned char *p){uint32_t n;memcpy(&n,p,4);return n;}
static int ds_finite(uint32_t n){return (n&0x7f800000)!=0x7f800000;}
static uint32_t ds_order(uint32_t n){return n&0x80000000?~n:n^0x80000000;}
static void ds_range(DrawStats *s,uint32_t slot,uint32_t bits){
    if(!ds_finite(bits))return;
    if(!(bits&0x7fffffff))bits=0; /* normalize signed zero */
    uint32_t mask=1u<<slot;
    if(!(s->range_mask&mask)){s->low[slot]=s->high[slot]=bits;s->range_mask|=mask;}
    else{
        if(ds_order(bits)<ds_order(s->low[slot]))s->low[slot]=bits;
        if(ds_order(bits)>ds_order(s->high[slot]))s->high[slot]=bits;
    }
}
static void ds_vertex(DrawStats *s,const unsigned char *data,const DrawLayout *v){
    s->vertices++;
    for(uint32_t i=0;i<v->position_bytes/4;i++){
        uint32_t n=ds_bits(data+i*4);
        if(!ds_finite(n))s->position_nonfinite++;
        if(i==3&&((n&0x80000000)||!(n&0x7fffffff)||!ds_finite(n)))s->rhw_bad++;
        ds_range(s,i,n);
    }
    if(v->color_offset!=UINT32_MAX){
        uint32_t alpha=ds_bits(data+v->color_offset)>>24;
        s->color_vertices++;s->alpha_zero+=alpha==0;s->alpha_opaque+=alpha==255;
    }
    if(v->uv_offset!=UINT32_MAX){
        uint32_t u=ds_bits(data+v->uv_offset),w=v->uv_components>1?ds_bits(data+v->uv_offset+4):0;
        s->uv_vertices++;
        s->uv_nonfinite+=!ds_finite(u)+(v->uv_components>1&&!ds_finite(w));
        s->uv_large+=(ds_finite(u)&&(u&0x7fffffff)>0x41800000)||
                     (v->uv_components>1&&ds_finite(w)&&(w&0x7fffffff)>0x41800000);
        s->uv_zero+=!(u&0x7fffffff)&&!(w&0x7fffffff);
        ds_range(s,4,u);if(v->uv_components>1)ds_range(s,5,w);
    }
}
/* Spread the bounded sample across the whole draw, including its last vertex. */
static uint32_t ds_sample_index(uint32_t i,uint32_t samples,uint32_t count){
    return samples>1?(uint32_t)((uint64_t)i*(count-1)/(samples-1)):0;
}
