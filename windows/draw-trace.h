/* Broader UP-draw coverage alongside the original first-eight-draw samples.
 * Four time windows retain later scene evidence without unbounded logging. */
#include "draw-stats.h"
#define DT_GROUPS 24
#define DT_PHASES 4
#define DT_DRAWS 1024
#define DT_VERTICES 64
#define DT_KEYS 23
static struct {
    DWORD key[DT_KEYS],first,last,draws,omitted,bad_indices,unsupported;
    DrawStats stats;
    BOOL used,dirty;
} dt_groups[DT_PHASES*DT_GROUPS];
static DWORD dt_calls,dt_limits;
static void dt_limit(DWORD reason){
    DWORD bit=1u<<reason;
    if(!(dt_limits&bit)){dt_limits|=bit;GT_EMIT("batch_limit",gt.frame,reason);}
}
static void dt_report(void){
    for(DWORD i=0;i<DT_PHASES*DT_GROUPS;i++){
        if(!dt_groups[i].dirty)continue;
        DWORD descriptor[DT_KEYS+1]={i};memcpy(descriptor+1,dt_groups[i].key,sizeof(dt_groups[i].key));
        gt_emit("batch",descriptor,DT_KEYS+1);
        DrawStats *s=&dt_groups[i].stats;
        GT_EMIT("batch_stats",i,dt_groups[i].first,dt_groups[i].last,dt_groups[i].draws,
            s->vertices,dt_groups[i].omitted,dt_groups[i].bad_indices,dt_groups[i].unsupported,
            s->position_nonfinite,s->rhw_bad,s->uv_vertices,s->uv_nonfinite,s->uv_large,s->uv_zero,
            s->color_vertices,s->alpha_zero,s->alpha_opaque);
        GT_EMIT("batch_bounds",i,s->range_mask,s->low[0],s->high[0],s->low[1],s->high[1],
            s->low[2],s->high[2],s->low[3],s->high[3],s->low[4],s->high[4],s->low[5],s->high[5]);
        dt_groups[i].dirty=FALSE;
    }
}
static void dt_up(IDirect3DDevice8 *self,D3DPRIMITIVETYPE type,UINT primitives,
    const void *data,UINT stride,const void *indices,D3DFORMAT index_format,UINT min,UINT vertices,BOOL indexed){
    if(!gt_owner()||gt.frame%32)return;
    if(dt_calls++>=DT_DRAWS){dt_limit(1);return;}
    DWORD saved=GetLastError(),key[DT_KEYS]={0};
    ULONGLONG ms=GetTickCount64()-gt.started;
    key[0]=ms<15000?0:ms<30000?1:ms<60000?2:3;
    if(FAILED(gt.dev.GetVertexShader(self,&key[1]))){dt_limit(2);SetLastError(saved);return;}
    DrawLayout layout={0};BOOL layout_ok=ds_layout(key[1],stride,&layout);
    if(!layout_ok)key[1]=0; /* Never export a shader handle as an FVF. */
    key[2]=stride;key[3]=type;key[4]=indexed;
    IDirect3DBaseTexture8 *texture=NULL;
    if(SUCCEEDED(gt.dev.GetTexture(self,0,&texture))){
        if(texture){
            key[5]=IDirect3DBaseTexture8_GetType(texture);
            if(key[5]==D3DRTYPE_TEXTURE){
                D3DSURFACE_DESC desc;
                if(SUCCEEDED(IDirect3DTexture8_GetLevelDesc((IDirect3DTexture8*)texture,0,&desc))){key[6]=desc.Width;key[7]=desc.Height;key[8]=desc.Format;}
            }
            IDirect3DBaseTexture8_Release(texture);
        }
    }else key[5]=UINT32_MAX;
    const D3DRENDERSTATETYPE render[]={D3DRS_ALPHATESTENABLE,D3DRS_ALPHAFUNC,D3DRS_ALPHAREF,D3DRS_ALPHABLENDENABLE,D3DRS_SRCBLEND,D3DRS_DESTBLEND};
    const D3DTEXTURESTAGESTATETYPE stages[]={D3DTSS_COLOROP,D3DTSS_ALPHAOP,D3DTSS_TEXCOORDINDEX,D3DTSS_TEXTURETRANSFORMFLAGS,
        D3DTSS_COLORARG1,D3DTSS_COLORARG2,D3DTSS_ALPHAARG1,D3DTSS_ALPHAARG2};
    for(unsigned i=0;i<6;i++){key[9+i]=UINT32_MAX;gt.dev.GetRenderState(self,render[i],&key[9+i]);}
    for(unsigned i=0;i<8;i++){key[15+i]=UINT32_MAX;gt.dev.GetTextureStageState(self,0,stages[i],&key[15+i]);}
    int group=-1;
    for(unsigned i=key[0]*DT_GROUPS;i<(key[0]+1)*DT_GROUPS;i++){
        if(!dt_groups[i].used){group=i;dt_groups[i].used=TRUE;memcpy(dt_groups[i].key,key,sizeof(key));dt_groups[i].first=gt.frame;break;}
        if(!memcmp(dt_groups[i].key,key,sizeof(key))){group=i;break;}
    }
    if(group<0){dt_limit(3);SetLastError(saved);return;}
    dt_groups[group].dirty=TRUE;dt_groups[group].last=gt.frame;dt_groups[group].draws++;
    UINT count=gt_vertex_count(type,primitives),samples=count>DT_VERTICES?DT_VERTICES:count;
    if(!data||!count||!layout_ok||(indexed&&(!indices||
        (index_format!=D3DFMT_INDEX16&&index_format!=D3DFMT_INDEX32)))){
        dt_groups[group].unsupported++;SetLastError(saved);return;
    }
    dt_groups[group].omitted+=count-samples;
    for(UINT i=0;i<samples;i++){
        uint32_t n=ds_sample_index(i,samples,count);
        if(indexed){
            unsigned bytes=index_format==D3DFMT_INDEX16?2:4;uint64_t offset=(uint64_t)n*bytes;
            if(offset+bytes>UINT32_MAX-(uintptr_t)indices){dt_groups[group].bad_indices++;continue;}
            uint32_t value=0;memcpy(&value,(const BYTE*)indices+(size_t)offset,bytes);n=value;
            if(n<min||(uint64_t)n>=(uint64_t)min+vertices){dt_groups[group].bad_indices++;continue;}
        }
        uint64_t offset=(uint64_t)n*stride;
        if(offset+stride>UINT32_MAX-(uintptr_t)data){dt_groups[group].unsupported++;continue;}
        ds_vertex(&dt_groups[group].stats,(const BYTE*)data+(size_t)offset,&layout);
    }
    SetLastError(saved);
}
