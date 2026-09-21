/* Five FFXI display DWORDs only. Backups live in this prepared Wine prefix. */
static const WCHAR *setting_names[]={L"0001",L"0002",L"0003",L"0004",L"0034"};
static const DWORD windowed_values[]={1280,720,1280,720,1};
static DWORD setting_values[5],setting_before[5],config_rollback_error;
static BOOL setting_present[5],setting_before_present[5],setting_changed[5];
static BOOL config_checked,config_backup_ready;
static const WCHAR *config_policy=L"preserve";

static LONG display_read(HKEY key,DWORD *values,BOOL *present){
    for(int i=0;i<5;i++){
        DWORD type=0,size=4;values[i]=0;present[i]=FALSE;
        LONG rc=RegQueryValueExW(key,setting_names[i],NULL,&type,(BYTE*)&values[i],&size);
        if(rc==ERROR_FILE_NOT_FOUND)continue;
        if(rc){values[i]=0;return rc;}
        if(type!=REG_DWORD||size!=4){values[i]=0;return ERROR_INVALID_DATA;}
        present[i]=TRUE;
    }
    return 0;
}
static LONG display_write(HKEY key,int i,DWORD value,BOOL present){
    LONG rc=present?RegSetValueExW(key,setting_names[i],0,REG_DWORD,(BYTE*)&value,4):RegDeleteValueW(key,setting_names[i]);
    return !present&&rc==ERROR_FILE_NOT_FOUND?0:rc;
}
static LONG display_backup(HKEY key,BOOL create){
    DWORD complete=0,type=0,size=4;
    LONG rc=RegQueryValueExW(key,L"Complete",NULL,&type,(BYTE*)&complete,&size);
    if(!rc&&type==REG_DWORD&&size==4&&complete==1){config_backup_ready=TRUE;return 0;}
    /* Only a missing marker is an interrupted first save. Never replace a
     * completed backup or silently discard a malformed backup marker. */
    if(rc!=ERROR_FILE_NOT_FOUND)return rc?rc:ERROR_INVALID_DATA;
    if(!create)return ERROR_NOT_FOUND;
    for(int i=0;i<5;i++){rc=display_write(key,i,setting_before[i],setting_before_present[i]);if(rc)return rc;}
    DWORD actual[5];BOOL present[5];rc=display_read(key,actual,present);if(rc)return rc;
    for(int i=0;i<5;i++)if(present[i]!=setting_before_present[i]||(present[i]&&actual[i]!=setting_before[i]))return ERROR_INVALID_DATA;
    /* Flush saved values before the completion marker and before any game edits. */
    rc=RegFlushKey(key);if(rc)return rc;complete=1;
    rc=RegSetValueExW(key,L"Complete",0,REG_DWORD,(BYTE*)&complete,4);if(rc)return rc;
    rc=RegFlushKey(key);config_backup_ready=rc==0;return rc;
}
static LONG display_config(const WCHAR *language,const WCHAR *policy){
    if((wcscmp(language,L"0")&&wcscmp(language,L"1")&&wcscmp(language,L"2"))||
       (wcscmp(policy,L"preserve")&&wcscmp(policy,L"windowed720")&&wcscmp(policy,L"restore")))return ERROR_INVALID_PARAMETER;
    config_policy=policy;config_checked=FALSE;config_backup_ready=FALSE;config_rollback_error=0;
    memset(setting_changed,0,sizeof(setting_changed));memset(setting_values,0,sizeof(setting_values));memset(setting_present,0,sizeof(setting_present));
    memset(setting_before,0,sizeof(setting_before));memset(setting_before_present,0,sizeof(setting_before_present));
    WCHAR branch[256],backup_branch[256];const WCHAR *area=!wcscmp(language,L"0")?L"":!wcscmp(language,L"1")?L"US":L"EU";
    swprintf(branch,256,L"Software\\PlayOnline%ls\\%ls\\FinalFantasyXI",area,*area?L"SquareEnix":L"Square");
    swprintf(backup_branch,256,L"Software\\LSBAndroid\\DisplayBackup\\%ls",*area?area:L"JP");
    HKEY key;LONG rc=RegOpenKeyExW(HKEY_LOCAL_MACHINE,branch,0,KEY_QUERY_VALUE|KEY_SET_VALUE|KEY_WOW64_32KEY,&key);
    if(rc==ERROR_FILE_NOT_FOUND&&!wcscmp(policy,L"preserve")){config_checked=TRUE;return 0;}
    if(rc==ERROR_FILE_NOT_FOUND)rc=RegCreateKeyExW(HKEY_LOCAL_MACHINE,branch,0,NULL,0,KEY_QUERY_VALUE|KEY_SET_VALUE|KEY_WOW64_32KEY,NULL,&key,NULL);
    if(rc)return rc;
    rc=display_read(key,setting_before,setting_before_present);
    if(rc){RegCloseKey(key);return rc;}
    memcpy(setting_values,setting_before,sizeof(setting_values));memcpy(setting_present,setting_before_present,sizeof(setting_present));
    if(!wcscmp(policy,L"preserve")){RegCloseKey(key);config_checked=TRUE;return 0;}
    HKEY backup;
    rc=RegOpenKeyExW(HKEY_LOCAL_MACHINE,backup_branch,0,KEY_QUERY_VALUE|KEY_SET_VALUE|KEY_WOW64_32KEY,&backup);
    if(rc==ERROR_FILE_NOT_FOUND&&!wcscmp(policy,L"windowed720"))
        rc=RegCreateKeyExW(HKEY_LOCAL_MACHINE,backup_branch,0,NULL,0,KEY_QUERY_VALUE|KEY_SET_VALUE|KEY_WOW64_32KEY,NULL,&backup,NULL);
    if(rc){RegCloseKey(key);return rc==ERROR_FILE_NOT_FOUND?ERROR_NOT_FOUND:rc;}
    rc=display_backup(backup,!wcscmp(policy,L"windowed720"));
    DWORD target[5];BOOL target_present[5];
    /* Validate a completed backup before applying or restoring the profile. */
    if(!rc)rc=display_read(backup,target,target_present);RegCloseKey(backup);
    if(rc){RegCloseKey(key);return rc;}
    if(!wcscmp(policy,L"windowed720"))for(int i=0;i<5;i++){target[i]=windowed_values[i];target_present[i]=TRUE;}
    BOOL written[5]={0};
    for(int i=0;i<5;i++){
        if(target_present[i]==setting_before_present[i]&&(!target_present[i]||target[i]==setting_before[i]))continue;
        rc=display_write(key,i,target[i],target_present[i]);if(rc)break;written[i]=TRUE;
    }
    if(!rc)rc=display_read(key,setting_values,setting_present);
    if(!rc)for(int i=0;i<5;i++)if(setting_present[i]!=target_present[i]||(target_present[i]&&setting_values[i]!=target[i])){rc=ERROR_INVALID_DATA;break;}
    if(!rc)rc=RegFlushKey(key);
    if(rc){
        for(int i=0;i<5;i++)if(written[i]){LONG error=display_write(key,i,setting_before[i],setting_before_present[i]);if(error&&!config_rollback_error)config_rollback_error=error;}
        LONG error=RegFlushKey(key);if(error&&!config_rollback_error)config_rollback_error=error;
        error=display_read(key,setting_values,setting_present);if(error&&!config_rollback_error)config_rollback_error=error;
    }
    for(int i=0;i<5;i++)setting_changed[i]=setting_present[i]!=setting_before_present[i]||(setting_present[i]&&setting_values[i]!=setting_before[i]);
    RegCloseKey(key);config_checked=rc==0;return rc;
}
