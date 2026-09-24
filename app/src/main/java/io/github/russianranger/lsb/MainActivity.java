package io.github.russianranger.lsb;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import io.github.russianranger.lsb.core.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class MainActivity extends Activity {
    private static String appVersion(Context context) {
        try { String version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;return version==null?"unknown":version; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { return "unknown"; }
    }
    private static final int PICK = 20, CREATE = 21;
    private static final int BG = Color.rgb(12, 20, 31), CARD = Color.rgb(24, 36, 49), TEXT = Color.rgb(244, 234, 213), MUTED = Color.rgb(163, 182, 198), ACCENT = Color.rgb(217, 184, 117);
    private String tab = "Client", pending = "";
    private LinearLayout content;
    private TextView operation, runtimeStatus,serverStatus;
    private ProgressBar progress;
    private Button cancel;
    private EditText host;
    private EditText loginPassword;
    private FantasyTiles tiles;
    private final Map<String,String> expandedSections=new HashMap<>();
    private Spinner region, playOnline;
    private List<String> playOnlinePaths = Collections.emptyList();
    private CheckBox preserve;
    private boolean preserveOnImport = true;
    private long generation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (operation != null) {
                operation.setText(WorkService.message + (WorkService.result.isEmpty() ? "" : "\n" + WorkService.result));
                progress.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE);
                cancel.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE);
            }
            if(runtimeStatus!=null)runtimeStatus.setText(ClientRuntime.get(MainActivity.this).status);
            if(serverStatus!=null)serverStatus.setText(ServerRuntime.get(MainActivity.this).status);
            if (generation != WorkService.generation) { generation = WorkService.generation; draw(); }
            handler.postDelayed(this, 600);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets; });
        }
        if (saved != null) { tab = saved.getString("tab", "Client"); pending = saved.getString("pending", ""); preserveOnImport = saved.getBoolean("preserve", true); }
        else if(getIntent().hasExtra("tab"))tab=getIntent().getStringExtra("tab");
        if(saved!=null){Bundle sections=saved.getBundle("sections");if(sections!=null)for(String key:sections.keySet())expandedSections.put(key,sections.getString(key,""));}
        generation = WorkService.generation;
        if (!WorkService.busy) { try {
            store(this).recover();
            SharedPreferences prefs = getSharedPreferences("preparation", MODE_PRIVATE);
            if (prefs.getInt("repairFormat", 0) != 3) {
                FilesEx.delete(new File(getFilesDir(), "repair-preview.txt"));
                prefs.edit().putInt("repairFormat", 3).apply();
            }
        } catch (Exception e) { WorkService.message = "Recovery needs attention"; WorkService.result = e.getMessage(); } }
        draw();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 30);
    }
    @Override protected void onResume() { super.onResume(); if(tab.equals("Runtime")||tab.equals("Client"))draw(); Fullscreen.apply(this);handler.post(poll); }
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)Fullscreen.apply(this);}
    @Override protected void onPause() { if(loginPassword!=null)loginPassword.setText("");handler.removeCallbacks(poll); super.onPause(); }
    @Override public void onSaveInstanceState(Bundle out) { out.putString("tab", tab); out.putString("pending", pending); out.putBoolean("preserve", preserveOnImport); Bundle sections=new Bundle();for(Map.Entry<String,String> entry:expandedSections.entrySet())sections.putString(entry.getKey(),entry.getValue());out.putBundle("sections",sections); super.onSaveInstanceState(out); }
    static File storage(Context ctx) throws IOException {
        File root = ctx.getExternalFilesDir(null); if (root == null) root = ctx.getFilesDir();
        File data = new File(root, "lsb"); FilesEx.mkdir(data); return data;
    }
    static ClientStore store(Context ctx) throws IOException { return new ClientStore(new File(storage(ctx), "session")); }
    static String profile(Context ctx) throws IOException {
        try (InputStream in = ctx.getAssets().open("working-profile.json")) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toString("UTF-8"); }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextColor(color); v.setTextSize(size); v.setPadding(0, dp(6), 0, dp(8)); v.setTextIsSelectable(true); return v;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private GradientDrawable background(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12)); return d; }
    private LinearLayout panel(String title) {
        LinearLayout v=column();v.setPadding(dp(18),dp(14),dp(18),dp(16));
        v.setBackground(new FantasyTiles.Panel(getResources().getDisplayMetrics().density,false));
        TextView heading=label(title,21,ACCENT);heading.setTypeface(Typeface.create("serif",Typeface.BOLD));v.addView(heading);return v;
    }
    private LinearLayout card(String title) {
        LinearLayout v=panel(title);tiles.addSection(title,v);return v;
    }
    private LinearLayout featuredCard(String title) {
        LinearLayout v=panel(title);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(12));content.addView(v,lp);return v;
    }
    private CheckBox setting(LinearLayout parent,String title,String key,boolean fallback,String explanation) {
        CheckBox box=new CheckBox(this);box.setText(title);box.setTextColor(TEXT);box.setTypeface(Typeface.create("serif",Typeface.NORMAL));
        box.setChecked(getSharedPreferences("runtime",0).getBoolean(key,fallback));
        box.setOnCheckedChangeListener((b,value)->getSharedPreferences("runtime",0).edit().putBoolean(key,value).apply());parent.addView(box);
        if(!explanation.isEmpty())parent.addView(label(explanation,13,MUTED));return box;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(TEXT); b.setMinHeight(dp(48));b.setTypeface(Typeface.create("serif",Typeface.BOLD));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x446ed5e8),new FantasyTiles.Panel(getResources().getDisplayMetrics().density,true),null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(4), 0, dp(4)); parent.addView(b, lp);
        b.setOnClickListener(v -> { if (WorkService.busy) { toast("Wait for the current operation, or cancel it."); return; } try { action.run(); } catch (Exception e) { error(e); } }); return b;
    }
    private void draw() {
        runtimeStatus=null;serverStatus=null;if(loginPassword!=null)loginPassword.setText("");loginPassword=null;
        LinearLayout page = column(); page.setBackgroundColor(BG); page.setPadding(dp(12), dp(6), dp(12), dp(6));
        FrameLayout hero=new FrameLayout(this);hero.setBackground(background(Color.rgb(15,35,58)));
        try(InputStream in=getAssets().open("art/"+(tab.equals("Server")?"server-background.png":"client-background.png"))){ImageView art=new ImageView(this);art.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(in,null));art.setScaleType(ImageView.ScaleType.CENTER_CROP);hero.addView(art,new FrameLayout.LayoutParams(-1,-1));}catch(IOException ignored){}
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xe5091526,0x66091526,0x11091526}));hero.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        boolean compact=getResources().getConfiguration().screenHeightDp<500;
        LinearLayout title=column();title.setPadding(dp(20),dp(compact?10:12),dp(16),dp(8));
        TextView heading=label("LSB",compact?25:30,TEXT);heading.setTypeface(Typeface.create("serif",Typeface.BOLD));heading.setPadding(0,0,0,0);title.addView(heading);
        TextView subtitle=label("A world of adventure, on your device",compact?11:13,Color.rgb(175,219,255));subtitle.setPadding(0,dp(3),0,0);title.addView(subtitle);
        hero.addView(title);page.addView(hero,new LinearLayout.LayoutParams(-1,dp(compact?72:100)));
        page.addView(label("Client & server launcher · "+appVersion(this),11,MUTED));
        LinearLayout nav = new LinearLayout(this);nav.setOrientation(LinearLayout.VERTICAL);LinearLayout navRow=null;int navIndex=0;int navColumns=getResources().getConfiguration().screenWidthDp>=600?6:3;
        for (String name : new String[]{"Client", "Controller", "Server", "Runtime", "Profile", "Diagnostics"}) {
            Button b = new Button(this); b.setText(name); b.setAllCaps(false); b.setTextSize(12);b.setTypeface(Typeface.create("serif",Typeface.BOLD)); b.setPadding(dp(4),dp(8),dp(4),dp(8)); b.setTextColor(name.equals(tab) ? ACCENT : TEXT); b.setMinHeight(dp(48));b.setSelected(name.equals(tab));
            b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x446ed5e8),new FantasyTiles.Panel(getResources().getDisplayMetrics().density,name.equals(tab)),null));
            if(navIndex++%navColumns==0){navRow=new LinearLayout(this);nav.addView(navRow);}LinearLayout.LayoutParams navCell=new LinearLayout.LayoutParams(0,-2,1);navCell.setMargins(dp(3),dp(3),dp(3),dp(3));navRow.addView(b,navCell);
            b.setOnClickListener(v -> { tab = name; draw(); });
        }
        page.addView(nav);
        operation = label(WorkService.message + (WorkService.result.isEmpty() ? "" : "\n" + WorkService.result), 13, MUTED); operation.setMaxLines(5); page.addView(operation);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE); page.addView(progress);
        cancel = new Button(this); cancel.setText("Cancel operation"); cancel.setAllCaps(false); cancel.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE); cancel.setOnClickListener(v -> { WorkService.cancel(); toast("Cancelling; waiting for the current file operation to stop."); }); page.addView(cancel);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); content = column(); scroll.addView(content); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        final String currentTab=tab;tiles=new FantasyTiles(this,expandedSections.getOrDefault(tab,""),value->expandedSections.put(currentTab,value));
        try { switch (tab) { case "Runtime": runtimePage(); break; case "Profile": profilePage(); break; case "Server": serverPage(); break; case "Controller": controllerPage(); break; case "Diagnostics": diagnosticsPage(); break; default: clientPage(); } }
        catch (Exception e) { content.addView(label("Cannot read app state: " + e.getMessage(), 16, TEXT)); }
        content.addView(tiles);
        Fullscreen.apply(this);
    }
    private void supportTile(){tiles.addAction("Export support ZIP","Save ZIP",()->{
        if(WorkService.busy){toast("Wait for the current operation, or cancel it.");return;}
        try{create("support","lsb-support.zip");}catch(Exception error){error(error);}
    });}
    private void runtimeSelector(LinearLayout panel,ClientRuntime rt) {
        CheckBox fex=new CheckBox(this);fex.setText("FEX / native ARM64 Wine");fex.setTextColor(TEXT);
        fex.setChecked(getSharedPreferences("runtime",0).getBoolean("fex",false));fex.setEnabled(!rt.alive()&&(fex.isChecked()||rt.fexInstalled()));
        fex.setOnCheckedChangeListener((b,v)->getSharedPreferences("runtime",0).edit().putBoolean("fex",v).apply());panel.addView(fex);
        panel.addView(label("Uses a separate Windows environment copied from your working setup. Off returns to Box64. Install FEX on the Runtime tab first; stop and relaunch to switch.",13,MUTED));
        CheckBox x87=new CheckBox(this);x87.setText("FEX faster x87 arithmetic");x87.setTextColor(TEXT);
        x87.setChecked(getSharedPreferences("runtime",0).getBoolean("fex_x87",false));x87.setEnabled(!rt.alive());
        x87.setOnCheckedChangeListener((b,v)->getSharedPreferences("runtime",0).edit().putBoolean("fex_x87",v).apply());panel.addView(x87);
        panel.addView(label("Confirmed working on Thor with runtime v3. Uses strict 64-bit arithmetic; off retains full 80-bit compatibility. Stop and relaunch to apply.",13,MUTED));
    }
    private void runtimePage() throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);
        LinearLayout panel=card("Install runtime");
        panel.addView(label("A separate environment for testing Windows, Direct3D 8, sound and input. Your imported FFXI files are not mounted or modified by these checks.",16,TEXT));
        runtimeStatus=label(rt.status,15,ACCENT);panel.addView(runtimeStatus);
        panel.addView(label("Default: Wine 10 / Box64 0.4.4. FEX uses native ARM64 Wine in a separate environment. Use the Client tab to launch your prepared FFXI installation.",14,MUTED));
        button(panel,"Install runtime (338 MiB download)",()->run("Installing Windows runtime",(ctx,p)->ClientRuntime.get(ctx).install(p))).setEnabled(!rt.alive()&&!rt.installed());
        button(panel,rt.fexInstalled()?"FEX runtime installed":"Install FEX runtime",()->run("Installing FEX runtime",(ctx,p)->ClientRuntime.get(ctx).installFex(p))).setEnabled(rt.installed()&&!rt.alive()&&!rt.fexInstalled());
        panel=card("Runtime checks");
        runtimeSelector(panel,rt);
        panel.addView(label("Graphics for this test",14,MUTED));
        panel.addView(label("Uses the Client tab’s DXVK, display rate, Native Surface and upload settings.",13,MUTED));
        Spinner graphics=new Spinner(this);graphics.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Turnip 26 / DXVK (Thor)","Turnip 24 / DXVK","Software diagnostic"}));
        String[] modes={"turnip26","turnip24","software"};
        graphics.setSelection(Arrays.asList(modes).indexOf(getSharedPreferences("runtime",MODE_PRIVATE).getString("renderer","turnip26")));panel.addView(graphics);
        CheckBox audio=new CheckBox(this);audio.setText("Enable sound test");audio.setTextColor(TEXT);audio.setChecked(true);panel.addView(audio);
        button(panel,"Start Windows checks",()->{
            String renderer=modes[graphics.getSelectedItemPosition()];getSharedPreferences("runtime",MODE_PRIVATE).edit().putString("renderer",renderer).apply();
            startForegroundService(new Intent(this,RuntimeService.class).putExtra("renderer",renderer).putExtra("audio",audio.isChecked()));startActivity(new Intent(this,RuntimeActivity.class));
        }).setEnabled(rt.installed()&&!rt.alive());
        button(panel,"Open runtime display",()->startActivity(new Intent(this,RuntimeActivity.class))).setEnabled(rt.alive());
        button(panel,"Stop runtime",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"))).setEnabled(rt.alive());
        panel.addView(label("Look for the colored triangle and Registry/COM PASS labels. Tap outside the triangle, send keys using Keyboard, and try Play tone. Exit the probe, then start it again. Export Diagnostics after the test.",15,TEXT));
        panel=card("Runtime recovery");
        button(panel,"View runtime details",()->{try{showText("Runtime details",rt.state().toString(2));}catch(Exception e){error(e);}});
        button(panel,"Create fresh Box64 test prefix",()->confirm("Fresh Box64 test environment","The current Box64 test prefix will be preserved in a separate backup. Your prepared client stays in place.",()->run("Preserving Windows prefix",(ctx,p)->ClientRuntime.get(ctx).freshPrefix()))).setEnabled(rt.installed()&&!rt.alive()&&!getSharedPreferences("runtime",0).getBoolean("fex",false));
        panel.addView(label("Current session backups contain your imported client, not this experimental runtime. A fresh-prefix action preserves its previous folder. Keep at least 3 GiB free for first setup.",14,MUTED));
    }
    private void clientPage() throws Exception {
        ClientStore s = store(this);
        boolean ready=ClientRuntime.get(this).preparationState().has("current");
        if(ready)loginCard(s);else supportTile();
        initializationCard(s);
        if (s.hasPendingImport() && !WorkService.busy) pendingImportCard(s);
        LinearLayout status = card(s.hasClient() ? "Imported client" : "Bring your FFXI installation");
        status.addView(label(s.summary(), 15, TEXT));
        status.addView(label("Your imported files are the source for a separate working installation. Launch uses the activated preparation above.", 14, MUTED));
        status.addView(label("Free space: " + storage(this).getUsableSpace() / 1073741824L + " GiB. Imports need room for a new full copy while retaining the active client.", 13, MUTED));
        preserve = new CheckBox(this); preserve.setText("Preserve current FFXI USER and PlayOnline usr settings on client import"); preserve.setTextColor(TEXT); preserve.setChecked(preserveOnImport); preserve.setOnCheckedChangeListener((b, checked) -> preserveOnImport = checked); status.addView(preserve);
        button(status, s.hasClient() ? "Import a complete client update ZIP" : "Import client ZIP", () -> pick("client")).setEnabled(!s.hasPendingImport());
        button(status, "Import xiloader.exe", () -> pick("loader")).setEnabled(s.hasClient());

        LinearLayout launch = card("Connection and repair");
        LaunchConfig cfg = s.config(); launch.addView(label("Server address", 14, MUTED)); host = new EditText(this); host.setSingleLine(true); host.setTextColor(TEXT); host.setText(cfg.host); host.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); launch.addView(host);
        launch.addView(label("Client region", 14, MUTED)); region = new Spinner(this); region.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"US", "EU", "JP"})); region.setSelection(Arrays.asList("US", "EU", "JP").indexOf(cfg.region)); launch.addView(region);
        playOnlinePaths = s.playOnlineChoices();
        playOnline = null;
        if (!playOnlinePaths.isEmpty()) {
            launch.addView(label("PlayOnline version", 14, MUTED));
            playOnline = playOnlineSpinner(launch, playOnlinePaths);
            int selected = playOnlinePaths.indexOf(cfg.polCore); playOnline.setSelection(Math.max(0, selected));
            followPlayOnlineRegion(playOnline, playOnlinePaths, region);
        }
        if (ClientInspector.isPatchCache(cfg.polCore)) launch.addView(label("Your saved selection is a patch-cache copy. Choose a Client files entry outside patchfiles above, then save. Your imported files and backup can be kept.", 15, ACCENT));
        button(launch, "Save connection settings", () -> { try { saveConnection(); toast("Saved"); draw(); } catch (Exception e) { error(e); } });
        button(launch, "Refresh file checks", () -> run("Checking imported files", (ctx, p) -> store(ctx).validate(p).summary())).setEnabled(s.hasClient());
        button(launch, "Validate client and preview repair script", () -> {
            try { saveConnection(); run("Inspecting client", (ctx, p) -> { File preview = new File(ctx.getFilesDir(), "repair-preview.txt"); FilesEx.delete(preview); String script = store(ctx).previewRepair(profile(ctx), p); FilesEx.text(preview, script); return "File validation passed. Repair recipe generated; run it inside Wine to test registration. See Diagnostics → Repair script."; }); }
            catch (Exception e) { error(e); }
        }).setEnabled(s.hasClient());
        button(launch, "Export prepared client + GameHub launcher", () -> { try { saveConnection(); create("prepared", "ffxi-prepared.zip"); } catch (Exception e) { error(e); } }).setEnabled(s.hasClient());
        button(launch, "Export launcher update only", () -> { try { saveConnection(); create("launcher", "ffxi-launcher-update.zip"); } catch (Exception e) { error(e); } }).setEnabled(s.hasClient());
        launch.addView(label("Legacy external export tools remain available. They are not required for the new in-app runtime checks.", 13, MUTED));

        LinearLayout backup = card("Backup and recovery");
        button(backup, "Export session backup", () -> create("backup", "lsb-session.zip")).setEnabled(s.hasClient());
        button(backup, "Restore session backup", () -> confirm("Restore session", "The backup is validated before activation. Your current client becomes the rollback copy.", () -> pick("restore"))).setEnabled(!s.hasPendingImport());
        button(backup, "Switch to previous client", () -> confirm("Switch client", "Validate the previous client, then swap it with the current client?", () -> run("Validating previous client", (ctx, p) -> { store(ctx).rollback(p); return "Previous client restored. The other copy remains available for rollback."; }))).setEnabled(s.hasPrevious());
        backup.addView(label("A session backup includes imported game files and saved connection settings. Windows runtime prefixes are separate and are not included. Uninstalling this app removes its managed copies.", 13, MUTED));
    }
    private EditText loginField(LinearLayout card,String title,String value,int type){
        card.addView(label(title,14,MUTED));EditText field=new EditText(this);field.setSingleLine(true);field.setTextColor(TEXT);field.setInputType(type);field.setText(value);
        field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        card.addView(field);return field;
    }
    private void loginCard(ClientStore source)throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);JSONObject state=rt.preparationState();
        LinearLayout card=featuredCard("Play FINAL FANTASY XI");runtimeStatus=label(rt.status,15,ACCENT);card.addView(runtimeStatus);
        card.addView(label("Start your Termux or managed server, then log in. Your working client and loader are kept together.",15,TEXT));
        LinearLayout graphics=card("Graphics & display");
        graphics.addView(label("FFXI display setting",14,MUTED));
        final String[] displayProfiles={"windowed720","windowed540","preserve","restore"};
        Spinner display=new Spinner(this);display.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Windowed 1280×720","Windowed 960×540 (lighter)","Keep current display settings","Restore saved original display settings"}));
        int selected=Arrays.asList(displayProfiles).indexOf(getSharedPreferences("runtime",0).getString("display_profile","windowed720"));
        display.setSelection(Math.max(0,selected));graphics.addView(display);
        graphics.addView(label("Applied at launch; original display settings remain available to restore. 960×540 reduces rendering load.",13,MUTED));
        setting(graphics,"Show DXVK game FPS","dxvk_hud",true,"");
        CheckBox fullscreen=setting(graphics,"Fullscreen app and game","fullscreen",false,"Hides Android bars and the game's bottom status strip. Use the game menu to exit fullscreen; swipe from an edge for Android controls. Rendering resolution stays the same.");
        fullscreen.setOnCheckedChangeListener((button,enabled)->Fullscreen.set(this,enabled));
        supportTile();
        LinearLayout proven=card("Proven fixes");
        proven.addView(label("Confirmed on Thor: runtime v3 restores the missing menus and distorted graphics. World play now reported at 20–30 FPS. Your current choices are preserved.",15,TEXT));
        runtimeSelector(proven,rt);
        CheckBox refresh60=new CheckBox(this);refresh60.setText("60 Hz display refresh");refresh60.setTextColor(TEXT);refresh60.setChecked(getSharedPreferences("runtime",0).getInt("display_fps",30)==60);
        refresh60.setOnCheckedChangeListener((b,v)->getSharedPreferences("runtime",0).edit().putInt("display_fps",v?60:30).apply());proven.addView(refresh60);
        proven.addView(label("Reduced reported drops into the teens. This is the display refresh cap; it does not raise the game's FPS limit.",13,MUTED));
        setting(proven,"DXVK 2.7.1","dxvk_271",false,"Previously improved play on Thor. Off selects 2.5.3. Compatibility checks retain automatic fallback.");
        setting(proven,"Native Surface display · shared memory","native_surface",true,"Confirmed working capture and direct Android display; bypasses compressed bitmap delivery.");
        setting(proven,"Shared-memory Vulkan presentation","shm_upload",true,"Verified active in the successful run. Reduces transfer traffic, with automatic fallback if its startup check fails. Its individual FPS benefit is not isolated.");
        proven.addView(label("Always applied: corrected FEX x87 condition flags in runtime v3, Android shared-memory capture, and removal of recurring startup-observer stalls. Stop and relaunch after changing runtime or display options.",13,MUTED));
        LinearLayout past=card("Past experiments");
        past.addView(label("No meaningful improvement in earlier comparisons. Retained for troubleshooting; both switches stay at your saved values. They were off in the successful 0.5.19 run.",15,TEXT));
        setting(past,"Turnip system-memory rendering","turnip_sysmem",false,"Changes GPU rendering mode; no established gameplay benefit. Next launch only, with compatibility fallback.");
        setting(past,"Two shader compiler workers","dxvk_two_compilers",false,"Limits shader compilation threads. Earlier comparisons did not establish a useful speedup; automatic worker count remains the baseline.");
        past.addView(label("Earlier metadata caching and hidden-cursor polling did not noticeably improve gameplay. Hidden-cursor polling did remove 85% of position queries in the 0.5.20 run and remains active. Full x87 precision and changing DXVK versions did not fix the old corruption. The CPU-feature correction alone also left it unresolved; it remains part of runtime v3 for correctness.",13,MUTED));
        LinearLayout tuning=card("New optimization");
        tuning.addView(label("Quieter gameplay overlay",18,ACCENT));
        tuning.addView(label("Unchanged status text no longer triggers a redraw every second. Fullscreen also hides the bottom status strip. This reduces overlay work; a gameplay FPS benefit is not yet established.",14,TEXT));
        LinearLayout diagnostics=card("Capture & diagnostics");
        CheckBox startupTrace=setting(diagnostics,"Capture FFXI startup and graphics","startup_trace",false,"For graphics faults only. Capture can affect FPS; leave it off for normal play and performance comparisons.");
        setting(diagnostics,"Show stutter diagnostics","dxvk_diagnostics",false,"Adds frame timing and shader activity to the FPS overlay. Applies on the next launch.");
        LinearLayout fallback=card("Fallback display");
        fallback.addView(label("Used only when Native Surface is off or unavailable. These options do not alter the active shared-memory display.",14,MUTED));
        setting(fallback,"Compress display transfer · lossless","compressed_display",true,"Established traffic reduction on the older display path.");
        setting(fallback,"Fast display · lower color depth","fast_display",true,"Uses 16-bit color on the fallback path. Reopen the client display to apply.");
        EditText server=loginField(card,"Server address",source.config().host,android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout credentials=new LinearLayout(this);LinearLayout accountColumn=column(),passwordColumn=column();
        credentials.addView(accountColumn,new LinearLayout.LayoutParams(0,-2,1));credentials.addView(passwordColumn,new LinearLayout.LayoutParams(0,-2,1));passwordColumn.setPadding(dp(12),0,0,0);card.addView(credentials);
        EditText account=loginField(accountColumn,"Account","",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText secret=loginField(passwordColumn,"Password","",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);loginPassword=secret;
        card.addView(label("Account and password are used for this launch only. They are not saved. Reported login failures stop the launcher and show a reason so you can retry here.",13,MUTED));
        button(card,"Launch FFXI",()->{
            LoginRequest login=null;
            try{
                if(rt.alive())throw new IOException("Stop the current client first");
                login=new LoginRequest(server.getText().toString().trim(),account.getText().toString(),secret.getText().toString());
                LaunchConfig old=source.config();source.saveConfig(new LaunchConfig(login.host,old.region,old.polCore));
                String ticket=RuntimeService.queueLogin(login);login=null;secret.setText("");account.setText("");
                rt.launchError="";
                String renderer=getSharedPreferences("runtime",MODE_PRIVATE).getString("renderer","turnip26");
                String displayProfile=displayProfiles[display.getSelectedItemPosition()];getSharedPreferences("runtime",MODE_PRIVATE).edit().putString("display_profile",displayProfile).putBoolean("startup_trace",startupTrace.isChecked()).apply();
                startForegroundService(new Intent(this,RuntimeService.class).putExtra("operation","launch").putExtra("renderer",renderer).putExtra("audio",true).putExtra("login_ticket",ticket).putExtra("display_profile",displayProfile).putExtra("startup_trace",startupTrace.isChecked()));
                startActivity(new Intent(this,RuntimeActivity.class));
            }catch(Exception e){if(login!=null)login.close();RuntimeService.clearLogin();error(e);}
        }).setEnabled(rt.installed()&&!rt.alive());
        button(card,"Open client display",()->startActivity(new Intent(this,RuntimeActivity.class))).setEnabled(rt.alive());
        button(card,"Stop client",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"))).setEnabled(rt.alive());
        button(diagnostics,"Check launcher dependencies",()->startInitialization("check-launcher")).setEnabled(!rt.alive());
        button(diagnostics,"View launch results",()->{try{JSONObject current=rt.preparationState().getJSONObject("current");showText("Launch results",current.has("last_launch")?current.getJSONObject("last_launch").toString(2):"No launch check has run yet.");}catch(Exception e){error(e);}});
        {
            diagnostics.addView(label("If launch diagnostics identify a missing dependency, select its official x86 installer. Repair makes a full recovery copy, runs the installer, and activates it only after client and loader checks pass.",14,MUTED));
            button(diagnostics,"Select launcher prerequisite (.exe)",()->pick("prerequisite")).setEnabled(!rt.alive());
            button(diagnostics,"Repair launcher prerequisites",()->startInitialization("repair-launcher")).setEnabled(!rt.alive()&&state.optBoolean("prerequisite_selected"));
        }
    }
    private void initializationCard(ClientStore source)throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);JSONObject prepared=rt.preparationState();
        boolean candidate=prepared.has("candidate"), copied=candidate&&prepared.getJSONObject("candidate").optBoolean("copy_complete");
        boolean repair=candidate&&prepared.getJSONObject("candidate").has("repair_of");
        LinearLayout card=card("Prepare PlayOnline and FFXI");
        if(runtimeStatus==null){runtimeStatus=label(rt.status,15,ACCENT);card.addView(runtimeStatus);}
        card.addView(label("Create a separate working copy and Windows environment, register the selected client, then test its PlayOnline and FFXI interfaces. Successful checks activate both copies together. This step does not log in or start the game.",15,TEXT));
        card.addView(label("Free runtime storage: "+rt.home.getUsableSpace()/1073741824L+" GiB. Preparation needs room for another full client and Windows copy; space is checked before copying. This may take several minutes.",14,MUTED));
        if(prepared.has("current"))card.addView(label("A validated preparation is available. A new attempt preserves it until checks pass.",14,ACCENT));
        if(candidate)card.addView(label(copied?"A staged working copy is available. Retry uses its captured region and files without copying the full import again.":"The previous copy was interrupted. Preparation will replace only that incomplete candidate.",14,MUTED));
        if(!rt.installed())card.addView(label("Install the Windows runtime on the Runtime tab first.",14,MUTED));
        button(card,repair?"Retry launcher prerequisite repair":copied?"Retry client initialization":"Prepare imported client",()->{
            try{if(!copied&&!repair)saveConnection();startInitialization(repair?"repair-launcher":"initialize");}catch(Exception e){error(e);}
        }).setEnabled(source.hasClient()&&!source.hasPendingImport()&&rt.installed()&&!rt.alive());
        button(card,"Open initialization display",()->startActivity(new Intent(this,RuntimeActivity.class))).setEnabled(rt.alive());
        button(card,"Stop initialization",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"))).setEnabled(rt.alive());
        button(card,"View preparation results",()->{try{showText("Prepared client",rt.preparationState().toString(2));}catch(Exception e){error(e);}});
        if(copied){
            card.addView(label("If diagnostics identify a missing dependency, select its official x86 prerequisite installer. It runs interactively in the staged copy, then registration checks run again.",14,MUTED));
            button(card,"Select prerequisite installer (.exe)",()->pick("prerequisite")).setEnabled(!rt.alive());
            button(card,"Run prerequisite and retry checks",()->startInitialization(repair?"repair-launcher":"installer")).setEnabled(!rt.alive()&&prepared.optBoolean("prerequisite_selected"));
        }
        button(card,"Discard staged preparation",()->confirm("Discard staged copy","Remove the unsuccessful working copy and its Windows environment? The original import and validated preparations are kept.",()->run("Discarding staged preparation",(ctx,p)->ClientRuntime.get(ctx).discardPreparation()))).setEnabled(candidate&&!rt.alive());
        button(card,"Restore previous preparation",()->run("Restoring previous preparation",(ctx,p)->ClientRuntime.get(ctx).rollbackPreparation())).setEnabled(prepared.has("previous")&&!rt.alive());
        card.addView(label("Session backups currently contain the original import, not prepared Windows environments. The existing Termux server is not needed for these checks.",13,MUTED));
    }
    private void startInitialization(String action){
        String renderer=getSharedPreferences("runtime",MODE_PRIVATE).getString("renderer","turnip26");
        startForegroundService(new Intent(this,RuntimeService.class).putExtra("operation",action).putExtra("renderer",renderer).putExtra("audio",true));
        startActivity(new Intent(this,RuntimeActivity.class));
    }
    private void saveConnection() throws IOException {
        if(ClientRuntime.get(this).alive())throw new IOException("Stop initialization before changing client settings");
        ClientStore s = store(this);
        String core = playOnline == null ? s.config().polCore : playOnlinePaths.get(playOnline.getSelectedItemPosition());
        LaunchConfig old = s.config(), next = new LaunchConfig(host.getText().toString().trim(), region.getSelectedItem().toString(), core);
        s.saveConfig(next);
        if (!old.host.equals(next.host) || !old.region.equals(next.region) || !old.polCore.equals(next.polCore)) FilesEx.delete(new File(getFilesDir(), "repair-preview.txt"));
    }
    private Spinner playOnlineSpinner(LinearLayout parent, List<String> paths) {
        List<String> labels = new ArrayList<>();
        for (String path : paths) labels.add((ClientInspector.isPatchCache(path) ? "Patch cache — do not use" : "Client files") + " · " + (path.toLowerCase(Locale.ROOT).endsWith("polcoreeu.dll") ? "EU" : "US / JP") + " · " + path);
        Spinner spinner = new Spinner(this); spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels)); parent.addView(spinner); return spinner;
    }
    private void followPlayOnlineRegion(Spinner versions, List<String> paths, Spinner regions) {
        versions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean eu = paths.get(position).toLowerCase(Locale.ROOT).endsWith("polcoreeu.dll");
                if (eu) regions.setSelection(1);
                else if (regions.getSelectedItemPosition() == 1) regions.setSelection(0);
            }
        });
    }
    private void pendingImportCard(ClientStore s) throws IOException {
        LinearLayout card = card("Choose PlayOnline version");
        card.addView(label("Extraction is complete. Select the installed PlayOnline version and folder. Choose a Client files entry outside patchfiles, then finish the import. The ZIP will not be extracted again.", 15, TEXT));
        List<String> choices = s.pendingChoices();
        Spinner versions = playOnlineSpinner(card, choices);
        card.addView(label("Client region (US and JP share the polcore.dll filename)", 14, MUTED));
        Spinner regions = new Spinner(this); regions.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"US", "EU", "JP"}));
        regions.setSelection(Arrays.asList("US", "EU", "JP").indexOf(s.pendingConfig().region)); card.addView(regions);
        followPlayOnlineRegion(versions, choices, regions);
        button(card, "Use selected version and finish import", () -> {
            final String core = choices.get(versions.getSelectedItemPosition()), selectedRegion = regions.getSelectedItem().toString();
            run("Validating selected PlayOnline version", (ctx, p) -> { store(ctx).finishPendingImport(core, selectedRegion, p); return "Client imported with " + selectedRegion + " PlayOnline. Your selection is saved."; });
        });
        button(card, "Discard extracted import", () -> confirm("Discard import", "Remove the extracted files waiting for selection? Your active client is kept.", () -> run("Discarding extracted import", (ctx, p) -> { store(ctx).discardPendingImport(); return "Extracted import discarded."; })));
    }
    private void profilePage() throws Exception {
        JSONObject data = new JSONObject(profile(this)); JSONObject runtime = data.getJSONObject("runtime");
        LinearLayout runtimeCard = card("Historical GameHub reference profile");
        runtimeCard.addView(label("Captured from your four screenshots. These components are reference requirements, not installed components in LSB Android.", 14, MUTED));
        String[][] fields = {{"Proton", "compatibilityLayer"}, {"CPU translator", "cpuTranslator"}, {"Translation parameters", "translationParams"}, {"GPU driver", "gpuDriver"}, {"DXVK", "dxvk"}, {"VKD3D", "vkd3d"}, {"Audio", "audio"}, {"CPU cores", "cpuCoreLimit"}, {"DInput", "dinput"}, {"Skip audio/video", "skipAudioVideoDecode"}};
        for (String[] f : fields) runtimeCard.addView(label(f[0] + "\n" + runtime.get(f[1]), 15, TEXT));
        LinearLayout components = card("Installed in the reference container"); JSONArray list = data.getJSONArray("components");
        for (int i = 0; i < list.length(); i++) { JSONObject c = list.getJSONObject(i); components.addView(label(c.getString("id") + "  ·  " + c.getString("gamehubPackageVersion"), 15, TEXT)); }
        components.addView(label("1.0.0 / 1.0.1 are GameHub package labels. The screenshots do not establish the exact upstream DLL versions or install order.", 13, MUTED));
        LinearLayout notes = card("Setup history");
        notes.addView(label("Reported working process: install PlayOnline/FFXI to initialize registration, replace their folders with updated files, then resolve xiloader initialization. Prior context identifies xiloader v2.0 and CLI autologin.\n\nThe chat-history search returned the recent assessment, not the old command transcript. Exact DLL overrides, installer order, translator preset internals, and the successful initialization command remain unverified.", 14, TEXT));
        button(notes, "Export compatibility profile", () -> create("profile", "ffxi-working-profile.json"));
    }
    private void controllerPage() throws Exception {
        SharedPreferences prefs=getSharedPreferences("controller",0);
        LinearLayout pad=card("Controller mapping");
        pad.addView(label("Send physical controls as a Windows joystick so FFXI can use its native gamepad settings. Button numbers below are the joystick buttons presented to the game.",15,TEXT));
        CheckBox enabled=new CheckBox(this);enabled.setText("Enable native gamepad");enabled.setTextColor(TEXT);enabled.setChecked(prefs.getBoolean("enabled",true));pad.addView(enabled);
        String[] targets=new String[21];targets[0]="Unmapped";for(int i=1;i<=16;i++)targets[i]="Gamepad button "+i;targets[17]="D-pad up";targets[18]="D-pad right";targets[19]="D-pad down";targets[20]="D-pad left";
        Spinner[] maps=new Spinner[ControllerInput.NAMES.length];
        for(int i=0;i<maps.length;i++){LinearLayout row=new LinearLayout(this);TextView name=label(ControllerInput.NAMES[i],15,TEXT);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));maps[i]=new Spinner(this);maps[i].setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,targets));maps[i].setSelection(Math.max(0,Math.min(20,prefs.getInt("button_"+i,i<12?i+1:17+i-12))));row.addView(maps[i],new LinearLayout.LayoutParams(0,-2,2));pad.addView(row);}
        LinearLayout axes=card("Sticks & dead zone");
        CheckBox swap=new CheckBox(this);swap.setText("Swap left and right sticks");swap.setTextColor(TEXT);swap.setChecked(prefs.getBoolean("swap_sticks",false));axes.addView(swap);
        CheckBox left=new CheckBox(this);left.setText("Invert left stick Y");left.setTextColor(TEXT);left.setChecked(prefs.getBoolean("invert_left_y",false));axes.addView(left);
        CheckBox right=new CheckBox(this);right.setText("Invert right stick Y");right.setTextColor(TEXT);right.setChecked(prefs.getBoolean("invert_right_y",false));axes.addView(right);
        axes.addView(label("Stick dead zone",14,MUTED));SeekBar dead=new SeekBar(this);dead.setMax(35);dead.setProgress(prefs.getInt("deadzone",18));axes.addView(dead);
        pad=card("Save & gamepad setup");
        button(pad,"Save controller mapping",()->{SharedPreferences.Editor edit=prefs.edit().putBoolean("enabled",enabled.isChecked()).putBoolean("swap_sticks",swap.isChecked()).putBoolean("invert_left_y",left.isChecked()).putBoolean("invert_right_y",right.isChecked()).putInt("deadzone",dead.getProgress());for(int i=0;i<maps.length;i++)edit.putInt("button_"+i,maps[i].getSelectedItemPosition());edit.apply();toast("Controller mapping saved. Reopen the client display to apply it.");});
        button(pad,"Restore default mapping",()->{prefs.edit().clear().apply();draw();});
        button(pad,"Open FFXI gamepad setup",()->startInitialization("gamepad-config")).setEnabled(!ClientRuntime.get(this).alive());
        pad.addView(label("The sticks provide X/Y and Z/Rz axes. Triggers default to buttons 11 and 12. Held inputs are released when the display loses focus. Set movement, camera and actions in FFXI’s gamepad configuration after testing detection.",13,MUTED));
    }
    private void serverPage() throws Exception {
        ServerRuntime sr=ServerRuntime.get(this);boolean running=sr.alive();
        LinearLayout live=card("Your LandSandBoat server");serverStatus=label(sr.status,15,ACCENT);live.addView(serverStatus);
        JSONObject active=sr.deployment();
        if(active.has("generation"))live.addView(label("Active server · "+active.optInt("accounts")+" accounts · "+active.optInt("characters")+" characters\nExpected client: "+active.optString("expected_client"),15,TEXT));
        else live.addView(label("First deploy the server folder and SQL backup that already work with this client. Client files and xiloader are never updated by these server actions.",15,TEXT));
        button(live,"Start managed server",()->startForegroundService(new Intent(this,ServerService.class))).setEnabled(sr.installed()&&active.has("generation")&&!running);
        button(live,"Stop managed server",()->startForegroundService(new Intent(this,ServerService.class).setAction("stop"))).setEnabled(running);
        live.addView(label("Stop the Termux server before starting this one: they use the same login/map ports. The managed server can keep running while you play. Use 127.0.0.1 in the Client tab.",13,MUTED));
        LinearLayout existing=card("Import your working server");
        button(existing,sr.installed()?"Server runtime installed":"Install server runtime and build tools",()->run("Installing server runtime",(ctx,p)->ServerRuntime.get(ctx).install(p))).setEnabled(!running&&!sr.installed());
        button(existing,"Export Termux packaging helper",()->create("server-helper","export-existing-lsb.py"));
        existing.addView(label("Run the helper inside your existing server’s Linux distro: python3 export-existing-lsb.py /path/to/server. It creates the server ZIP and SQL.gz without updating them.",13,MUTED));
        button(existing,"Import existing server folder ZIP",()->pick("source")).setEnabled(!running);
        button(existing,"Import existing database (.sql / .sql.gz)",()->pick("server-sql")).setEnabled(!running);
        existing.addView(label(sr.hasDatabaseImport()?"SQL backup imported and ready for deployment.":"Use a full SQL dump of your game database, including accounts and characters. Raw MariaDB data folders are not imported.",13,MUTED));
        EditText database=new EditText(this);database.setTextColor(TEXT);database.setSingleLine(true);database.setHint("Original database name");database.setText(getSharedPreferences("server",0).getString("database","xidb"));existing.addView(database);
        CheckBox local=new CheckBox(this);local.setText("Configure all zones for one local map process");local.setTextColor(TEXT);local.setChecked(getSharedPreferences("server",0).getBoolean("local_zones",true));existing.addView(local);
        Runnable save=()->{String name=database.getText().toString().trim();if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,47}"))throw new IllegalArgumentException("Enter a database name using letters, digits and underscores");getSharedPreferences("server",0).edit().putString("database",name).putBoolean("local_zones",local.isChecked()).apply();};
        button(existing,"Deploy imported Linux ARM64 server + database",()->{save.run();run("Deploying existing server",(ctx,p)->ServerRuntime.get(ctx).perform("deploy",false,p));}).setEnabled(sr.installed()&&sr.hasDatabaseImport()&&!running);
        button(existing,"Build imported source and deploy database",()->{save.run();run("Building existing server revision",(ctx,p)->ServerRuntime.get(ctx).perform("deploy",true,p));}).setEnabled(sr.installed()&&sr.hasDatabaseImport()&&!running);
        existing.addView(label("Import the complete server folder: settings, scripts, modules, sql, tools, meshes and matching binaries/source. Linux ARM64 binaries may be reused; Windows or Termux-native binaries must be rebuilt from the same source. Deployment stages an independent database before switching.",13,MUTED));
        LinearLayout source=card("Source and database updates");
        File report=new File(storage(this),"server/current/source-report.txt");if(report.exists())source.addView(label(FilesEx.read(report,8192),13,MUTED));
        EditText repo=new EditText(this);repo.setTextColor(TEXT);repo.setSingleLine(true);repo.setText(getSharedPreferences("server",0).getString("repository","https://github.com/Russianranger/LSB-server"));source.addView(repo);
        EditText ref=new EditText(this);ref.setTextColor(TEXT);ref.setSingleLine(true);ref.setHint("Branch, tag or exact commit");ref.setText(getSharedPreferences("server",0).getString("ref","base"));source.addView(ref);
        button(source,"Fetch selected source revision",()->{String repository=repo.getText().toString().trim(),revision=ref.getText().toString().trim();getSharedPreferences("server",0).edit().putString("repository",repository).putString("ref",revision).apply();run("Fetching source snapshot",(ctx,p)->SourceImport.download(new File(storage(ctx),"server"),repository,revision,p));}).setEnabled(!running);
        button(source,"Inspect selected source",()->run("Inspecting source",(ctx,p)->ServerRuntime.get(ctx).perform("inspect",false,p))).setEnabled(sr.installed()&&!running);
        button(source,"Build and apply source + database update",()->confirm("Stage a server update","First verify the existing server deployment works. This builds the selected source and runs its database migrations on a separate copy. Your current server/database pair stays available for rollback. Client and loader versions must be compatible with the selected revision.",()->run("Staging server and database update",(ctx,p)->ServerRuntime.get(ctx).perform("update",true,p)))).setEnabled(sr.installed()&&active.has("generation")&&!running);
        source.addView(label("Fetching source only changes the selected snapshot. It never updates the running deployment. Updates keep a complete previous server/database pair; rolling back also rolls player progress back to that copy.",13,MUTED));
        LinearLayout recovery=card("Server backups and logs");
        button(recovery,"Export full database backup",()->create("server-db","lsb-database.sql.gz")).setEnabled(active.has("generation")&&!running);
        button(recovery,"Restore previous server + database",()->confirm("Restore previous deployment","This switches both server files and player data to the retained previous generation. Progress made after that snapshot remains in the other generation.",()->run("Switching server generation",(ctx,p)->ServerRuntime.get(ctx).perform("rollback",false,p)))).setEnabled(active.has("generation")&&!running);
        button(recovery,"View server operation log",()->{try{showText("Server log",sr.operationLog());}catch(Exception e){error(e);}});
        button(recovery,"Probe saved server address",()->run("Checking server TCP ports",(ctx,p)->{String address=store(ctx).config().host;StringBuilder result=new StringBuilder("TCP reachability only: "+address+"\n");for(int port:new int[]{54231,54230,54001})try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(address,port),2500);result.append(port).append(": reachable\n");}catch(IOException e){result.append(port).append(": unavailable\n");}FilesEx.text(new File(ctx.getFilesDir(),"server-probe.txt"),result.toString());return result.toString();}));
    }
    private void diagnosticsPage() throws IOException {
        supportTile();
        LinearLayout d = card("Support and validation");
        d.addView(label("Support ZIPs contain the reference profile, key-file hashes, import summary, saved server/region, and app operation/probe logs. They exclude game payloads, Wine registry hives, and account passwords.", 14, MUTED));
        button(d, "View client inventory", () -> { try { showText("Client inventory", store(this).inventory()); } catch (Exception e) { error(e); } });
        button(d, "View repair script", () -> { try { File f = new File(getFilesDir(), "repair-preview.txt"); showText("Repair recipe · not yet executed", f.exists() ? FilesEx.read(f, 32768) : "Use Client → Validate client and preview repair script first."); } catch (Exception e) { error(e); } });
        button(d, "View operation log", () -> { try { File f = new File(getFilesDir(), "operations.log"); showText("Operation log", f.exists() ? FilesEx.read(f, 262144) : "No operations yet"); } catch (Exception e) { error(e); } });
        LinearLayout next = card("Runtime status");
        next.addView(label("Client files: managed import available\nRegistry/COM repair: in-app staged initialization\nWindows runtime: Box64 fallback and verified FEX v3 world play\nDisplay, D3D8 and audio: open-probe checks in Runtime tab\nFFXI registration/COM: staged preparation on Client tab\nLogin: prepared-client launch on Client tab\nController: native joystick mapping\nServer: isolated source/database deployment\n\nA successful import means the file layout and selected PE headers passed checks. It does not mean the client has launched.", 14, TEXT));
    }
    private void pick(String kind) { pending = kind; Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"); startActivityForResult(i, PICK); }
    private void create(String kind, String name) { pending = kind; Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(kind.equals("profile") ? "application/json" : kind.equals("server-helper")?"text/x-python":kind.equals("server-db")?"application/gzip":"application/zip").putExtra(Intent.EXTRA_TITLE, name); startActivityForResult(i, CREATE); }
    @Override protected void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);
        if ((request != PICK && request != CREATE) || response != RESULT_OK || data == null || data.getData() == null) { pending = ""; return; }
        final Uri uri = data.getData(); final String kind = pending; pending = ""; final boolean preserveFiles = preserveOnImport;
        final int grant = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, grant); } catch (SecurityException ignored) { }
        run(request == PICK ? "Reading selected file" : "Writing export", (ctx, p) -> {
            try {
            if (request == PICK) {
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Cannot open the selected document");
                    switch (kind) {
                        case "client": store(ctx).importClient(in, false, preserveFiles, p); return store(ctx).hasPendingImport() ? "Extraction complete. Choose the PlayOnline version on the Client tab to finish import." : "Client imported and validated. The previous copy, if any, is retained.";
                        case "restore": store(ctx).importClient(in, true, false, p); return store(ctx).hasPendingImport() ? "Backup extracted. Choose the PlayOnline version on the Client tab to finish restore." : "Session restored and validated.";
                        case "prerequisite": return ClientRuntime.get(ctx).importPrerequisite(in);
                        case "loader": store(ctx).importLoader(in, p); return "32-bit xiloader imported. Its runtime compatibility still needs a launch test.";
                        case "server-sql": return ServerRuntime.get(ctx).importDatabase(in,p);
                        case "source": if(ServerRuntime.get(ctx).alive())throw new IOException("Stop the managed server before importing source"); return SourceImport.stage(new File(storage(ctx), "server"), in, "User-selected ZIP (revision unverified)", p);
                        default: throw new IOException("File operation was lost; please select it again");
                    }
                }
            }
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot write to the selected destination");
                switch (kind) {
                    case "server-helper": try(InputStream helper=ctx.getAssets().open("server/export_existing.py")){byte[] b=new byte[8192];int n;while((n=helper.read(b))!=-1)out.write(b,0,n);} break;
                    case "server-db": ServerRuntime.get(ctx).exportDatabase(out,p); break;
                    case "backup": store(ctx).exportBackup(out, p); break;
                    case "prepared": case "launcher": store(ctx).exportPrepared(out, profile(ctx), windowsLauncher(ctx), kind.equals("prepared"), p); break;
                    case "profile": out.write(profile(ctx).getBytes(StandardCharsets.UTF_8)); break;
                    case "support": support(ctx, out); break;
                    default: throw new IOException("Export operation was lost; please select it again");
                }
            } catch (Exception e) { try { DocumentsContract.deleteDocument(ctx.getContentResolver(), uri); } catch (Exception ignored) { } throw e; }
            return "Export completed: " + kind + ".";
            } finally { try { ctx.getContentResolver().releasePersistableUriPermission(uri, grant); } catch (SecurityException ignored) { } }
        });
    }
    private static byte[] windowsLauncher(Context ctx) throws IOException {
        try (InputStream in = ctx.getAssets().open("LSB-FFXI.exe")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > 2 * 1048576) throw new IOException("Windows launcher asset exceeds 2 MiB"); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    private static void support(Context ctx, OutputStream out) throws Exception {
        ClientStore s = store(ctx);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ClientRuntime.get(ctx).exportLogs(zip);
            ServerRuntime.get(ctx).exportLogs(zip);
            SafeZip.entry(zip, "runtime-profile.json", profile(ctx)); SafeZip.entry(zip, "inventory.json", s.inventory()); SafeZip.entry(zip, "summary.txt", s.summary()); SafeZip.entry(zip, "session.properties", s.config().properties());
            SafeZip.entry(zip, "playonline-candidates.txt", String.join("\n", s.playOnlineChoices()) + "\n");
            if (s.hasPendingImport()) SafeZip.entry(zip, "pending-playonline.txt", "Waiting for PlayOnline selection\n" + String.join("\n", s.pendingChoices()) + "\n");
            SafeZip.entry(zip, "device.txt", "app="+appVersion(ctx)+"\nandroid=" + Build.VERSION.RELEASE + "\nsdk=" + Build.VERSION.SDK_INT + "\nmodel=" + Build.MODEL + "\nabis=" + Arrays.toString(Build.SUPPORTED_ABIS) + "\nfreeBytes=" + storage(ctx).getUsableSpace() + "\ninAppRuntime=prepared_client_launch\n");
            for (String name : new String[]{"operations.log", "server-probe.txt", "repair-preview.txt"}) { File f = new File(ctx.getFilesDir(), name); if (f.exists()) SafeZip.entry(zip, name, FilesEx.read(f, 262144)); }
            File report = new File(storage(ctx), "server/current/source-report.txt"); if (report.exists()) SafeZip.entry(zip, "source-report.txt", FilesEx.read(report, 8192));
        }
    }
    private void run(String title, WorkService.Job job) { if(ClientRuntime.get(this).alive()){toast("Stop the runtime before file operations or exports.");return;} if (!WorkService.submit(getApplicationContext(), title, job)) toast("An operation is already running or could not start."); draw(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private void error(Exception e) { new AlertDialog.Builder(this).setTitle("Unable to continue").setMessage(e.getMessage()).setPositiveButton("OK", null).show(); }
    private void confirm(String title, String message, Runnable action) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Continue", (d, w) -> action.run()).show(); }
    private void showText(String title, String text) { TextView view = label(text, 13, TEXT); view.setTypeface(Typeface.MONOSPACE); view.setPadding(dp(16), dp(8), dp(16), dp(8)); ScrollView scroll = new ScrollView(this); scroll.addView(view); new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show(); }
}
