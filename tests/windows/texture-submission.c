/* Synthetic reproduction of states seen in the Thor FFXI captures.
 * CI only: no client assets, game hooks or production launch changes. */
#define COBJMACROS
#include <windows.h>
#include <d3d8.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

typedef struct {float x,y,z,rhw;DWORD color;float u,v;} Vertex;
static const DWORD rgb[4]={0x00ff0000,0x0000ff00,0x000000ff,0x00ffffff};
static const WORD rgb565[4]={0xf800,0x07e0,0x001f,0xffff};
static const BYTE alpha[4]={0,136,255,68};
static volatile unsigned path_draws[3];
#define TRY(stage,call) do {hr=(call);if(FAILED(hr)){printf("LSB_TEXTURE_API stage=%s hr=%08lx FAIL\n",stage,(unsigned long)hr);goto done;}} while(0)
#define RELEASE(kind,p) do {if(p)kind##_Release(p);} while(0)

static HRESULT write_pattern(IDirect3DTexture8 *texture,unsigned format,unsigned frame){
    D3DLOCKED_RECT lock;HRESULT hr=IDirect3DTexture8_LockRect(texture,0,&lock,NULL,0);
    if(FAILED(hr))return hr;
    /* Independent encoder: four constant-color quadrants; rotate each update.
     * BC1 endpoints select color zero. BC2 adds explicit 4-bit alpha. */
    const unsigned width=1024,step=format?4:1,bytes=format==1?8:16;
    if(lock.Pitch<(INT)(format?(width/4)*bytes:width*4)){
        IDirect3DTexture8_UnlockRect(texture,0);return E_FAIL;
    }
    for(unsigned y=0;y<width;y+=step)for(unsigned x=0;x<width;x+=step){
        unsigned quadrant=(y>=512)*2+(x>=512),color=(quadrant+frame)%4;
        if(!format){
            DWORD value=rgb[color]|((DWORD)alpha[quadrant]<<24);
            memcpy((BYTE*)lock.pBits+y*lock.Pitch+x*4,&value,4);
        }else{
            DWORD block[4]={0};unsigned index=0;
            if(format==2){block[0]=block[1]=(alpha[quadrant]/17)*0x11111111u;index=2;}
            block[index]=rgb565[color]; /* second endpoint and indices are zero */
            memcpy((BYTE*)lock.pBits+(y/4)*lock.Pitch+(x/4)*bytes,block,bytes);
        }
    }
    return IDirect3DTexture8_UnlockRect(texture,0);
}

static HRESULT tile(IDirect3DDevice8 *d,unsigned panel,unsigned row,unsigned tx,unsigned ty){
    float x=(float)(panel*420+tx*60),y=(float)(row*240+ty*40);
    float u=(float)tx/7,v=(float)ty/6,u1=(float)(tx+1)/7,v1=(float)(ty+1)/6;
    Vertex vertices[]={
        {x-.5f,y-.5f,.5f,1,0x80808080,u,v},{x+59.5f,y-.5f,.5f,1,0x80808080,u1,v},
        {x-.5f,y+39.5f,.5f,1,0x80808080,u,v1},{x+59.5f,y+39.5f,.5f,1,0x80808080,u1,v1}};
    if(panel){
        path_draws[panel]++;
        /* Nonzero MinVertexIndex catches treating indices as relative offsets.
         * Prefix vertices must not be sampled or rendered. */
        Vertex padded[6];memset(padded,0xff,sizeof(padded));memcpy(padded+2,vertices,sizeof(vertices));
        const WORD small[]={2,3,4,5};const DWORD large[]={2,3,4,5};
        return IDirect3DDevice8_DrawIndexedPrimitiveUP(d,D3DPT_TRIANGLESTRIP,2,4,2,
            panel==1?(const void*)small:(const void*)large,panel==1?D3DFMT_INDEX16:D3DFMT_INDEX32,padded,sizeof(Vertex));
    }
    path_draws[0]++;
    return IDirect3DDevice8_DrawPrimitiveUP(d,D3DPT_TRIANGLESTRIP,2,vertices,sizeof(vertices[0]));
}

