/* Opt-in, process-local D3D8 metadata. No texture/vertex bytes leave this DLL.
 * Observe existing writes instead of locking GPU buffers for readback. */
#include <d3d8.h>
#include <stdint.h>
#define GT_BUFFERS 32
#define GT_BYTES 32768
#define GT_DRAWS 65536
#define GT_FRAMES 1024
static struct {
    IDirect3D8Vtbl api;
    IDirect3DDevice8Vtbl dev;
    IDirect3DVertexBuffer8Vtbl vb;
    IDirect3D8Vtbl *api_table;
    IDirect3DDevice8Vtbl *dev_table;
    IDirect3DVertexBuffer8Vtbl *vb_table;
    IDirect3DDevice8 *device;
    DWORD owner,frame,draws,up,buffered,vertices,nonfinite,extreme,rhw_bad,unavailable,failures;
    DWORD samples,states,first_error,buffer_vertices;
    volatile LONG other_thread_draws;
    ULONGLONG started;
    BOOL active;
} gt;
static SRWLOCK gt_lock=SRWLOCK_INIT;
static struct {
    IDirect3DVertexBuffer8 *object;
    UINT size,offset,bytes;
    BYTE *mapped;
    BOOL valid;
    BYTE data[GT_BYTES];
} gt_buffers[GT_BUFFERS];
static void gt_emit(const char *tag,const DWORD *v,unsigned count){
    DWORD saved=GetLastError(),written;char row[512];
    int n=snprintf(row,sizeof(row),"lsb-d3d8-v1 %s",tag);
    for(unsigned i=0;i<count&&n>0&&n<(int)sizeof(row)-12;i++)
        n+=snprintf(row+n,sizeof(row)-n," %08lx",(unsigned long)v[i]);
    if(n>0&&n<(int)sizeof(row)-1){row[n++]='\n';WriteFile(GetStdHandle(STD_ERROR_HANDLE),row,n,&written,NULL);}
    SetLastError(saved);
}
#define GT_EMIT(tag,...) do {const DWORD values[]={__VA_ARGS__};gt_emit(tag,values,sizeof(values)/sizeof(values[0]));} while(0)
static void gt_coverage(DWORD reason){
    static volatile LONG seen;
    LONG bit=1L<<reason;if(!(InterlockedOr(&seen,bit)&bit))GT_EMIT("coverage",reason);
}
static BOOL gt_slot(void **slot,void *expected,void *value){
    DWORD old;if(!VirtualProtect(slot,sizeof(*slot),PAGE_READWRITE,&old))return FALSE;
    BOOL ok=InterlockedCompareExchangePointer(slot,value,expected)==expected;
    DWORD unused;VirtualProtect(slot,sizeof(*slot),old,&unused);return ok;
}
static BOOL gt_owner(void){return gt.active&&GetCurrentThreadId()==gt.owner;}
static BOOL gt_sampling(void){return gt_owner()&&(gt.frame%32==0)&&gt.samples<8;}
static int gt_find(IDirect3DVertexBuffer8 *v){
    for(int i=0;i<GT_BUFFERS;i++)if(gt_buffers[i].object==v)return i;return -1;
}
static HRESULT WINAPI gt_vb_lock(IDirect3DVertexBuffer8 *self,UINT offset,UINT size,BYTE **data,DWORD flags){
    HRESULT hr=gt.vb.Lock(self,offset,size,data,flags);DWORD saved=GetLastError();
    AcquireSRWLockExclusive(&gt_lock);int i=gt_find(self);
    if(i>=0){
        gt_buffers[i].valid=FALSE;gt_buffers[i].mapped=NULL;
        if(SUCCEEDED(hr)&&data&&*data&&gt_sampling()&&offset<=gt_buffers[i].size){
            UINT available=gt_buffers[i].size-offset;
            if(size&&size<available)available=size;
            gt_buffers[i].offset=offset;gt_buffers[i].bytes=available>GT_BYTES?GT_BYTES:available;
            gt_buffers[i].mapped=*data;
        }
    }
    ReleaseSRWLockExclusive(&gt_lock);SetLastError(saved);return hr;
}
static HRESULT WINAPI gt_vb_unlock(IDirect3DVertexBuffer8 *self){
    DWORD saved=GetLastError();
    AcquireSRWLockExclusive(&gt_lock);int i=gt_find(self);
    if(i>=0&&gt_buffers[i].mapped){
        memcpy(gt_buffers[i].data,gt_buffers[i].mapped,gt_buffers[i].bytes);
        gt_buffers[i].mapped=NULL;gt_buffers[i].valid=TRUE;
    }
    ReleaseSRWLockExclusive(&gt_lock);SetLastError(saved);
    HRESULT hr=gt.vb.Unlock(self);saved=GetLastError();
    if(FAILED(hr)){AcquireSRWLockExclusive(&gt_lock);i=gt_find(self);if(i>=0)gt_buffers[i].valid=FALSE;ReleaseSRWLockExclusive(&gt_lock);}
    SetLastError(saved);return hr;
}
static ULONG WINAPI gt_vb_release(IDirect3DVertexBuffer8 *self){
    ULONG refs=gt.vb.Release(self);DWORD saved=GetLastError();
    if(!refs){AcquireSRWLockExclusive(&gt_lock);int i=gt_find(self);if(i>=0)memset(&gt_buffers[i],0,sizeof(gt_buffers[i]));ReleaseSRWLockExclusive(&gt_lock);}
    SetLastError(saved);return refs;
}
static HRESULT WINAPI gt_create_vb(IDirect3DDevice8 *self,UINT length,DWORD usage,DWORD fvf,D3DPOOL pool,IDirect3DVertexBuffer8 **out){
    HRESULT hr=gt.dev.CreateVertexBuffer(self,length,usage,fvf,pool,out);DWORD saved=GetLastError();
    if(gt_owner()&&self==gt.device&&SUCCEEDED(hr)&&out&&*out){
        IDirect3DVertexBuffer8Vtbl *table=(IDirect3DVertexBuffer8Vtbl*)(*out)->lpVtbl;
        if(!gt.vb_table){
            gt.vb=*table;gt.vb_table=table;
            if(!gt_slot((void**)&table->Lock,(void*)gt.vb.Lock,(void*)gt_vb_lock)||
               !gt_slot((void**)&table->Unlock,(void*)gt.vb.Unlock,(void*)gt_vb_unlock)||
               !gt_slot((void**)&table->Release,(void*)gt.vb.Release,(void*)gt_vb_release))gt_coverage(1);
        }
        if(table==gt.vb_table){
            AcquireSRWLockExclusive(&gt_lock);int i=gt_find(*out);
            if(i<0)for(int j=0;j<GT_BUFFERS;j++)if(!gt_buffers[j].object){i=j;break;}
            if(i>=0){memset(&gt_buffers[i],0,sizeof(gt_buffers[i]));gt_buffers[i].object=*out;gt_buffers[i].size=length;}
            ReleaseSRWLockExclusive(&gt_lock);
            if(i<0)gt_coverage(2);
        }else gt_coverage(3);
    }
    SetLastError(saved);return hr;
}
static BOOL gt_finite(DWORD bits){return (bits&0x7f800000)!=0x7f800000;}
static void gt_vertex(const BYTE *data,DWORD fvf){
    DWORD bits[4]={0};BOOL rhw=(fvf&D3DFVF_POSITION_MASK)==D3DFVF_XYZRHW;
    memcpy(bits,data,rhw?16:12);gt.vertices++;
    for(unsigned i=0;i<(rhw?4u:3u);i++)if(!gt_finite(bits[i]))gt.nonfinite++;
    /* Extreme screen coordinates are a clue only, not automatic invalidity. */
    if(rhw&&((bits[0]&0x7fffffff)>0x49800000||(bits[1]&0x7fffffff)>0x49800000))gt.extreme++;
    if(rhw&&((bits[3]&0x80000000)||!(bits[3]&0x7fffffff)||!gt_finite(bits[3])))gt.rhw_bad++;
}
static BOOL gt_position(DWORD fvf,UINT stride){
    DWORD p=fvf&D3DFVF_POSITION_MASK;
    return fvf<0x10000&&stride>=((p==D3DFVF_XYZRHW)?16u:12u)&&stride<=256&&
        (p==D3DFVF_XYZ||p==D3DFVF_XYZRHW||p==D3DFVF_XYZB1||p==D3DFVF_XYZB2||p==D3DFVF_XYZB3||p==D3DFVF_XYZB4||p==D3DFVF_XYZB5);
}
static void gt_state(IDirect3DDevice8 *self,DWORD fvf){
    if(gt.states>=16)return;
    D3DRENDERSTATETYPE keys[]={D3DRS_FILLMODE,D3DRS_CULLMODE,D3DRS_ZENABLE,D3DRS_ZFUNC,D3DRS_ALPHATESTENABLE,D3DRS_ALPHAFUNC,D3DRS_ALPHAREF,D3DRS_ALPHABLENDENABLE,D3DRS_SRCBLEND,D3DRS_DESTBLEND,D3DRS_COLORWRITEENABLE};
    DWORD values[24]={gt.frame,fvf<0x10000?fvf:0};
    for(unsigned i=0;i<11;i++){values[i+2]=0xffffffff;gt.dev.GetRenderState(self,keys[i],&values[i+2]);}
    unsigned short cw=0;__asm__ volatile("fnstcw %0":"=m"(cw));values[13]=cw;
    __asm__ volatile("stmxcsr %0":"=m"(values[14]));
    D3DMATRIX matrix;values[15]=0;
    if(SUCCEEDED(gt.dev.GetTransform(self,D3DTS_PROJECTION,&matrix))){
        DWORD bits[16];memcpy(bits,&matrix,sizeof(bits));for(unsigned i=0;i<16;i++)if(!gt_finite(bits[i]))values[15]++;
    }else values[15]=0xffffffff;
    D3DTEXTURESTAGESTATETYPE texture_keys[]={D3DTSS_COLOROP,D3DTSS_COLORARG1,D3DTSS_COLORARG2,D3DTSS_ALPHAOP};
    for(unsigned i=0;i<4;i++){values[16+i]=0xffffffff;gt.dev.GetTextureStageState(self,0,texture_keys[i],&values[16+i]);}
    IDirect3DBaseTexture8 *texture=NULL;
    if(SUCCEEDED(gt.dev.GetTexture(self,0,&texture))&&texture){
        values[20]=1;
        if(IDirect3DBaseTexture8_GetType(texture)==D3DRTYPE_TEXTURE){
            D3DSURFACE_DESC desc;if(SUCCEEDED(IDirect3DTexture8_GetLevelDesc((IDirect3DTexture8*)texture,0,&desc))){values[21]=desc.Width;values[22]=desc.Height;values[23]=desc.Format;}
        }
        IDirect3DBaseTexture8_Release(texture);
    }
    /* Retain distinct states, not 16 copies of the first text draw. */
    static DWORD seen[16][23];
    for(DWORD i=0;i<gt.states;i++)if(!memcmp(seen[i],values+1,sizeof(seen[i])))return;
    memcpy(seen[gt.states++],values+1,sizeof(seen[0]));gt_emit("state",values,24);
}
static void gt_sample(IDirect3DDevice8 *self,const void *data,UINT first,UINT count,UINT stride,BOOL indexed){
    DWORD saved=GetLastError(),fvf=0;
    gt.samples++;
    if(FAILED(gt.dev.GetVertexShader(self,&fvf))){gt.unavailable++;SetLastError(saved);return;}
    gt_state(self,fvf);
    if(count>8)count=8;
    if(data){
        if(gt_position(fvf,stride)&&count&&first<=UINT32_MAX/stride&&count<=UINT32_MAX/stride-first){
            const BYTE *v=(const BYTE*)data+(size_t)first*stride;
            for(UINT i=0;i<count;i++)gt_vertex(v+(size_t)i*stride,fvf);
        }else gt.unavailable++;
    }else{
        IDirect3DVertexBuffer8 *buffer=NULL;
        if(SUCCEEDED(gt.dev.GetStreamSource(self,0,&buffer,&stride))&&buffer){
            UINT base=0;IDirect3DIndexBuffer8 *indices=NULL;
            if(indexed&&SUCCEEDED(gt.dev.GetIndices(self,&indices,&base))&&indices)IDirect3DIndexBuffer8_Release(indices);
            uint64_t offset=((uint64_t)first+base)*stride;
            AcquireSRWLockShared(&gt_lock);int i=gt_find(buffer);
            if(i>=0&&gt_buffers[i].valid&&gt_position(fvf,stride)&&count&&offset>=gt_buffers[i].offset&&
               offset-gt_buffers[i].offset+(uint64_t)(count-1)*stride+((fvf&D3DFVF_POSITION_MASK)==D3DFVF_XYZRHW?16u:12u)<=gt_buffers[i].bytes){
                const BYTE *v=gt_buffers[i].data+(size_t)(offset-gt_buffers[i].offset);
                for(UINT n=0;n<count;n++)gt_vertex(v+(size_t)n*stride,fvf);gt.buffer_vertices+=count;
            }else gt.unavailable++;
            ReleaseSRWLockShared(&gt_lock);IDirect3DVertexBuffer8_Release(buffer);
        }else gt.unavailable++;
    }
    SetLastError(saved);
}
static UINT gt_vertex_count(D3DPRIMITIVETYPE type,UINT n){
    if(n>UINT32_MAX/3)return 0;
    switch(type){case D3DPT_POINTLIST:return n;case D3DPT_LINELIST:return n*2;case D3DPT_LINESTRIP:return n?n+1:0;
        case D3DPT_TRIANGLELIST:return n*3;case D3DPT_TRIANGLESTRIP:case D3DPT_TRIANGLEFAN:return n?n+2:0;default:return 0;}
}
static void gt_finish(DWORD reason);
static void gt_draw(HRESULT hr,BOOL up){
    if(!gt.active)return;
    if(GetCurrentThreadId()!=gt.owner){InterlockedIncrement(&gt.other_thread_draws);return;}
    gt.draws++;if(up)gt.up++;else gt.buffered++;
    if(FAILED(hr)){gt.failures++;if(!gt.first_error)gt.first_error=(DWORD)hr;}
    if(gt.draws>=GT_DRAWS)gt_finish(3);
}
static HRESULT WINAPI gt_dp(IDirect3DDevice8 *s,D3DPRIMITIVETYPE t,UINT first,UINT n){
    HRESULT hr=gt.dev.DrawPrimitive(s,t,first,n);DWORD e=GetLastError();
    if(s==gt.device){if(SUCCEEDED(hr)&&gt_sampling())gt_sample(s,NULL,first,gt_vertex_count(t,n),0,FALSE);gt_draw(hr,FALSE);}
    SetLastError(e);return hr;
}
static HRESULT WINAPI gt_dip(IDirect3DDevice8 *s,D3DPRIMITIVETYPE t,UINT min,UINT vertices,UINT start,UINT n){
    HRESULT hr=gt.dev.DrawIndexedPrimitive(s,t,min,vertices,start,n);DWORD e=GetLastError();
    if(s==gt.device){if(SUCCEEDED(hr)&&gt_sampling())gt_sample(s,NULL,min,vertices,0,TRUE);gt_draw(hr,FALSE);}
    SetLastError(e);return hr;
}
static HRESULT WINAPI gt_up(IDirect3DDevice8 *s,D3DPRIMITIVETYPE t,UINT n,const void *v,UINT stride){
    HRESULT hr=gt.dev.DrawPrimitiveUP(s,t,n,v,stride);DWORD e=GetLastError();
    if(s==gt.device){if(SUCCEEDED(hr)&&gt_sampling())gt_sample(s,v,0,gt_vertex_count(t,n),stride,FALSE);gt_draw(hr,TRUE);}
    SetLastError(e);return hr;
}
static HRESULT WINAPI gt_iup(IDirect3DDevice8 *s,D3DPRIMITIVETYPE t,UINT min,UINT vertices,UINT n,const void *indices,D3DFORMAT format,const void *v,UINT stride){
    HRESULT hr=gt.dev.DrawIndexedPrimitiveUP(s,t,min,vertices,n,indices,format,v,stride);DWORD e=GetLastError();
    if(s==gt.device){if(SUCCEEDED(hr)&&gt_sampling())gt_sample(s,v,min,vertices,stride,FALSE);gt_draw(hr,TRUE);}
    SetLastError(e);return hr;
}
static void gt_report(void){GT_EMIT("frame",gt.frame,gt.draws,gt.up,gt.buffered,gt.vertices,gt.nonfinite,gt.extreme,gt.rhw_bad,gt.unavailable,gt.failures,gt.first_error,gt.buffer_vertices,(DWORD)gt.other_thread_draws);}
static HRESULT WINAPI gt_present(IDirect3DDevice8 *s,const RECT *a,const RECT *b,HWND w,const RGNDATA *r){
    HRESULT hr=gt.dev.Present(s,a,b,w,r);DWORD e=GetLastError();
    if(s==gt.device&&gt.active){
        if(!gt_owner()){if(GetTickCount64()-gt.started>=60000)gt_finish(2);SetLastError(e);return hr;}
        gt.frame++;gt.samples=0;
        if(gt.frame==1||gt.frame%32==0)gt_report();
        if(gt.frame>=GT_FRAMES)gt_finish(1);else if(GetTickCount64()-gt.started>=60000)gt_finish(2);
    }
    SetLastError(e);return hr;
}
static ULONG WINAPI gt_device_release(IDirect3DDevice8 *s){
    ULONG refs=gt.dev.Release(s);DWORD e=GetLastError();
    if(!refs&&s==gt.device&&gt.active)gt_finish(4);
    SetLastError(e);return refs;
}
#define GT_DEVICE_SLOTS(X) X(Release,gt_device_release) X(CreateVertexBuffer,gt_create_vb) X(DrawPrimitive,gt_dp) X(DrawIndexedPrimitive,gt_dip) X(DrawPrimitiveUP,gt_up) X(DrawIndexedPrimitiveUP,gt_iup) X(Present,gt_present)
static void gt_finish(DWORD reason){
    if(!InterlockedExchange((volatile LONG*)&gt.active,FALSE))return;gt_report();BOOL restored=TRUE;
#define RESTORE(name,fn) restored=gt_slot((void**)&gt.dev_table->name,(void*)fn,(void*)gt.dev.name)&&restored;
    GT_DEVICE_SLOTS(RESTORE)
#undef RESTORE
    if(gt.vb_table){
        restored=gt_slot((void**)&gt.vb_table->Lock,(void*)gt_vb_lock,(void*)gt.vb.Lock)&&restored;
        restored=gt_slot((void**)&gt.vb_table->Unlock,(void*)gt_vb_unlock,(void*)gt.vb.Unlock)&&restored;
        restored=gt_slot((void**)&gt.vb_table->Release,(void*)gt_vb_release,(void*)gt.vb.Release)&&restored;
    }
    GT_EMIT("complete",reason,gt.frame,gt.draws,restored);
}
static HRESULT WINAPI gt_create_device(IDirect3D8 *s,UINT adapter,D3DDEVTYPE type,HWND window,DWORD flags,D3DPRESENT_PARAMETERS *p,IDirect3DDevice8 **out){
    HRESULT hr=gt.api.CreateDevice(s,adapter,type,window,flags,p,out);DWORD e=GetLastError();
    if(SUCCEEDED(hr)&&out&&*out&&!gt.device){
        gt.device=*out;gt.dev_table=(IDirect3DDevice8Vtbl*)(*out)->lpVtbl;gt.dev=*gt.dev_table;
        gt.owner=GetCurrentThreadId();gt.started=GetTickCount64();gt.active=TRUE;
        GT_EMIT("device",flags,p?p->BackBufferWidth:0,p?p->BackBufferHeight:0);
#define INSTALL(name,fn) if(!gt_slot((void**)&gt.dev_table->name,(void*)gt.dev.name,(void*)fn))gt_coverage(4);
        GT_DEVICE_SLOTS(INSTALL)
#undef INSTALL
        gt_slot((void**)&gt.api_table->CreateDevice,(void*)gt_create_device,(void*)gt.api.CreateDevice);
    }
    SetLastError(e);return hr;
}
static void gt_attach(IDirect3D8 *api){
    DWORD saved=GetLastError();
    if(api&&!gt.api_table){
        gt.api_table=(IDirect3D8Vtbl*)api->lpVtbl;gt.api=*gt.api_table;
        if(!gt_slot((void**)&gt.api_table->CreateDevice,(void*)gt.api.CreateDevice,(void*)gt_create_device))gt_coverage(5);
    }
    SetLastError(saved);
}
