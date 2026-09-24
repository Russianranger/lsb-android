/* Real window geometry and a partial-failure rollback, no game assets. */
#include <windows.h>
static BOOL fail_position;
static BOOL WINAPI position_fixture(HWND w,HWND after,int x,int y,int cx,int cy,UINT flags){
    if(fail_position){fail_position=FALSE;SetLastError(ERROR_ACCESS_DENIED);return FALSE;}
    return SetWindowPos(w,after,x,y,cx,cy,flags);
}
#define SetWindowPos position_fixture
#define wmain bridge_main
#include "../../windows/client-launch.c"
#undef wmain
#undef SetWindowPos
#include <assert.h>
static void reset(void){
    window_adjusted=game_window_seen=FALSE;borderless_requested=TRUE;
    borderless_state="not_observed";borderless_error=borderless_rollback_error=0;
    memset(&geometry_before,0,sizeof(geometry_before));memset(&geometry_after,0,sizeof(geometry_after));
    setting_present[4]=TRUE;setting_values[4]=1;child_pid=GetCurrentProcessId();
}
static HWND make(const WCHAR *cls,int width,int height){
    RECT r={0,0,width,height};DWORD style=WS_OVERLAPPEDWINDOW|WS_VISIBLE,exstyle=WS_EX_CLIENTEDGE;
    assert(AdjustWindowRectEx(&r,style,FALSE,exstyle));
    HWND w=CreateWindowExW(exstyle,cls,L"Fixture",style,7,11,r.right-r.left,r.bottom-r.top,NULL,NULL,GetModuleHandleW(NULL),NULL);
    assert(w);return w;
}
static void same(GameGeometry a,GameGeometry b){
    assert(EqualRect(&a.outer,&b.outer)&&EqualRect(&a.client,&b.client));
    assert(a.origin.x==b.origin.x&&a.origin.y==b.origin.y&&a.style==b.style&&a.exstyle==b.exstyle);
}
int wmain(void){
    WNDCLASSW cls={0};cls.lpfnWndProc=DefWindowProcW;cls.hInstance=GetModuleHandleW(NULL);cls.lpszClassName=L"FFXiClass";assert(RegisterClassW(&cls));
    for(int small=0;small<2;small++){
        reset();int width=small?960:1280,height=small?540:720;HWND w=make(cls.lpszClassName,width,height);
        window_observation(w,0);assert(game_window_seen&&window_adjusted&&!strcmp(borderless_state,"applied"));
        assert(!borderless_error&&!borderless_rollback_error);
        assert(geometry_before.origin.x>0&&geometry_before.origin.y>0);
        assert(geometry_after.origin.x==0&&geometry_after.origin.y==0);
        assert(geometry_after.client.right==width&&geometry_after.client.bottom==height);
        assert(geometry_after.outer.left==0&&geometry_after.outer.top==0&&geometry_after.outer.right==width&&geometry_after.outer.bottom==height);
        /* No recurring correction if the game changes its own window later. */
        assert(SetWindowPos(w,NULL,13,17,0,0,SWP_NOSIZE|SWP_NOZORDER|SWP_NOACTIVATE));
        GameGeometry changed,after;assert(game_geometry(w,&changed));window_observation(w,0);assert(game_geometry(w,&after));same(changed,after);
        DestroyWindow(w);
    }
    for(int mode=0;mode<5;mode++){
        reset();HWND w=make(mode==3?L"STATIC":cls.lpszClassName,640,480);GameGeometry before,after;
        assert(game_geometry(w,&before));
        if(mode==0)borderless_requested=FALSE;
        if(mode==1)setting_values[4]=0;
        if(mode==2)child_pid++;
        if(mode==4)fail_position=TRUE;
        window_observation(w,0);assert(game_geometry(w,&after));same(before,after);
        if(mode==4)assert(!strcmp(borderless_state,"failed")&&borderless_error==ERROR_ACCESS_DENIED&&!borderless_rollback_error);
        if(mode==2||mode==3)assert(!game_window_seen&&!window_adjusted);
        DestroyWindow(w);
    }
    reset();HWND w=CreateWindowW(cls.lpszClassName,L"Already borderless",WS_POPUP|WS_VISIBLE,0,0,960,540,NULL,NULL,cls.hInstance,NULL);
    assert(w);window_observation(w,0);assert(!strcmp(borderless_state,"applied"));same(geometry_before,geometry_after);DestroyWindow(w);
    puts("PASS: borderless 720p/540p retains full client size, origin zero, one-time only, owned game window only, opt-out/fullscreen preserved, partial failure restored");
    return 0;
}
