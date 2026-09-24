/* One startup adjustment, never a per-frame scan. No titles or handles logged. */
static BOOL borderless_requested=TRUE,window_adjusted;
static const char *borderless_state="not_observed";
static DWORD borderless_error,borderless_rollback_error;
typedef struct { RECT outer,client; POINT origin; LONG style,exstyle; } GameGeometry;
static GameGeometry geometry_before,geometry_after;
static BOOL game_geometry(HWND window,GameGeometry *g){
    g->origin.x=g->origin.y=0;
    g->style=GetWindowLongW(window,GWL_STYLE);g->exstyle=GetWindowLongW(window,GWL_EXSTYLE);
    return GetWindowRect(window,&g->outer)&&GetClientRect(window,&g->client)&&ClientToScreen(window,&g->origin);
}
static BOOL game_style(HWND window,int index,LONG value){
    SetLastError(0);return SetWindowLongW(window,index,value)!=0||GetLastError()==0;
}
static void fit_game_window(HWND window){
    if(window_adjusted)return;
    window_adjusted=TRUE;
    if(!borderless_requested){borderless_state="disabled";return;}
    /* Keep exclusive/fullscreen and unknown registry modes intact. Preserve
     * profiles still permit this independent, reversible window preference. */
    if(!setting_present[4]||setting_values[4]!=1){borderless_state="not_windowed";return;}
    if(!game_geometry(window,&geometry_before)){borderless_error=GetLastError();borderless_state="failed";return;}
    geometry_after=geometry_before;
    if((geometry_before.style&WS_CHILD)||GetMenu(window)||geometry_before.client.right<=0||geometry_before.client.bottom<=0){
        borderless_state="unsupported_window";return;
    }
    LONG style=(geometry_before.style&~(WS_CAPTION|WS_THICKFRAME|WS_SYSMENU|WS_MINIMIZEBOX|WS_MAXIMIZEBOX))|WS_POPUP;
    LONG exstyle=geometry_before.exstyle&~(WS_EX_DLGMODALFRAME|WS_EX_WINDOWEDGE|WS_EX_CLIENTEDGE|WS_EX_STATICEDGE);
    BOOL ok=game_style(window,GWL_STYLE,style)&&game_style(window,GWL_EXSTYLE,exstyle)&&
        SetWindowPos(window,NULL,0,0,geometry_before.client.right,geometry_before.client.bottom,
                     SWP_FRAMECHANGED|SWP_NOACTIVATE|SWP_NOZORDER|SWP_NOOWNERZORDER);
    if(!ok)borderless_error=GetLastError();
    if(ok)ok=game_geometry(window,&geometry_after)&&geometry_after.origin.x==0&&geometry_after.origin.y==0&&
        geometry_after.client.right==geometry_before.client.right&&geometry_after.client.bottom==geometry_before.client.bottom&&
        geometry_after.style==style&&geometry_after.exstyle==exstyle;
    if(ok){borderless_state="applied";return;}
    if(!borderless_error)borderless_error=ERROR_INVALID_DATA;
    borderless_state="failed";
    /* Restore both style and original outer geometry on any partial failure. */
    if(!game_style(window,GWL_STYLE,geometry_before.style))borderless_rollback_error=GetLastError();
    if(!game_style(window,GWL_EXSTYLE,geometry_before.exstyle))borderless_rollback_error=GetLastError();
    RECT r=geometry_before.outer;
    if(!SetWindowPos(window,NULL,r.left,r.top,r.right-r.left,r.bottom-r.top,
                    SWP_FRAMECHANGED|SWP_NOACTIVATE|SWP_NOZORDER|SWP_NOOWNERZORDER))borderless_rollback_error=GetLastError();
    if(!game_geometry(window,&geometry_after)&&!borderless_rollback_error)borderless_rollback_error=GetLastError();
}
static void geometry_json(FILE *f,const GameGeometry *g){
    fprintf(f,"{\"client_x\":%ld,\"client_y\":%ld,\"width\":%ld,\"height\":%ld,\"style\":%lu,\"exstyle\":%lu}",
        g->origin.x,g->origin.y,g->client.right,g->client.bottom,(unsigned long)(DWORD)g->style,(unsigned long)(DWORD)g->exstyle);
}
static void game_window_json(FILE *f){
    fprintf(f,",\"game_window\":{\"policy\":\"startup_only\",\"requested\":%s,\"state\":\"%s\",\"win32_error\":%lu,\"rollback_error\":%lu,\"before\":",
        borderless_requested?"true":"false",borderless_state,(unsigned long)borderless_error,(unsigned long)borderless_rollback_error);
    geometry_json(f,&geometry_before);fputs(",\"after\":",f);geometry_json(f,&geometry_after);fputc('}',f);
}