static DWORD expected_color(unsigned format,unsigned row,unsigned quadrant,unsigned frame){
    DWORD color=rgb[(quadrant+frame)%4];
    if(!row)return color; /* alpha test ALWAYS, blending disabled */
    unsigned a=format==1?255:alpha[quadrant];
    a=(a*256+127)/255;if(a>255)a=255; /* MODULATE2X with diffuse alpha 128 */
    DWORD out=0;
    for(unsigned shift=0;shift<24;shift+=8){
        unsigned src=(color>>shift)&255,dst=(0x204060>>shift)&255;
        unsigned channel=(src*a+dst*(row==1?255-a:255)+127)/255;
        if(channel>255)channel=255;out|=channel<<shift;
    }
    return out;
}

static BOOL matches(DWORD a,DWORD b){
    for(unsigned shift=0;shift<24;shift+=8){
        int difference=(int)((a>>shift)&255)-(int)((b>>shift)&255);
        if(difference < -2 || difference > 2)return FALSE;
    }
    return TRUE;
}

int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,LPWSTR args,int show){
    (void)previous;(void)show;
    HWND window=CreateWindowW(L"STATIC",L"Synthetic texture submissions",WS_POPUP|WS_VISIBLE,0,0,1280,720,NULL,NULL,instance,NULL);
    if(!window)return 10;
    IDirect3D8 *api=Direct3DCreate8(D3D_SDK_VERSION);
    if(!api){DestroyWindow(window);return 11;}
    if(!wcscmp(args,L"--trace")){
        HMODULE trace=LoadLibraryW(L"P:\\startup-trace.dll");
        typedef void (WINAPI *Attach)(IDirect3D8*);
        Attach attach=trace?(Attach)(void*)GetProcAddress(trace,"LsbGraphicsTrace"):NULL;
        if(!attach){IDirect3D8_Release(api);DestroyWindow(window);return 14;}
        attach(api);
    }
    IDirect3DDevice8 *d=NULL;IDirect3DTexture8 *textures[3]={0};
    IDirect3DSurface8 *back=NULL,*readback=NULL;HRESULT hr=S_OK;
    unsigned draws=0,samples=0;unsigned short control=0x003f;
    D3DPRESENT_PARAMETERS p={0};p.Windowed=TRUE;p.SwapEffect=D3DSWAPEFFECT_DISCARD;
    p.BackBufferWidth=1280;p.BackBufferHeight=720;p.hDeviceWindow=window;
    TRY("device",IDirect3D8_CreateDevice(api,0,D3DDEVTYPE_HAL,window,D3DCREATE_HARDWARE_VERTEXPROCESSING,&p,&d));
    __asm__ volatile("fldcw %0"::"m"(control));
    __asm__ volatile("fnstcw %0":"=m"(control));
    /* Reserved bit 6 can read differently; precision/rounding/masks must agree. */
    if((control&0x0f3f)!=0x003f){hr=E_FAIL;goto done;}
    TRY("back",IDirect3DDevice8_GetBackBuffer(d,0,D3DBACKBUFFER_TYPE_MONO,&back));
    D3DSURFACE_DESC desc;TRY("description",IDirect3DSurface8_GetDesc(back,&desc));
    if(desc.Format!=D3DFMT_A8R8G8B8&&desc.Format!=D3DFMT_X8R8G8B8){hr=E_FAIL;goto done;}
    TRY("readback",IDirect3DDevice8_CreateImageSurface(d,1280,720,desc.Format,&readback));
    const D3DFORMAT formats[]={D3DFMT_A8R8G8B8,D3DFMT_DXT1,D3DFMT_DXT3};
    for(unsigned i=0;i<3;i++)TRY("texture",IDirect3DDevice8_CreateTexture(d,1024,1024,1,0,formats[i],D3DPOOL_MANAGED,&textures[i]));
    TRY("fvf",IDirect3DDevice8_SetVertexShader(d,D3DFVF_XYZRHW|D3DFVF_DIFFUSE|D3DFVF_TEX1));
    TRY("lighting",IDirect3DDevice8_SetRenderState(d,D3DRS_LIGHTING,FALSE));
    TRY("fill",IDirect3DDevice8_SetRenderState(d,D3DRS_FILLMODE,D3DFILL_SOLID));
    TRY("cull",IDirect3DDevice8_SetRenderState(d,D3DRS_CULLMODE,D3DCULL_NONE));
    TRY("depth",IDirect3DDevice8_SetRenderState(d,D3DRS_ZENABLE,FALSE));
    TRY("color_write",IDirect3DDevice8_SetRenderState(d,D3DRS_COLORWRITEENABLE,15));
    TRY("color_op",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLOROP,D3DTOP_MODULATE2X));
    TRY("color_arg1",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLORARG1,D3DTA_TEXTURE));
    TRY("color_arg2",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_COLORARG2,D3DTA_DIFFUSE));
    TRY("alpha_op",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_ALPHAOP,D3DTOP_MODULATE2X));
    TRY("alpha_arg1",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_ALPHAARG1,D3DTA_TEXTURE));
    TRY("alpha_arg2",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_ALPHAARG2,D3DTA_DIFFUSE));
    TRY("min",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_MINFILTER,D3DTEXF_POINT));
    TRY("mag",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_MAGFILTER,D3DTEXF_POINT));
    TRY("mip",IDirect3DDevice8_SetTextureStageState(d,0,D3DTSS_MIPFILTER,D3DTEXF_NONE));
    TRY("stage1",IDirect3DDevice8_SetTextureStageState(d,1,D3DTSS_COLOROP,D3DTOP_DISABLE));
    for(unsigned frame=0;frame<4;frame++){
        for(unsigned i=0;i<3;i++)TRY("update",write_pattern(textures[i],i,frame));
        TRY("clear",IDirect3DDevice8_Clear(d,0,NULL,D3DCLEAR_TARGET,0xff204060,1,0));
        TRY("begin",IDirect3DDevice8_BeginScene(d));
        for(unsigned row=0;row<3;row++){
            TRY("alpha_test",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHATESTENABLE,TRUE));
            TRY("alpha_func",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHAFUNC,row?D3DCMP_GREATER:D3DCMP_ALWAYS));
            TRY("alpha_ref",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHAREF,row?0:96));
            TRY("blend",IDirect3DDevice8_SetRenderState(d,D3DRS_ALPHABLENDENABLE,row!=0));
            TRY("src",IDirect3DDevice8_SetRenderState(d,D3DRS_SRCBLEND,D3DBLEND_SRCALPHA));
            TRY("dst",IDirect3DDevice8_SetRenderState(d,D3DRS_DESTBLEND,row==1?D3DBLEND_INVSRCALPHA:D3DBLEND_ONE));
            for(unsigned i=0;i<3;i++){
                TRY("bind",IDirect3DDevice8_SetTexture(d,0,(IDirect3DBaseTexture8*)textures[i]));
                for(unsigned y=0;y<6;y++)for(unsigned x=0;x<7;x++){TRY("draw",tile(d,i,row,x,y));draws++;}
            }
        }
        TRY("end",IDirect3DDevice8_EndScene(d));
        TRY("copy",IDirect3DDevice8_CopyRects(d,back,NULL,0,readback,NULL));
        D3DLOCKED_RECT lock;TRY("lock",IDirect3DSurface8_LockRect(readback,&lock,NULL,D3DLOCK_READONLY));
        BOOL mismatch=FALSE;
        for(unsigned row=0;row<3;row++)for(unsigned i=0;i<3;i++)for(unsigned q=0;q<4;q++){
            unsigned x=i*420+105+(q%2)*210,y=row*240+60+(q/2)*120;
            DWORD actual;memcpy(&actual,(BYTE*)lock.pBits+y*lock.Pitch+x*4,4);
            DWORD expected=expected_color(i,row,q,frame);samples++;
            if(!matches(actual,expected)){
                if(!mismatch)printf("LSB_TEXTURE_PIXEL frame=%u format=%u blend=%u quadrant=%u expected=%06lx actual=%06lx FAIL\n",frame,i,row,q,(unsigned long)(expected&0xffffff),(unsigned long)(actual&0xffffff));
                mismatch=TRUE;
            }
        }
        TRY("unlock",IDirect3DSurface8_UnlockRect(readback));
        if(mismatch){hr=E_FAIL;goto done;}
        TRY("present",IDirect3DDevice8_Present(d,NULL,NULL,NULL,NULL));
        MSG message;while(PeekMessageW(&message,NULL,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}
    }
    printf("LSB_TEXTURE_PATHS up=%u indexed16=%u indexed32=%u\n",path_draws[0],path_draws[1],path_draws[2]);
    printf("LSB_TEXTURE_SUBMISSIONS formats=3 frames=4 draws=%u samples=%u x87=%04x PASS\n",draws,samples,control);
done:
    RELEASE(IDirect3DSurface8,readback);RELEASE(IDirect3DSurface8,back);
    for(unsigned i=0;i<3;i++)RELEASE(IDirect3DTexture8,textures[i]);
    RELEASE(IDirect3DDevice8,d);IDirect3D8_Release(api);DestroyWindow(window);fflush(stdout);
    return FAILED(hr)?12:0;
}
