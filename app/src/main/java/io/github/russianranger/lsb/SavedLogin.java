package io.github.russianranger.lsb;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.russianranger.lsb.core.LaunchConfig;
import io.github.russianranger.lsb.core.LoginRequest;
import java.io.IOException;

/** Opt-in local login. Never include this preference group in support diagnostics. */
final class SavedLogin {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("quick_login",0);}
    private static String host(String value){return new LaunchConfig(value==null?null:value.trim(),"US").host.toLowerCase(java.util.Locale.ROOT);}
    static boolean matches(Context c,String server){
        SharedPreferences p=prefs(c);
        try{return p.getString("host","").equals(host(server))&&!p.getString("account","").isEmpty()&&!p.getString("password","").isEmpty();}
        catch(IllegalArgumentException invalid){return false;}
    }
    static String account(Context c,String server){return matches(c,server)?prefs(c).getString("account",""):"";}
    static String password(Context c,String server){return matches(c,server)?prefs(c).getString("password",""):"";}
    static void save(Context c,String server,String account,String password)throws IOException {
        // Share the loader's validation; a rejected edit must retain the prior login.
        try(LoginRequest checked=new LoginRequest(server,account,password)){
            if(!prefs(c).edit().clear().putString("host",host(checked.host)).putString("account",account).putString("password",password).commit())throw new IOException("Could not save the quick login");
        }
    }
    static void forget(Context c)throws IOException {
        if(!prefs(c).edit().clear().commit())throw new IOException("Could not remove the saved login");
    }
}
