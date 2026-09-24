/* Exercise indexed DISCARD/NOOVERWRITE buffers, fixed-function transforms,
 * managed textures, alpha tests/blending, state blocks and render-target reuse.
 * Pixels are sampled before Present; DXVK HUD settings cannot affect them.
 * Fixed diagnostics contain no client assets, names, paths or credentials. */
#include <stdio.h>
#include <string.h>
typedef struct { float x,y,z,rhw; DWORD color; float u,v; } PixelScreenVertex;
typedef struct { float x,y,z; DWORD color; } PixelWorldVertex;
#define PIXEL_SCREEN_FVF (D3DFVF_XYZRHW|D3DFVF_DIFFUSE|D3DFVF_TEX1)
#define PIXEL_WORLD_FVF (D3DFVF_XYZ|D3DFVF_DIFFUSE)
#define PIXEL_TRY(stage,call) do { hr=(call); if(FAILED(hr)){ \
    printf("LSB_D3D8_API mode=%s stage=%s hr=%08lx FAIL\n",mode,stage,(unsigned long)hr); goto done; } } while(0)
#define PIXEL_RELEASE(kind,p) do { if(p)kind##_Release(p); } while(0)

static HRESULT pixel_quad(IDirect3DDevice8 *d,float x,float y,float w,float h,DWORD color) {
    PixelScreenVertex v[]={
        {x-.5f,y-.5f,.5f,1,color,0,0}, {x+w-.5f,y-.5f,.5f,1,color,1,0},
        {x-.5f,y+h-.5f,.5f,1,color,0,1}, {x+w-.5f,y+h-.5f,.5f,1,color,1,1}};
    HRESULT hr=IDirect3DDevice8_SetVertexShader(d,PIXEL_SCREEN_FVF);
    return FAILED(hr)?hr:IDirect3DDevice8_DrawPrimitiveUP(d,D3DPT_TRIANGLESTRIP,2,v,sizeof(v[0]));
}
static int pixel_color_matches(DWORD actual,DWORD expected) {
    for(unsigned shift=0;shift<24;shift+=8) {
        int difference=(int)((actual>>shift)&255)-(int)((expected>>shift)&255);
        if(difference < -2 || difference > 2)return 0;
    }
    return 1; /* Alpha is undefined for an X8 backbuffer. */
}
static int check_pixels(IDirect3D8 *api,HWND window,DWORD behavior,const char *mode) {
    IDirect3DDevice8 *d=NULL; IDirect3DSurface8 *back=NULL,*target=NULL,*pixels=NULL;
    IDirect3DTexture8 *pattern=NULL,*rendered=NULL;
    IDirect3DVertexBuffer8 *vb=NULL; IDirect3DIndexBuffer8 *ib=NULL;
    DWORD block=0; HRESULT hr=S_OK; unsigned samples=0;
    D3DPRESENT_PARAMETERS p={0};p.Windowed=TRUE;p.SwapEffect=D3DSWAPEFFECT_DISCARD;
    p.BackBufferWidth=320;p.BackBufferHeight=240;p.hDeviceWindow=window;
    PIXEL_TRY("device",IDirect3D8_CreateDevice(api,0,D3DDEVTYPE_HAL,window,behavior,&p,&d));
    PIXEL_TRY("backbuffer",IDirect3DDevice8_GetBackBuffer(d,0,D3DBACKBUFFER_TYPE_MONO,&back));
    D3DSURFACE_DESC desc;
    PIXEL_TRY("description",IDirect3DSurface8_GetDesc(back,&desc));
    if(desc.Format!=D3DFMT_X8R8G8B8 && desc.Format!=D3DFMT_A8R8G8B8){
        printf("LSB_D3D8_API mode=%s stage=format hr=%08lx FAIL\n",mode,(unsigned long)E_FAIL);hr=E_FAIL;goto done;}
    PIXEL_TRY("readback",IDirect3DDevice8_CreateImageSurface(d,320,240,desc.Format,&pixels));
    PIXEL_TRY("texture",IDirect3DDevice8_CreateTexture(d,8,8,1,0,D3DFMT_A8R8G8B8,D3DPOOL_MANAGED,&pattern));
    PIXEL_TRY("target_texture",IDirect3DDevice8_CreateTexture(d,64,64,1,D3DUSAGE_RENDERTARGET,desc.Format,D3DPOOL_DEFAULT,&rendered));
    PIXEL_TRY("target_surface",IDirect3DTexture8_GetSurfaceLevel(rendered,0,&target));
    DWORD usage=D3DUSAGE_DYNAMIC|D3DUSAGE_WRITEONLY;
    if(behavior&D3DCREATE_SOFTWARE_VERTEXPROCESSING)usage|=D3DUSAGE_SOFTWAREPROCESSING;
    PIXEL_TRY("vertex_buffer",IDirect3DDevice8_CreateVertexBuffer(d,8*sizeof(PixelWorldVertex),usage,PIXEL_WORLD_FVF,D3DPOOL_DEFAULT,&vb));
    PIXEL_TRY("index_buffer",IDirect3DDevice8_CreateIndexBuffer(d,12*sizeof(WORD),usage,D3DFMT_INDEX16,D3DPOOL_DEFAULT,&ib));
    PIXEL_TRY("lighting",IDirect3DDevice8_SetRenderState(d,D3DRS_LIGHTING,FALSE));
    PIXEL_TRY("culling",IDirect3DDevice8_SetRenderState(d,D3DRS_CULLMODE,D3DCULL_NONE));
    PIXEL_TRY("depth",IDirect3DDevice8_SetRenderState(d,D3DRS_ZENABLE,FALSE));
    PIXEL_TRY("min_filter",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_MINFILTER,D3DTEXF_POINT));
    PIXEL_TRY("mag_filter",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_MAGFILTER,D3DTEXF_POINT));
    PIXEL_TRY("mip_filter",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_MIPFILTER,D3DTEXF_NONE));
    PIXEL_TRY("color_op",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLOROP,D3DTOP_MODULATE));
    PIXEL_TRY("color_arg1",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLORARG1,D3DTA_TEXTURE));
    PIXEL_TRY("color_arg2",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLORARG2,D3DTA_DIFFUSE));
    PIXEL_TRY("alpha_op",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_ALPHAOP,D3DTOP_SELECTARG1));
    PIXEL_TRY("alpha_arg",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_ALPHAARG1,D3DTA_TEXTURE));
    PIXEL_TRY("stage1",IDirect3DDevice8_SetTextureStageState(d,1,D3DTSS_COLOROP,D3DTOP_DISABLE));
    PIXEL_TRY("stateblock",IDirect3DDevice8_CreateStateBlock(d,D3DSBT_ALL,&block));
    for(unsigned frame=0;frame<4;frame++) {
        DWORD solid=(frame&1)?0xff20a060:0xffa04020;
        DWORD tile[4]={0xffff0000,0xff00ff00,0xff0000ff,0xffffffff};
        D3DLOCKED_RECT lock;
        PIXEL_TRY("texture_lock",IDirect3DTexture8_LockRect(pattern,0,&lock,NULL,0));
        for(unsigned y=0;y<8;y++)for(unsigned x=0;x<8;x++)
            ((DWORD *)((BYTE *)lock.pBits+y*lock.Pitch))[x]=tile[((y>=4)*2+(x>=4)+frame)%4];
        PIXEL_TRY("texture_unlock",IDirect3DTexture8_UnlockRect(pattern,0));
        PIXEL_TRY("restore_state",IDirect3DDevice8_ApplyStateBlock(d,block));
        PIXEL_TRY("unbind_target",IDirect3DDevice8_SetTexture(d,0,NULL));
        PIXEL_TRY("set_target",IDirect3DDevice8_SetRenderTarget(d,target,NULL));
        D3DVIEWPORT8 viewport={0,0,64,64,0,1};
        PIXEL_TRY("target_viewport",IDirect3DDevice8_SetViewport(d,&viewport));
        PIXEL_TRY("target_clear",IDirect3DDevice8_Clear(d,0,NULL,D3DCLEAR_TARGET,0xff000000,1,0));
        PIXEL_TRY("target_begin",IDirect3DDevice8_BeginScene(d));
        /* World x/y halves, projection doubles. Neither matrix alone fills
         * the target. Two draws also exercise nonzero base vertex/index. */
        D3DMATRIX identity={0},world={0},projection={0};
        identity._11=identity._22=identity._33=identity._44=1;
        world=identity;world._11=world._22=.5f;
        projection=identity;projection._11=projection._22=2;
        PIXEL_TRY("world",IDirect3DDevice8_SetTransform(d,D3DTS_WORLD,&world));
        PIXEL_TRY("view",IDirect3DDevice8_SetTransform(d,D3DTS_VIEW,&identity));
        PIXEL_TRY("projection",IDirect3DDevice8_SetTransform(d,D3DTS_PROJECTION,&projection));
        PIXEL_TRY("world_fvf",IDirect3DDevice8_SetVertexShader(d,PIXEL_WORLD_FVF));
        PIXEL_TRY("vertex_color",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLOROP,D3DTOP_SELECTARG2));
        for(unsigned half=0;half<2;half++) {
            BYTE *data=NULL;float left=half?0:-1,right=half?1:0;
            PixelWorldVertex vertices[]={{left,1,.5f,solid},{right,1,.5f,solid},{left,-1,.5f,solid},{right,-1,.5f,solid}};
            WORD indices[]={0,1,2,2,1,3};DWORD flags=(frame&1)?0:(half?D3DLOCK_NOOVERWRITE:D3DLOCK_DISCARD);
            PIXEL_TRY("vertex_lock",IDirect3DVertexBuffer8_Lock(vb,half*4*sizeof(PixelWorldVertex),sizeof(vertices),&data,flags));
            memcpy(data,vertices,sizeof(vertices));PIXEL_TRY("vertex_unlock",IDirect3DVertexBuffer8_Unlock(vb));
            PIXEL_TRY("index_lock",IDirect3DIndexBuffer8_Lock(ib,half*6*sizeof(WORD),sizeof(indices),&data,flags));
            memcpy(data,indices,sizeof(indices));PIXEL_TRY("index_unlock",IDirect3DIndexBuffer8_Unlock(ib));
            PIXEL_TRY("stream",IDirect3DDevice8_SetStreamSource(d,0,vb,sizeof(PixelWorldVertex)));
            PIXEL_TRY("indices",IDirect3DDevice8_SetIndices(d,ib,half*4));
            PIXEL_TRY("indexed_draw",IDirect3DDevice8_DrawIndexedPrimitive(d,D3DPT_TRIANGLELIST,0,4,half*6,2));
        }
        PIXEL_TRY("target_end",IDirect3DDevice8_EndScene(d));
        PIXEL_TRY("restore_back",IDirect3DDevice8_SetRenderTarget(d,back,NULL));
        viewport.Width=320;viewport.Height=240;
        PIXEL_TRY("back_viewport",IDirect3DDevice8_SetViewport(d,&viewport));
        PIXEL_TRY("back_clear",IDirect3DDevice8_Clear(d,0,NULL,D3DCLEAR_TARGET,0xff000000,1,0));
        PIXEL_TRY("back_begin",IDirect3DDevice8_BeginScene(d));
        PIXEL_TRY("plain_quad",pixel_quad(d,0,0,160,120,solid));
        PIXEL_TRY("texture_mode",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLOROP,D3DTOP_MODULATE));
        PIXEL_TRY("pattern_bind",IDirect3DDevice8_SetTexture(d,0,(IDirect3DBaseTexture8 *)pattern));
        PIXEL_TRY("pattern_quad",pixel_quad(d,160,0,160,120,0xffffffff));
        PIXEL_TRY("rendered_bind",IDirect3DDevice8_SetTexture(d,0,(IDirect3DBaseTexture8 *)rendered));
        PIXEL_TRY("rendered_quad",pixel_quad(d,0,120,160,120,0xffffffff));
        /* Blend blue over the target; a transparent white quad is rejected. */
        PIXEL_TRY("blend_base",pixel_quad(d,160,120,160,120,0xffffffff));
        PIXEL_TRY("blend_unbind",IDirect3DDevice8_SetTexture(d,0,NULL));
        PIXEL_TRY("blend_color",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLOROP,D3DTOP_SELECTARG2));
        PIXEL_TRY("blend_alpha",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_ALPHAARG1,D3DTA_DIFFUSE));
        PIXEL_TRY("blend_enable",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHABLENDENABLE,TRUE));
        PIXEL_TRY("blend_src",IDirect3DDevice8_SetRenderState(d,D3DRS_SRCBLEND,D3DBLEND_SRCALPHA));
        PIXEL_TRY("blend_dst",IDirect3DDevice8_SetRenderState(d,D3DRS_DESTBLEND,D3DBLEND_INVSRCALPHA));
        PIXEL_TRY("blend_quad",pixel_quad(d,160,120,160,120,0x800000ff));
        PIXEL_TRY("blend_disable",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHABLENDENABLE,FALSE));
        PIXEL_TRY("test_enable",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHATESTENABLE,TRUE));
        PIXEL_TRY("test_func",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHAFUNC,D3DCMP_GREATER));
        PIXEL_TRY("test_ref",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHAREF,128));
        PIXEL_TRY("test_quad",pixel_quad(d,160,120,160,120,0x00ffffff));
        PIXEL_TRY("back_end",IDirect3DDevice8_EndScene(d));
        PIXEL_TRY("copy_readback",IDirect3DDevice8_CopyRects(d,back,NULL,0,pixels,NULL));
        PIXEL_TRY("pixel_lock",IDirect3DSurface8_LockRect(pixels,&lock,NULL,D3DLOCK_READONLY));
        int mismatch=0;
        for(unsigned panel=0;panel<4;panel++)for(unsigned sample=0;sample<4;sample++) {
            unsigned x=(panel%2)*160+40+(sample%2)*80,y=(panel/2)*120+30+(sample/2)*60;
            DWORD expected=solid;
            if(panel==1)expected=tile[(sample+frame)%4];
            if(panel==3) {
                expected=0;
                for(unsigned shift=0;shift<24;shift+=8) {
                    unsigned source=shift==0?255:0,destination=(solid>>shift)&255;
                    expected|=((source*128+destination*127+127)/255)<<shift;
                }
            }
            DWORD actual=((DWORD *)((BYTE *)lock.pBits+y*lock.Pitch))[x];samples++;
            if(!pixel_color_matches(actual,expected)) {
                if(!mismatch)printf("LSB_D3D8_PIXEL mode=%s frame=%u panel=%u sample=%u expected=%06lx actual=%06lx FAIL\n",
                    mode,frame,panel,sample,(unsigned long)(expected&0xffffff),(unsigned long)(actual&0xffffff));
                mismatch=1;
            }
        }
        PIXEL_TRY("pixel_unlock",IDirect3DSurface8_UnlockRect(pixels));
        if(mismatch){hr=E_FAIL;goto done;}
        PIXEL_TRY("present",IDirect3DDevice8_Present(d,NULL,NULL,NULL,NULL));
        MSG message;while(PeekMessageW(&message,NULL,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}
    }
    printf("LSB_D3D8_PIXELS mode=%s frames=4 samples=%u PASS\n",mode,samples);
done:
    if(block)IDirect3DDevice8_DeleteStateBlock(d,block);
    PIXEL_RELEASE(IDirect3DIndexBuffer8,ib);PIXEL_RELEASE(IDirect3DVertexBuffer8,vb);
    PIXEL_RELEASE(IDirect3DSurface8,pixels);PIXEL_RELEASE(IDirect3DSurface8,target);PIXEL_RELEASE(IDirect3DSurface8,back);
    PIXEL_RELEASE(IDirect3DTexture8,rendered);PIXEL_RELEASE(IDirect3DTexture8,pattern);PIXEL_RELEASE(IDirect3DDevice8,d);
    fflush(stdout);return FAILED(hr)?12:0;
}
