package io.github.russianranger.lsb;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.IOException;

/** Purpose-based choices over the existing, independently installed engines. */
final class RuntimePresets {
    static void initializeNewSetup(Context context)throws IOException {
        SharedPreferences p=context.getSharedPreferences("runtime",0);
        // A restored or previously configured installation is authoritative.
        if(!p.getAll().isEmpty())return;
        if(Build.MODEL.toLowerCase(java.util.Locale.ROOT).contains("thor"))gameplay(context,"thor");
        else p.edit().putBoolean("fex",false).putString("renderer","turnip26").apply();
        updater(context,"box64");
    }
    static void gameplay(Context context,String choice)throws IOException {
        if(ClientRuntime.get(context).alive())throw new IOException("Stop the client before changing its gameplay preset");
        SharedPreferences.Editor e=context.getSharedPreferences("runtime",0).edit();
        if("thor".equals(choice)){
            e.putBoolean("fex",true).putBoolean("fex_x87",true).putString("renderer","turnip26")
                .putBoolean("dxvk_271",true).putBoolean("native_surface",true).putBoolean("shm_upload",true)
                .putBoolean("dxvk_two_compilers",true).putBoolean("proot_acceleration",true)
                .putBoolean("turnip_sysmem",false).putBoolean("dxvk_staged_buffers",false)
                .putString("performance_trial","none").putInt("display_fps",60)
                .putString("display_profile","windowed720").putBoolean("borderless",true)
                .putBoolean("startup_trace",false).putBoolean("dxvk_diagnostics",false);
        }else if("box64".equals(choice))e.putBoolean("fex",false);
        else throw new IOException("Choose a supported gameplay preset");
        e.apply();
    }
    static void updater(Context context,String choice)throws IOException {
        if(ClientRuntime.get(context).alive())throw new IOException("Stop the client or updater before changing the updater preset");
        if(!choice.equals("box64")&&!choice.equals("fex")&&!choice.equals("follow"))throw new IOException("Choose a supported updater preset");
        SharedPreferences.Editor e=context.getSharedPreferences("runtime",0).edit();
        if(choice.equals("follow"))e.remove("updater_engine");else e.putString("updater_engine",choice);
        e.apply();
    }
    static String gameplayLabel(Context context){
        SharedPreferences p=context.getSharedPreferences("runtime",0);
        if(!p.getBoolean("fex",false))return "Box64 gameplay · current graphics settings";
        boolean thor=p.getBoolean("fex_x87",false)&&p.getString("renderer","turnip26").equals("turnip26")
            &&p.getBoolean("dxvk_271",false)&&p.getBoolean("native_surface",true)&&p.getBoolean("shm_upload",true)
            &&p.getBoolean("dxvk_two_compilers",false)&&p.getBoolean("proot_acceleration",true)
            &&!p.getBoolean("turnip_sysmem",false)&&!p.getBoolean("dxvk_staged_buffers",false)
            &&p.getString("performance_trial","none").equals("none")&&p.getInt("display_fps",30)==60
            &&p.getString("display_profile","windowed720").equals("windowed720")&&p.getBoolean("borderless",true)
            &&!p.getBoolean("startup_trace",false)&&!p.getBoolean("dxvk_diagnostics",false);
        return thor?"Thor tested gameplay · FEX / Turnip / DXVK":"FEX gameplay · custom settings";
    }
    static String updaterLabel(Context context){
        String value=context.getSharedPreferences("runtime",0).getString("updater_engine",null);
        return value==null?"Follow gameplay engine":value.equals("box64")?"Box64 · PlayOnline checks and updates":"FEX · PlayOnline checks and updates";
    }
}
