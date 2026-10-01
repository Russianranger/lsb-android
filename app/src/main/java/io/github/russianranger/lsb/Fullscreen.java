package io.github.russianranger.lsb;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.*;

/** Saved app/display preference; never changes the Windows rendering resolution. */
final class Fullscreen {
    static boolean enabled(Context context){return context.getSharedPreferences("runtime",0).getBoolean("fullscreen",false);}
    static void set(Activity activity,boolean enabled){
        activity.getSharedPreferences("runtime",0).edit().putBoolean("fullscreen",enabled).apply();apply(activity);
    }
    static void apply(Activity activity){
        boolean enabled=enabled(activity);Window window=activity.getWindow();
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController controller=window.getInsetsController();
            if(controller!=null){
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if(enabled)controller.hide(WindowInsets.Type.systemBars());else controller.show(WindowInsets.Type.systemBars());
            }
        }else{
            window.getDecorView().setSystemUiVisibility(enabled?
                View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION:0);
        }
    }
}
