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
import java.util.concurrent.*;
import java.util.zip.*;

public final class MainActivity extends Activity {
    private static String appVersion(Context context) {
        try { String version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;return version==null?"unknown":version; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { return "unknown"; }
    }
    private static final int PICK = 20, CREATE = 21;
    private static final int BG = Color.rgb(12, 20, 31), CARD = Color.rgb(24, 36, 49), TEXT = Color.rgb(244, 234, 213), MUTED = Color.rgb(163, 182, 198), ACCENT = Color.rgb(217, 184, 117);
    private String tab = "Play", pending = "";
    private boolean clientAliveUi,openPlayDisplay;
    private LinearLayout content;
    private TextView operation, runtimeStatus,serverStatus,serverStartup,latestServerOutput,serverLogBody,serverLogDialogBody;
    private ScrollView serverLogScroll,serverLogDialogScroll;
    private AlertDialog serverLogDialog;
    private AlertDialog progressDialog;private TextView progressDetails;
    private ServerRuntime.LogSnapshot serverLogSnapshot;
    private ExecutorService serverLogReader;
    private boolean resumed,serverLogReadPending;
    private long logReadAfter,logViewGeneration,logScrollGeneration;
    private static final Object READ_ONLY_CONTROL=new Object();
    private ProgressBar progress;
    private Button cancel;
    private EditText host;
    private EditText loginPassword,serverPassword,serverPasswordConfirm;
    private boolean serverAliveUi;
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
                if(ClientRuntime.get(MainActivity.this).alive())OperationProgress.client.update(ClientRuntime.get(MainActivity.this).status);
                operation.setText(OperationProgress.current().summary());
                if(progressDetails!=null)progressDetails.setText(OperationProgress.current().details());
                progress.setVisibility(OperationProgress.current().running ? View.VISIBLE : View.GONE);
                cancel.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE);
            }
            if(!SessionBackup.active&&SessionBackup.recoveryError.isEmpty()){
                if(runtimeStatus!=null&&(!tab.equals("Play")||ClientRuntime.get(MainActivity.this).alive()))runtimeStatus.setText(ClientRuntime.get(MainActivity.this).status);
                if(serverStatus!=null&&(!tab.equals("Play")||ServerRuntime.get(MainActivity.this).alive()))serverStatus.setText(ServerRuntime.get(MainActivity.this).status);
                if(serverStartup!=null)serverStartup.setText(ServerRuntime.get(MainActivity.this).startupLog());
                if((tab.equals("Server")||tab.equals("Build")||tab.equals("Play")||tab.equals("Setup"))&&(serverAliveUi!=ServerRuntime.get(MainActivity.this).alive()||clientAliveUi!=ClientRuntime.get(MainActivity.this).alive()))draw();
                if(openPlayDisplay&&ClientRuntime.get(MainActivity.this).displayAvailable()){openPlayDisplay=false;startActivity(new Intent(MainActivity.this,RuntimeActivity.class));}
                if(openPlayDisplay&&!ClientRuntime.get(MainActivity.this).alive()&&!ClientRuntime.get(MainActivity.this).launchError.isEmpty())openPlayDisplay=false;
            }
            if (generation != WorkService.generation) { generation = WorkService.generation; draw(); }
            renderServerLogs();requestServerLogs();
            handler.postDelayed(this, 600);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        OperationProgress.load(this);
        if(!WorkService.busy)try{SessionBackup.recover(this);}catch(Exception e){SessionBackup.recoveryError=e.getMessage();WorkService.message="Recovery needs attention";WorkService.result=e.getMessage();}
        if(!SessionBackup.active&&SessionBackup.recoveryError.isEmpty())ClientRuntime.migrateProvenAcceleration(this);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets; });
        }
        if (saved != null) { tab = saved.getString("tab", "Play"); pending = saved.getString("pending", ""); preserveOnImport = saved.getBoolean("preserve", true); }
        else if(getIntent().hasExtra("tab"))tab=getIntent().getStringExtra("tab");
        if(saved!=null)openPlayDisplay=saved.getBoolean("openPlayDisplay",false);
        if(saved!=null){Bundle sections=saved.getBundle("sections");if(sections!=null)for(String key:sections.keySet())expandedSections.put(key,sections.getString(key,""));}
        generation = WorkService.generation;
        if (!WorkService.busy&&SessionBackup.recoveryError.isEmpty()) { try {
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
    @Override protected void onResume() { super.onResume();resumed=true;logReadAfter=0; if(tab.equals("Runtime")||tab.equals("Client")||tab.equals("Play")||tab.equals("Setup")||tab.equals("Advanced"))draw(); Fullscreen.apply(this);handler.removeCallbacks(poll);handler.post(poll); }
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)Fullscreen.apply(this);}
    @Override protected void onPause() { resumed=false;logViewGeneration++;if(loginPassword!=null)loginPassword.setText("");clearServerPasswords();handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy(){if(serverLogDialog!=null)serverLogDialog.dismiss();if(progressDialog!=null)progressDialog.dismiss();if(serverLogReader!=null)serverLogReader.shutdownNow();super.onDestroy();}
    private void clearServerPasswords(){if(serverPassword!=null)serverPassword.setText("");if(serverPasswordConfirm!=null)serverPasswordConfirm.setText("");}
    @Override public void onSaveInstanceState(Bundle out) { out.putBoolean("openPlayDisplay",openPlayDisplay); out.putString("tab", tab); out.putString("pending", pending); out.putBoolean("preserve", preserveOnImport); Bundle sections=new Bundle();for(Map.Entry<String,String> entry:expandedSections.entrySet())sections.putString(entry.getKey(),entry.getValue());out.putBundle("sections",sections); super.onSaveInstanceState(out); }
    static File storage(Context ctx) throws IOException {
        File root = ctx.getExternalFilesDir(null); if (root == null) root = ctx.getFilesDir();
        File data = new File(root, "lsb"); FilesEx.mkdir(data); return data;
    }
    static ClientStore store(Context ctx) throws IOException {
        File session=new File(storage(ctx),"session");ClientStore source=new ClientStore(session);
        if(source.releasedImport())try {
            File clients=new File(ctx.getFilesDir(),"rt/clients");
            if(clients.isDirectory()){
                PreparedClientStore prepared=new PreparedClientStore(clients);File gen=prepared.selected("current");
                if(prepared.complete(gen)){
                    JSONObject receipt=new JSONObject(FilesEx.read(new File(gen,"initialization-passed.json"),262144));
                    if("passed".equals(receipt.optString("status"))&&gen.getName().equals(receipt.optString("generation")))
                        return new ClientStore(session,new File(gen,"client"),prepared.metadata(gen).getProperty("core",""));
                }
            }
        }catch(Exception unavailable){ /* Imports with preservation enabled fail closed without a validated personal-settings source. */ }
        return source;
    }
    static String profile(Context ctx) throws IOException {
        try (InputStream in = ctx.getAssets().open("working-profile.json")) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toString("UTF-8"); }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void disableControls(View view){
        if((view instanceof Button||view instanceof EditText||view instanceof Spinner||view instanceof SeekBar)&&view.getTag()!=READ_ONLY_CONTROL)view.setEnabled(false);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)disableControls(group.getChildAt(i));}
    }
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
        return button(parent,text,action,false);
    }
    private Button button(LinearLayout parent, String text, Runnable action,boolean readOnly) {
        HintButton b = new HintButton(); b.setText(text); b.setAllCaps(false); b.setTextColor(TEXT); b.setMinHeight(dp(48));b.setTypeface(Typeface.create("serif",Typeface.BOLD));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x446ed5e8),new FantasyTiles.Panel(getResources().getDisplayMetrics().density,true),null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(4), 0, dp(4)); parent.addView(b, lp);
        b.hint=label("",12,MUTED);b.hint.setVisibility(View.GONE);parent.addView(b.hint);
        if(readOnly)b.setTag(READ_ONLY_CONTROL);
        b.setOnClickListener(v -> { if (WorkService.busy&&!readOnly) { toast("Wait for the current operation, or cancel it."); return; } try { action.run(); } catch (Exception e) { error(e); } }); return b;
    }
    private final class HintButton extends Button {
        TextView hint;
        HintButton(){super(MainActivity.this);}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);if(hint!=null){hint.setText(disabledReason(getText().toString()));hint.setVisibility(enabled?View.GONE:View.VISIBLE);}}
    }
    private String disabledReason(String action){
        if(WorkService.busy)return "Wait for the current operation to finish, or cancel it.";
        if(ClientRuntime.get(this).alive())return "Stop the client operation first.";
        if(action.startsWith("Open ")&&action.contains("display"))return "Start the client or runtime to open its display.";
        if(action.startsWith("Stop "))return "This service is already stopped.";
        if(ServerRuntime.get(this).alive())return "Stop the managed server first.";
        if(action.contains("installed"))return "Already installed.";
        if(action.startsWith("Install runtime")&&ClientRuntime.get(this).installed())return "The runtime is already installed.";
        if(action.contains("FEX runtime"))return ClientRuntime.get(this).fexInstalled()?"The FEX runtime is already installed.":"Install the baseline client runtime first.";
        if(action.equals("Check staged build and database"))return "Prepare a database with a completed build first; server build tools must be installed.";
        if(action.equals("Deploy checked build and database"))return "Prepare staging and pass its checks. If the server ran afterward, prepare its database again.";
        if(action.contains("Prepare database"))return "Complete a server build and select the database input first.";
        if(action.contains("Build")||action.contains("Compile"))return "Install server build tools and fetch or import the source first.";
        if(action.startsWith("Save working"))return "Activate a prepared client before saving a working combination.";
        if(action.contains("previous")||action.contains("rollback"))return "No validated previous copy is available.";
        if(action.contains("prerequisite"))return "Prepare a client and select an x86 prerequisite installer first.";
        if(action.contains("Verify")||action.contains("Activate"))return "Finish the preceding staged update step first.";
        if(action.contains("Import")||action.contains("import"))return "Finish or discard the pending import or staged update first.";
        return "Complete the preceding setup step and select the required files first.";
    }
    private void draw() {
        serverAliveUi=ServerRuntime.get(this).alive();clientAliveUi=ClientRuntime.get(this).alive();
        runtimeStatus=null;serverStatus=null;serverStartup=null;serverLogBody=null;serverLogScroll=null;if(loginPassword!=null)loginPassword.setText("");loginPassword=null;clearServerPasswords();serverPassword=null;serverPasswordConfirm=null;
        LinearLayout page = column(); page.setBackgroundColor(BG); page.setPadding(dp(12), dp(6), dp(12), dp(6));
        FrameLayout hero=new FrameLayout(this);hero.setBackground(background(Color.rgb(15,35,58)));
        try(InputStream in=getAssets().open("art/"+((tab.equals("Server")||tab.equals("Build"))?"server-background.png":"client-background.png"))){ImageView art=new ImageView(this);art.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(in,null));art.setScaleType(ImageView.ScaleType.CENTER_CROP);hero.addView(art,new FrameLayout.LayoutParams(-1,-1));}catch(IOException ignored){}
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xe5091526,0x66091526,0x11091526}));hero.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        boolean compact=getResources().getConfiguration().screenHeightDp<500;
        LinearLayout title=column();title.setPadding(dp(20),dp(compact?10:12),dp(16),dp(8));
        TextView heading=label("LSB",compact?25:30,TEXT);heading.setTypeface(Typeface.create("serif",Typeface.BOLD));heading.setPadding(0,0,0,0);title.addView(heading);
        TextView subtitle=label("A world of adventure, on your device",compact?11:13,Color.rgb(175,219,255));subtitle.setPadding(0,dp(3),0,0);title.addView(subtitle);
        hero.addView(title);page.addView(hero,new LinearLayout.LayoutParams(-1,dp(compact?72:100)));
        page.addView(label((getPackageName().endsWith(".restoretest")?"LSB Restore Test · separate installation":"Client & server launcher")+" · "+appVersion(this),11,MUTED));
        LinearLayout nav = new LinearLayout(this);nav.setOrientation(LinearLayout.VERTICAL);LinearLayout navRow=null;int navIndex=0;int navColumns=getResources().getConfiguration().screenWidthDp>=600?6:3;
        for (String name : new String[]{"Play", "Client", "Server", "Build", "Controller", "More"}) {
            Button b = new Button(this); b.setText(name); b.setAllCaps(false); b.setTextSize(12);b.setTypeface(Typeface.create("serif",Typeface.BOLD)); b.setPadding(dp(4),dp(8),dp(4),dp(8)); b.setTextColor(name.equals(tab) ? ACCENT : TEXT); b.setMinHeight(dp(48));b.setSelected(name.equals(tab));
            b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x446ed5e8),new FantasyTiles.Panel(getResources().getDisplayMetrics().density,name.equals(tab)),null));
            if(navIndex++%navColumns==0){navRow=new LinearLayout(this);nav.addView(navRow);}LinearLayout.LayoutParams navCell=new LinearLayout.LayoutParams(0,-2,1);navCell.setMargins(dp(3),dp(3),dp(3),dp(3));navRow.addView(b,navCell);
            b.setOnClickListener(v -> { tab = name; draw(); });
        }
        page.addView(nav);
        operation = label(OperationProgress.current().summary(), 13, MUTED); operation.setMaxLines(5);page.addView(operation);
        operation.setContentDescription("Current operation and elapsed time. Tap for details.");operation.setOnClickListener(v->showProgressDetails());
        latestServerOutput=label("",12,MUTED);latestServerOutput.setTypeface(Typeface.MONOSPACE);latestServerOutput.setMaxLines(2);latestServerOutput.setVisibility(View.GONE);page.addView(latestServerOutput);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE); page.addView(progress);
        cancel = new Button(this); cancel.setText("Cancel operation"); cancel.setAllCaps(false); cancel.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE); cancel.setOnClickListener(v -> { WorkService.cancel(); toast("Cancelling; waiting for the current file operation to stop."); }); page.addView(cancel);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); content = column(); scroll.addView(content); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        if(!SessionBackup.recoveryError.isEmpty()){
            content.addView(label(SessionBackup.recoveryError,15,TEXT));
            button(content,"Retry restore recovery",()->run("Recovering restored session",(ctx,p)->{SessionBackup.recover(ctx);return "Restored session recovered.";}));return;
        }
        if(SessionBackup.active){content.addView(label("Complete session transfer in progress. Keep LSB open while files and settings are verified.",15,TEXT));return;}
        final String currentTab=tab;tiles=new FantasyTiles(this,expandedSections.getOrDefault(tab,""),value->expandedSections.put(currentTab,value));
        try { if(tab.equals("Client")||tab.equals("Build")||tab.equals("Server"))setupBreadcrumb(); switch (tab) { case "Play": playPage(); break; case "More": morePage();break;case "Advanced": advancedPage();break;case "Setup": setupPage(); break; case "Runtime": runtimePage(); break; case "Profile": profilePage(); break; case "Server": serverPage(); break; case "Build": buildPage(); break; case "Controller": controllerPage(); break; case "Diagnostics": diagnosticsPage(); break; default: clientPage(); } }
        catch (Exception e) { content.addView(label("Cannot read app state: " + e.getMessage(), 16, TEXT)); }
        content.addView(tiles);
        if(WorkService.busy)disableControls(content);
        renderServerLogs();requestServerLogs();
        Fullscreen.apply(this);
    }
    private void navigate(String page,String section){tab=page;if(!section.isEmpty())expandedSections.put(page,section);draw();}
    private void showProgressDetails(){
        if(progressDialog!=null)progressDialog.dismiss();progressDetails=label(OperationProgress.current().details(),14,TEXT);
        progressDetails.setPadding(dp(16),dp(12),dp(16),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(progressDetails);
        progressDialog=new AlertDialog.Builder(this).setTitle("Current operation details").setView(scroll).setPositiveButton("Close",null).create();
        progressDialog.setOnDismissListener(d->{progressDetails=null;progressDialog=null;});progressDialog.show();
    }
    private void morePage(){
        LinearLayout more=featuredCard("Setup, recovery and diagnostics");
        button(more,"Guided setup",()->navigate("Setup",""),true);
        button(more,"Backups and storage",()->navigate("Client","Backup and recovery"),true);
        button(more,"Advanced settings and repairs",()->navigate("Advanced",""),true);
        button(more,"Diagnostics and history",()->navigate("Diagnostics",""),true);
        supportTile();
    }
    private void advancedPage()throws Exception {
        LinearLayout intro=featuredCard("Advanced settings and repairs");intro.addView(label("Runtime experiments, dependency repairs and technical details. Play uses your current saved settings.",14,MUTED));
        button(intro,"Runtime installation and checks",()->navigate("Runtime",""),true);
        button(intro,"Historical GameHub reference",()->navigate("Profile",""),true);
        button(intro,"Diagnostics and history",()->navigate("Diagnostics",""),true);
        serverRuntimeSettingsCard();
        ClientStore source=store(this);if(ClientRuntime.get(this).preparationState().has("current"))loginCard(source);
        button(intro,"Inspect all activated client files",()->run("Inspecting active client",(ctx,p)->ClientRuntime.get(ctx).inspectActiveClient())).setEnabled(ClientRuntime.get(this).preparationState().has("current")&&!ClientRuntime.get(this).alive());
        initializationCard(source);
        LinearLayout legacy=card("External launcher and repair tools");
        button(legacy,"Validate client and preview repair script",()->run("Inspecting client",(ctx,p)->{String script=store(ctx).previewRepair(profile(ctx),p);FilesEx.text(new File(ctx.getFilesDir(),"repair-preview.txt"),script);return "Repair preview ready in Diagnostics.";})).setEnabled(source.hasClient());
        button(legacy,"Export prepared client + GameHub launcher",()->create("prepared","ffxi-prepared.zip")).setEnabled(source.hasClient());
        button(legacy,"Export launcher update only",()->create("launcher","ffxi-launcher-update.zip")).setEnabled(source.hasClient());
    }
    private void serverRuntimeSettingsCard(){
        ServerRuntime sr=ServerRuntime.get(this);LinearLayout settings=card("Server runtime settings");
        CheckBox acceleration=new CheckBox(this);acceleration.setText("Server runtime acceleration");acceleration.setTextColor(TEXT);
        acceleration.setChecked(getSharedPreferences("server",0).getBoolean("proot_acceleration",true));acceleration.setEnabled(!sr.alive()&&!WorkService.busy);
        acceleration.setOnCheckedChangeListener((box,value)->getSharedPreferences("server",0).edit().putBoolean("proot_acceleration",value).apply());settings.addView(acceleration);
        settings.addView(label("Enabled by default for faster server startup. Startup checks keep compatibility mode if unavailable. Stop the server before changing; turn off only to troubleshoot or compare boot times. Builds, backups and database recovery use compatibility mode.",13,MUTED));
        File filterReceipt=new File(sr.logs,"proot-acceleration.json");
        if(filterReceipt.isFile())try{JSONObject filter=new JSONObject(FilesEx.read(filterReceipt,65536));settings.addView(label("Last server start: "+("syscall_filter".equals(filter.optString("mode"))?"acceleration confirmed":"compatibility mode"),13,MUTED));}catch(Exception ignored){}
    }
    private void installationCard()throws Exception {
        JSONObject active=InstallationSummary.snapshot(this);LinearLayout panel=card("Currently playing with");
        panel.addView(label(InstallationSummary.describe(active),14,TEXT));
        JSONObject prepared=ClientRuntime.get(this).preparationState(),candidate=prepared.optJSONObject("candidate");JSONObject build=ServerRuntime.get(this).buildState();
        if(candidate!=null)panel.addView(label("Staged client · "+InstallationSummary.shortId(candidate.optString("generation"))+" · "+candidate.optString("update_phase","preparation")+" · not active",13,ACCENT));
        JSONObject fetched=build.optJSONObject("selected_source"),compiled=build.optJSONObject("build"),staged=build.optJSONObject("staged");
        if(fetched!=null&&fetched.length()>0)panel.addView(label("Fetched source · "+sourceIdentity(fetched)+" · changes take effect only after deployment",13,MUTED));
        if(compiled!=null&&compiled.has("build_id"))panel.addView(label("Built · "+InstallationSummary.shortId(compiled.optString("build_id"))+" · "+compiled.optString("state"),13,MUTED));
        if(staged!=null&&staged.has("generation")&&!"deployed".equals(staged.optString("state")))panel.addView(label("Staged server/database · "+InstallationSummary.shortId(staged.optString("generation"))+" · "+staged.optString("state")+" · not active",13,ACCENT));
        JSONObject saved=InstallationSummary.saved(this);
        if(saved.has("combination"))panel.addView(label("Saved working combination · "+new java.text.SimpleDateFormat("MMM d, HH:mm",Locale.getDefault()).format(new Date(saved.optLong("saved_at")))+"\n"+InstallationSummary.describe(saved.getJSONObject("combination")),13,MUTED));
        else panel.addView(label("No working-combination backup recorded yet. Save one after successful play, before updating.",13,MUTED));
        boolean idle=!ClientRuntime.get(this).alive()&&!ServerRuntime.get(this).alive();
        button(panel,"Save working combination",()->create("working-backup","lsb-working-combination.zip")).setEnabled(idle&&active.has("client"));
        button(panel,"Restore saved working combination",this::restoreBackup).setEnabled(idle);
        panel.addView(label("Saves a complete backup to your chosen file, including client, loader, server, database, runtimes and settings. Restore selects that ZIP and returns them together to the saved point. This can take several minutes. External servers and their databases are not included.",13,MUTED));
        if(!idle)panel.addView(label("Stop the client and managed server to save or restore a working combination.",13,ACCENT));
    }
    private void setupBreadcrumb()throws Exception {
        if(getSharedPreferences("setup",0).getString("mode","").isEmpty())return;
        SetupGuide.Step step=SetupGuide.next(this);
        LinearLayout bar=column();bar.addView(label("Setup · "+step.title,13,MUTED));
        button(bar,step.ready()?"Back to Play":"Continue guided setup",()->navigate(step.ready()?"Play":"Setup",""),true);content.addView(bar);
    }
    private void chooseSetup(String mode){
        try{
            if(ClientRuntime.get(this).alive()||ServerRuntime.get(this).alive())throw new IOException("Stop the client and server before changing setup paths");
            if(mode.equals("restore"))getSharedPreferences("setup",0).edit().putBoolean("restore_pending",true).apply();
            else {
                getSharedPreferences("setup",0).edit().putString("mode",mode).remove("restore_pending").putBoolean("connection_confirmed",false).apply();
                RuntimePresets.initializeNewSetup(this);
                if(mode.equals("local")){ClientStore s=store(this);LaunchConfig old=s.config();s.saveConfig(new LaunchConfig("127.0.0.1",old.region,old.polCore));}
            }
            navigate("Setup","");
        }catch(Exception e){error(e);}
    }
    private void restoreBackup(){
        confirm("Restore complete session","Choose the complete session ZIP exported from LSB or LSB Restore Test. Restoring replaces this app's data after verification. Both the client and server must be stopped. The other app installation is unchanged.",()->pick("restore"));
    }
    private void setupPage()throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);SetupGuide.Step step=SetupGuide.next(this);
        LinearLayout intro=featuredCard("Set up your adventure");
        intro.addView(label("Your progress is saved. Completed downloads, imported files and restored preparations are recognized automatically.",14,MUTED));
        if(!step.id.equals("choose")){
            intro.addView(label(step.title,20,ACCENT));intro.addView(label(step.detail,15,TEXT));
            if(step.id.equals("connection"))setupConnection(intro);
            else button(intro,step.action,()->performSetupStep(step.id),step.ready()).setEnabled(step.ready()||(!rt.alive()&&!ServerRuntime.get(this).alive()));
            if(step.id.equals("account"))button(intro,"I already have an account",()->{getSharedPreferences("setup",0).edit().putBoolean("account_ready",true).apply();draw();});
            if(rt.alive()){
                runtimeStatus=label(rt.status,14,ACCENT);intro.addView(runtimeStatus);
                button(intro,"Open setup display",()->startActivity(new Intent(this,RuntimeActivity.class)),true);
                button(intro,"Stop client operation",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop")),true);
            }
        }
        LinearLayout paths=step.id.equals("choose")?intro:card("Choose a setup path");
        paths.addView(label("Connect elsewhere, host on this device, or bring back a complete backup.",14,TEXT));
        button(paths,"Connect to an existing server",()->chooseSetup("external"));
        button(paths,"Create a server on this device",()->chooseSetup("local"));
        button(paths,"Restore a complete backup",()->chooseSetup("restore"));
        if(!step.id.equals("restore")&&!step.id.equals("choose"))runtimePresetsCard();
        if(step.ready()){
            LinearLayout next=card("Next steps");
            button(next,"Build or update server source",()->navigate("Build",""),true);
            button(next,"Update client with PlayOnline",()->navigate("Client","Client update · PlayOnline"),true);
            button(next,"Configure controller",()->navigate("Controller",""),true);
        }
        supportTile();
    }
    private void setupConnection(LinearLayout panel)throws Exception {
        ClientStore s=store(this);LaunchConfig old=s.config();boolean managed=SetupGuide.local(this);
        EditText address=loginField(panel,"Server address",managed?"127.0.0.1":old.host,android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setEnabled(!managed);
        panel.addView(label("Client region",14,MUTED));Spinner choice=new Spinner(this);choice.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"US","EU","JP"}));choice.setSelection(Math.max(0,Arrays.asList("US","EU","JP").indexOf(old.region)));panel.addView(choice);
        button(panel,"Save connection and continue",()->{try{s.saveConfig(new LaunchConfig(managed?"127.0.0.1":address.getText().toString().trim(),choice.getSelectedItem().toString(),old.polCore));getSharedPreferences("setup",0).edit().putBoolean("connection_confirmed",true).apply();draw();}catch(Exception e){error(e);}});
    }
    private void performSetupStep(String step){
        switch(step){
            case "restore":restoreBackup();break;
            case "runtime":run("Installing client runtime",(ctx,p)->ClientRuntime.get(ctx).install(p));break;
            case "fex":run("Installing gameplay engine",(ctx,p)->ClientRuntime.get(ctx).installFex(p));break;
            case "client":pick("client");break;
            case "loader":pick("loader");break;
            case "selection":navigate("Client","Choose PlayOnline version");break;
            case "prepare":
                try{if(ClientRuntime.get(this).preparationState().has("candidate"))navigate("Client","Prepare PlayOnline and FFXI");else startInitialization("initialize");}catch(Exception e){error(e);}break;
            case "server_runtime":run("Installing server build tools",(ctx,p)->ServerRuntime.get(ctx).install(p));break;
            case "account":navigate("Server","Create account");break;
            case "ready":navigate("Play","");break;
            default:navigate("Build","");break;
        }
    }
    private void runtimePresetsCard(){
        ClientRuntime rt=ClientRuntime.get(this);LinearLayout panel=card("Gameplay and updater presets");
        panel.addView(label("GAMEPLAY\n"+RuntimePresets.gameplayLabel(this),15,TEXT));
        panel.addView(label("Thor tested: FEX, Turnip 26, DXVK 2.7.1, 1280×720, native shared-memory display, 60 Hz display refresh and two shader workers. Other devices may need different graphics settings.",13,MUTED));
        button(panel,"Use Thor tested gameplay",()->{try{RuntimePresets.gameplay(this,"thor");draw();}catch(Exception e){error(e);}}).setEnabled(!rt.alive());
        button(panel,"Use Box64 gameplay",()->{try{RuntimePresets.gameplay(this,"box64");draw();}catch(Exception e){error(e);}}).setEnabled(!rt.alive());
        panel.addView(label("Box64 gameplay keeps your graphics settings. Changing gameplay never changes the updater selection.",13,MUTED));
        if(getSharedPreferences("runtime",0).getBoolean("fex",false)&&!rt.fexInstalled())button(panel,"Install selected FEX engine",()->navigate("Setup",""),true);
        panel.addView(label("PLAYONLINE CHECKS AND UPDATES\n"+RuntimePresets.updaterLabel(this),15,TEXT));
        panel.addView(label("Box64 is the recommended starting point for the faster Thor file-check path. The updater has its own staged client and Windows environment.",13,MUTED));
        final String[] values={"box64","fex","follow"};Spinner update=new Spinner(this);update.setContentDescription("Updater purpose preset");update.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Box64 · faster Thor file checks","FEX · alternate updater","Use gameplay engine"}));panel.addView(update);
        String saved=getSharedPreferences("runtime",0).getString("updater_engine","follow");update.setSelection(Math.max(0,Arrays.asList(values).indexOf(saved)));
        button(panel,"Apply updater choice",()->{try{RuntimePresets.updater(this,values[update.getSelectedItemPosition()]);draw();}catch(Exception e){error(e);}}).setEnabled(!rt.alive());
        button(panel,"Advanced runtime checks and installation",()->navigate("Advanced",""),true);
        button(panel,"Graphics and compatibility settings",()->navigate("Advanced","Graphics & display"),true);
    }
    private void playPage()throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);ServerRuntime sr=ServerRuntime.get(this);ClientStore source=store(this);
        SetupGuide.Step step=SetupGuide.next(this);boolean managed=SetupGuide.local(this);JSONObject prepared=rt.preparationState();
        LinearLayout play=featuredCard("Play FINAL FANTASY XI");
        String clientMessage=rt.alive()?rt.status:!rt.launchError.isEmpty()?rt.launchError:step.ready()?"Ready to play":"Setup needed · "+step.title;
        runtimeStatus=label(clientMessage,15,ACCENT);play.addView(runtimeStatus);
        String serverMessage=sr.status;
        if(!sr.alive()&&serverMessage.equals("Import your existing server and SQL backup to begin."))serverMessage=sr.deployment().has("generation")?"Server stopped · Play will start it":"Server setup needed";
        serverStatus=label(managed?serverMessage:"External server · start it before choosing Play",14,MUTED);if(managed)play.addView(serverStatus);
        play.addView(label(managed?"Server on this device · Play starts it and waits for all services before opening FFXI.":"Existing server · "+source.config().host,15,TEXT));
        play.addView(label(RuntimePresets.gameplayLabel(this),13,MUTED));
        JSONObject activePair=InstallationSummary.snapshot(this);JSONObject activeClient=activePair.optJSONObject("client"),activeServer=activePair.optJSONObject("server");
        if(activeClient!=null)play.addView(label("Client "+InstallationSummary.shortId(activeClient.optString("generation"))+" · Loader "+InstallationSummary.shortId(activeClient.optString("loader_sha256"))+(managed&&activeServer!=null?"\nServer "+InstallationSummary.shortId(activeServer.optString("generation"))+" · Database "+activeServer.optString("database","not deployed"):""),12,MUTED));
        if(prepared.has("current")){
            JSONObject current=prepared.getJSONObject("current");play.addView(label("Active client · "+current.optString("generation").substring(0,Math.min(8,current.optString("generation").length())),13,MUTED));
            JSONObject previous=object(current,"last_launch"),process=object(previous,"process");
            if(process.has("child_exit"))play.addView(label(process.optInt("child_exit",-1)==0?"Last client session closed normally.":"Last client session needs attention. See Diagnostics for details.",13,MUTED));
        }
        if(!step.ready()){
            play.addView(label("Next: "+step.title+"\n"+step.detail,15,TEXT));
            button(play,"Continue setup",()->navigate("Setup",""),true);
        }else if(!rt.alive()){
            EditText address=null;if(!managed)address=loginField(play,"Server address",source.config().host,android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
            String loginHost=managed?"127.0.0.1":source.config().host;boolean savedLogin=SavedLogin.matches(this,loginHost);
            EditText account=loginField(play,"Account",SavedLogin.account(this,loginHost),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            EditText secret=loginField(play,"Password",SavedLogin.password(this,loginHost),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setSaveEnabled(false);loginPassword=secret;
            CheckBox remember=new CheckBox(this);remember.setText("Remember account and password for quick login");remember.setTextColor(TEXT);remember.setChecked(savedLogin);play.addView(remember);
            final EditText externalAddress=address;
            play.addView(label("Saved logins stay in this app and are included in complete session backups. The server stays running after logout until you stop it.",13,MUTED));
            Button launch=button(play,savedLogin?"Quick login":"Play",()->{
                LoginRequest login=null;
                try{
                    if(rt.alive())throw new IOException("The client is already starting or running");
                    login=new LoginRequest(managed?"127.0.0.1":externalAddress.getText().toString().trim(),account.getText().toString(),secret.getText().toString());
                    if(remember.isChecked())SavedLogin.save(this,login.host,account.getText().toString(),secret.getText().toString());
                    else if(SavedLogin.matches(this,loginHost))SavedLogin.forget(this);
                    LaunchConfig old=source.config();source.saveConfig(new LaunchConfig(login.host,old.region,old.polCore));
                    String ticket=RuntimeService.queueLogin(login);login=null;secret.setText("");account.setText("");
                    android.content.SharedPreferences prefs=getSharedPreferences("runtime",0);
                    startForegroundService(new Intent(this,RuntimeService.class).putExtra("operation","launch").putExtra("play_flow",true).putExtra("managed_server",managed).putExtra("login_ticket",ticket)
                        .putExtra("renderer",prefs.getString("renderer","turnip26")).putExtra("audio",true).putExtra("display_profile",prefs.getString("display_profile","windowed720")).putExtra("startup_trace",prefs.getBoolean("startup_trace",false)));
                    openPlayDisplay=true;rt.launchError="";rt.status="Preparing to play…";
                }catch(Exception e){if(login!=null)login.close();RuntimeService.clearLogin();error(e);}
            });
            if(externalAddress!=null)watchLoginHost(externalAddress,account,secret,remember,launch,"Play");
            if(savedLogin)button(play,"Forget saved login",()->{try{SavedLogin.forget(this);draw();}catch(Exception e){error(e);}});
        }
        if(rt.alive()){
            button(play,"Open client display",()->startActivity(new Intent(this,RuntimeActivity.class)),true).setEnabled(rt.displayAvailable());
            button(play,"Cancel / stop client",()->{openPlayDisplay=false;startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"));},true);
        }
        if(managed&&sr.alive())button(play,"Stop managed server",()->startForegroundService(new Intent(this,ServerService.class).setAction("stop"))).setEnabled(!rt.alive());
        installationCard();runtimePresetsCard();
        LinearLayout connection=card("Server selection");
        button(connection,"Use managed server on this device",()->chooseSetup("local"));
        button(connection,"Use an existing or Termux server",()->chooseSetup("external"));
        LinearLayout maintenance=card("Updates and backups");
        button(maintenance,"Build or update server source",()->navigate("Build",""),true);
        button(maintenance,"Update client with PlayOnline",()->navigate("Client","Client update · PlayOnline"),true);
        button(maintenance,"Backup and restore",()->navigate("Client","Backup and recovery"),true);
        button(maintenance,"Controller setup",()->navigate("Controller",""),true);
        supportTile();
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
        button(content,"Back to gameplay and updater presets",()->navigate("Play","Gameplay and updater presets"),true);
        button(content,"Historical GameHub reference",()->navigate("Profile",""),true);
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
        panel.addView(label("Complete session backups include this runtime and all Windows environments. Stop the client and server before exporting from Client → Backup and recovery.",14,MUTED));
    }
    private void clientPage() throws Exception {
        ClientStore s = store(this);
        JSONObject preparation=ClientRuntime.get(this).preparationState();
        boolean ready=preparation.has("current");
        if(ready){LinearLayout play=featuredCard("Client installation");play.addView(label("Your active client is ready. Use Play to log in and start the managed server.",15,TEXT));button(play,"Go to Play",()->navigate("Play",""),true);loaderSelectionCard(ClientRuntime.get(this));installationCard();basicGraphicsCard();}else supportTile();
        clientUpdateCard();
        if(!ready||preparation.has("candidate"))initializationCard(s);
        String problem=ClientRuntime.get(this).launchError;
        if(problem.isEmpty()&&!OperationProgress.client.running&&"Needs attention".equals(OperationProgress.client.outcome))problem=OperationProgress.client.step;
        if(ready&&!problem.isEmpty()){
            LinearLayout repair=card("Client needs attention");repair.addView(label(problem,14,ACCENT));
            String lower=problem.toLowerCase(Locale.ROOT);boolean dependency=lower.contains("dependenc")||lower.contains("prerequisite")||lower.contains("dll")||lower.contains("xiloader");
            button(repair,dependency?"Open loader and prerequisite repairs":"Open failure diagnostics",()->navigate(dependency?"Advanced":"Diagnostics",dependency?"Capture & diagnostics":""),true);
        }
        if (s.hasPendingImport() && !WorkService.busy) pendingImportCard(s);
        LinearLayout status = card(s.hasClient() ? "Imported client" : s.releasedImport() ? "Original import removed" : "Bring your FFXI installation");
        status.addView(label(s.summary(), 15, TEXT));
        status.addView(label(s.releasedImport()?"Your prepared client and its personal settings remain available. A new import can preserve USER and usr settings from that prepared client.":"Your imported files are the source for a separate working installation. Launch uses the activated preparation above. After testing it, remove the original from Manage backups and storage → Copies to reclaim space.", 14, MUTED));
        status.addView(label("Free space: " + storage(this).getUsableSpace() / 1073741824L + " GiB. Imports need room for a new full copy while retaining the active client.", 13, MUTED));
        preserve = new CheckBox(this); preserve.setText("Preserve current FFXI USER and PlayOnline usr settings on client import"); preserve.setTextColor(TEXT); preserve.setChecked(preserveOnImport); preserve.setOnCheckedChangeListener((b, checked) -> preserveOnImport = checked); status.addView(preserve);
        button(status, s.hasClient() ? "Import a complete client update ZIP" : "Import client ZIP", () -> pick("client")).setEnabled(!s.hasPendingImport());
        button(status, ready ? "Import and apply xiloader.exe" : "Import xiloader.exe", () -> pick("loader")).setEnabled((s.hasClient()||(s.releasedImport()&&ready))&&!ClientRuntime.get(this).alive()&&(!ready||!preparation.has("candidate")));

        LinearLayout launch = card("Connection");
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
        button(launch,"Advanced repairs and external launcher tools",()->navigate("Advanced","External launcher and repair tools"),true);

        LinearLayout backup = card("Backup and recovery");
        boolean backupIdle=!ClientRuntime.get(this).alive()&&!ServerRuntime.get(this).alive();
        button(backup, "Manage backups and storage", () -> startActivity(new Intent(this,BackupBrowserActivity.class)));
        button(backup, "Export complete session backup", () -> create("backup", "lsb-complete-session.zip")).setEnabled(backupIdle);
        button(backup, "Restore complete session backup", () -> confirm("Restore complete session", "This replaces this app’s settings, clients, runtimes, server and databases after the backup passes verification. Stop both runtimes first. Use LSB Restore Test to test your backup separately from your working installation.", () -> pick("restore"))).setEnabled(backupIdle);
        button(backup, "Restore legacy client backup", () -> confirm("Restore legacy client backup", "Older session backups contain only the imported client and connection settings. This restores that client and keeps a rollback copy.", () -> pick("restore-legacy"))).setEnabled(backupIdle&&!s.hasPendingImport());
        button(backup, "Switch to previous client", () -> confirm("Switch client", "Validate the previous client, then swap it with the current client?", () -> run("Validating previous client", (ctx, p) -> { store(ctx).rollback(p); return "Previous client restored. The other copy remains available for rollback."; }))).setEnabled(s.hasPrevious());
        backup.addView(label("Includes app settings, imported and prepared clients, all Windows environments, both runtimes, server source and deployments, databases and a fresh SQL dump. Temporary process files are recreated. The backup contains private server credentials and account data; keep it private. Restoring needs room for the complete unpacked installation alongside any existing data.", 13, MUTED));
    }
    private void basicGraphicsCard(){
        LinearLayout panel=card("Gameplay display");panel.addView(label(RuntimePresets.gameplayLabel(this),14,TEXT));
        setting(panel,"Show DXVK game FPS","dxvk_hud",true,"");
        CheckBox fullscreen=setting(panel,"Fullscreen app and game","fullscreen",false,"Swipe from an edge to show Android controls.");fullscreen.setOnCheckedChangeListener((b,v)->Fullscreen.set(this,v));
        button(panel,"Choose gameplay or updater preset",()->navigate("Play","Gameplay and updater presets"),true);
        button(panel,"Advanced graphics and compatibility",()->navigate("Advanced","Graphics & display"),true);
    }
    private void clientUpdateCard() throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);JSONObject state=rt.preparationState();
        JSONObject candidate=state.optJSONObject("candidate");
        boolean staged=candidate!=null&&candidate.has("update_of");
        String phase=staged?candidate.optString("update_phase"):"";
        boolean verified="verified".equals(phase);
        boolean idle=!rt.alive()&&!ServerRuntime.get(this).alive();
        LinearLayout update=card("Client update · PlayOnline");
        update.addView(label(getPackageName().endsWith(".restoretest")?"Test client updates here while your regular LSB installation stays separate.":"Use LSB Restore Test for the first update test, keeping this working installation available.",15,TEXT));
        update.addView(label("The updater copies your prepared client and Windows environment, then opens PlayOnline in that staged copy. Your active client stays available until you verify and activate the update. Stop the client and managed server first.",14,MUTED));
        if(staged){
            update.addView(label(verified?"Updated copy verified and ready for activation.":"An update copy is staged. Resume PlayOnline to continue downloading or checking files.",15,ACCENT));
            String version=candidate.optString("client_version","");if(!version.isEmpty())update.addView(label("Staged client: "+version,14,MUTED));
        }else if(candidate!=null)update.addView(label("Finish or discard the existing staged preparation before starting a client update.",14,ACCENT));
        update.addView(label("Updater runtime",14,MUTED));
        String[] updaterEngines={"","fex","box64"};
        String chosenEngine=getSharedPreferences("runtime",0).getString("updater_engine","");
        Spinner updaterEngine=new Spinner(this);
        updaterEngine.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Follow game runtime","FEX runtime","Box64 compatibility"}));
        update.addView(updaterEngine);
        updaterEngine.setSelection("fex".equals(chosenEngine)?1:"box64".equals(chosenEngine)?2:0);
        updaterEngine.setEnabled(idle);
        updaterEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                String value=updaterEngines[position];
                if(value.equals(getSharedPreferences("runtime",0).getString("updater_engine","")))return;
                android.content.SharedPreferences.Editor edit=getSharedPreferences("runtime",0).edit();
                if(value.isEmpty())edit.remove("updater_engine");else edit.putString("updater_engine",value);
                edit.apply();
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        update.addView(label("Follow game runtime uses your installed game engine. You can select a runtime here to compare repair speed. Each launch briefly measures file access before opening PlayOnline; Diagnostics includes the results.",13,MUTED));
        if(!rt.fexInstalled())update.addView(label("Install FEX from the Runtime tab before selecting it here.",13,MUTED));
        CheckBox updaterAcceleration=setting(update,"Updater runtime acceleration","updater_syscall_filter",true,"Reduces runtime overhead when supported. A startup check keeps compatibility mode if unavailable. Turn off to compare; stop and reopen PlayOnline after changing this setting.");
        updaterAcceleration.setEnabled(idle&&!WorkService.busy);
        CheckBox updaterGraphics=setting(update,"Hardware PlayOnline graphics","updater_hardware_graphics",true,"Uses the GPU to draw PlayOnline while it checks files. A startup check falls back to compatibility graphics if unavailable. Stop and reopen PlayOnline after changing this setting.");
        updaterGraphics.setEnabled(idle&&!WorkService.busy);
        button(update,staged?"Open or resume PlayOnline update":"Prepare update and open PlayOnline",()->confirm(staged?"Resume client update":"Prepare a client update",staged?"Open PlayOnline in the staged copy? Reopening it requires verification again before activation.":"Create a full update copy, then move ROM/0/0.dat aside in that copy to trigger PlayOnline file repair? Your active client and its Windows environment are retained. This needs space for another complete client.",()->startInitialization("update-client"))).setEnabled(idle&&rt.installed()&&state.has("current")&&(candidate==null||staged));
        update.addView(label("First finish any PlayOnline Viewer update and let it restart. At its main menu: Check Files → FINAL FANTASY XI → Check Files → File Repair → Yes. Wait for repair to finish, then choose Exit Viewer. If the viewer closes, reopen it here. A blank update page does not mean repair is ready; try Refresh display from the display menu, then export support if it stays blank.",14,TEXT));
        update.addView(label("FINAL FANTASY XI must appear in PlayOnline’s Check Files list. If it is missing, the installation needs the official retail registration/login setup first. LSB does not bypass that step. The update copy stays separate until you verify and activate it.",13,MUTED));
        button(update,"Open updater display",()->startActivity(new Intent(this,RuntimeActivity.class))).setEnabled(rt.alive());
        button(update,"Stop updater",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"))).setEnabled(rt.alive());
        button(update,"Verify completed update",()->confirm("Verify updated client","Only continue after PlayOnline reports file repair complete and you have exited the viewer. This checks the updated copy before activation.",()->startInitialization("verify-client-update"))).setEnabled(staged&&idle);
        button(update,"Activate verified update",()->confirm("Activate updated client","Switch this app to the verified update? Your previous prepared client remains available for rollback. The new client may need a matching server revision and xiloader; this does not update either of them.",()->run("Activating client update",(ctx,p)->ClientRuntime.get(ctx).activateClientUpdate()))).setEnabled(verified&&idle);
        button(update,"Discard staged update",()->confirm("Discard staged update","Delete the update copy and its Windows environment? Downloaded changes in that copy will be lost. Your active prepared client is retained.",()->run("Discarding client update",(ctx,p)->ClientRuntime.get(ctx).discardPreparation()))).setEnabled(staged&&idle);
        button(update,"Restore previous prepared client",()->confirm("Restore previous prepared client","Switch back to the retained client and matching Windows environment? Stop the managed server before switching client versions.",()->run("Restoring previous prepared client",(ctx,p)->ClientRuntime.get(ctx).rollbackPreparation()))).setEnabled(!staged&&state.has("previous")&&idle);
    }
    private EditText loginField(LinearLayout card,String title,String value,int type){
        card.addView(label(title,14,MUTED));EditText field=new EditText(this);field.setSingleLine(true);field.setTextColor(TEXT);field.setInputType(type);field.setText(value);
        field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        card.addView(field);return field;
    }
    private void loaderSelectionCard(ClientRuntime rt)throws Exception {
        JSONObject state=rt.loaderSelectionState();
        String imported=state.optString("imported_sha256"),active=state.optString("prepared_sha256");
        boolean available=state.optBoolean("prepared_available"),different=state.optBoolean("differs"),pending=state.optBoolean("recovery_pending"),staged=rt.preparationState().has("candidate");
        LinearLayout loader=card("Active xiloader");
        loader.addView(label("Imported loader: "+(imported.isEmpty()?"Unavailable":imported.substring(0,Math.min(12,imported.length()))),14,TEXT));
        loader.addView(label("Prepared client loader: "+(active.isEmpty()?"Unavailable":active.substring(0,Math.min(12,active.length()))),14,TEXT));
        loader.addView(label(imported.isEmpty()?"Import an xiloader before changing the prepared client.":!available?"No prepared loader is available.":pending?"An interrupted loader change needs recovery. Apply the imported loader again before logging in.":different?"The prepared client uses a different xiloader from your import.":"The prepared client uses your imported xiloader.",15,different||pending?ACCENT:MUTED));
        loader.addView(label("Apply the imported xiloader to the prepared client while preserving its updated game files and Windows environment. Successful login still needs to be checked.",14,MUTED));
        if(staged)loader.addView(label("Finish or discard the staged client preparation or update before changing xiloader.",14,ACCENT));
        button(loader,"Use imported xiloader in prepared client",()->run("Applying imported xiloader",(ctx,p)->ClientRuntime.get(ctx).applyImportedLoader(imported,p))).setEnabled(available&&!imported.isEmpty()&&(different||pending)&&!staged&&!rt.alive()&&!WorkService.busy);
    }
    private void loginCard(ClientStore source)throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);JSONObject state=rt.preparationState();
        JSONObject stagedClient=state.optJSONObject("candidate");boolean updateStaged=stagedClient!=null&&stagedClient.has("update_of");
        LinearLayout card=featuredCard("Play FINAL FANTASY XI");runtimeStatus=label(rt.status,15,ACCENT);card.addView(runtimeStatus);
        card.addView(label("Start your Termux or managed server, then log in. Your working client and loader are kept together.",15,TEXT));
        loaderSelectionCard(rt);
        LinearLayout graphics=card("Graphics & display");
        graphics.addView(label("FFXI display setting",14,MUTED));
        final String[] displayProfiles={"windowed720","windowed540","preserve","restore"};
        Spinner display=new Spinner(this);display.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Windowed 1280×720","Windowed 960×540 (lighter)","Keep current display settings","Restore saved original display settings"}));
        int selected=Arrays.asList(displayProfiles).indexOf(getSharedPreferences("runtime",0).getString("display_profile","windowed720"));
        display.setSelection(Math.max(0,selected));graphics.addView(display);
        graphics.addView(label("Applied at launch; original display settings remain available to restore. 960×540 reduces rendering load.",13,MUTED));
        setting(graphics,"Remove game window borders","borderless",true,"Removes the Windows frame and top-left offset on the next launch, preserving the measured game-window size. Applies to windowed FFXI in either Android display mode.");
        setting(graphics,"Show DXVK game FPS","dxvk_hud",true,"");
        CheckBox fullscreen=setting(graphics,"Fullscreen app and game","fullscreen",false,"Hides Android bars and the game's bottom status strip. Use the game menu to exit fullscreen; swipe from an edge for Android controls. Rendering resolution stays the same.");
        fullscreen.setOnCheckedChangeListener((button,enabled)->Fullscreen.set(this,enabled));
        supportTile();
        LinearLayout proven=card("Proven fixes");
        proven.addView(label("Confirmed on Thor: runtime v3 restores correct graphics, two shader compiler workers improve smoothness, and runtime syscall filtering delivered the successful Trial F run. Filtering is now on by default for the tested profile.",15,TEXT));
        runtimeSelector(proven,rt);
        CheckBox refresh60=new CheckBox(this);refresh60.setText("60 Hz display refresh");refresh60.setTextColor(TEXT);refresh60.setChecked(getSharedPreferences("runtime",0).getInt("display_fps",30)==60);
        refresh60.setOnCheckedChangeListener((b,v)->getSharedPreferences("runtime",0).edit().putInt("display_fps",v?60:30).apply());proven.addView(refresh60);
        proven.addView(label("Reduced reported drops into the teens. This is the display refresh cap; it does not raise the game's FPS limit.",13,MUTED));
        setting(proven,"DXVK 2.7.1","dxvk_271",false,"Previously improved play on Thor. Off selects 2.5.3. Compatibility checks retain automatic fallback.");
        setting(proven,"Native Surface display · shared memory","native_surface",true,"Confirmed working capture and direct Android display; bypasses compressed bitmap delivery.");
        setting(proven,"Shared-memory Vulkan presentation","shm_upload",true,"Verified active in the successful run. Reduces transfer traffic, with automatic fallback if its startup check fails. Its individual FPS benefit is not isolated.");
        CheckBox compilerWorkers=setting(proven,"Two shader compiler workers","dxvk_two_compilers",false,"Improved world-play smoothness on Thor in the latest comparisons. Recommended with Turnip system-memory rendering off. Stop and relaunch after changing this setting.");
        CheckBox acceleration=setting(proven,"Runtime syscall filtering","proot_acceleration",true,"The successful Trial F optimization, now enabled by default. Requires the tested FEX, Turnip 26, DXVK 2.7.1 and two-worker profile with experiments off. A startup check retains compatibility mode if unavailable. Turn off for troubleshooting; stop and relaunch to apply.");
        proven.addView(label("The button returns trials to Baseline, enables syscall filtering and two compiler workers, and turns off Turnip system-memory rendering and staged geometry uploads. Your Turnip driver, display and other settings stay as selected.",13,MUTED));
        proven.addView(label("Always applied: corrected FEX x87 condition flags in runtime v3, Android shared-memory capture, and removal of recurring startup-observer stalls. Stop and relaunch after changing runtime or display options.",13,MUTED));
        LinearLayout trials=card("Optimization trials");
        trials.addView(label("Choose one test, then stop and relaunch. Start from the tested shader settings and Windowed 1280×720; keep border removal and startup capture off for comparisons.",15,TEXT));
        final String[] trialValues={"none","one_compiler","cached_dynamic","gpl_fast"};
        final String[] trialHelp={
            "The tested baseline uses two shader compiler workers and runtime syscall filtering. F has moved to Proven fixes and is enabled by default. Stop and relaunch after switching.",
            "A uses one compiler worker. Your comparison suggests a small improvement, but the timing logs do not establish a consistent win. New shaders may take longer to compile.",
            "D places dynamic geometry buffers in CPU-cached memory. It may reduce CPU access costs while buildings and characters appear, but can reduce GPU throughput. Rendering checks must pass before it is applied.",
            "E skips background compilation of optimized shader pipelines. It may reduce CPU competition during camera pans, but can reduce GPU throughput and retain more base pipelines. This is a new experiment; B remains disabled."
        };
        Spinner trial=new Spinner(this);trial.setContentDescription("Performance trial");
        trial.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Baseline · proven settings","A · One compiler worker","D · Cached geometry buffers","E · Fewer shader optimization jobs"}));
        int selectedTrial=Math.max(0,Arrays.asList(trialValues).indexOf(getSharedPreferences("runtime",0).getString("performance_trial","none")));
        trial.setSelection(selectedTrial);trials.addView(trial);
        final TextView trialDescription=label(trialHelp[selectedTrial],14,MUTED);trialDescription.setContentDescription("Performance trial details");trials.addView(trialDescription);
        trial.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                String value=trialValues[position];
                trialDescription.setText(trialHelp[position]);
                if(!value.equals(getSharedPreferences("runtime",0).getString("performance_trial","none")))getSharedPreferences("runtime",0).edit().putString("performance_trial",value).apply();
            }
        });
        trials.addView(label("A, D and E remain experimental and temporarily use compatibility mode to avoid untested combinations with syscall filtering. Select Baseline for normal play with the proven filtering setting. Support ZIPs retain six sessions with their effective settings and timing history.",13,MUTED));
        LinearLayout past=card("Past experiments");
        past.addView(label("These rendering experiments have no demonstrated benefit in the latest comparisons. Keep them off when using the tested shader settings; saved choices remain available for troubleshooting.",15,TEXT));
        past.addView(label("Retired B · Retain shader pipelines: froze before the menu despite passing its startup pixel check. Retired C · Lighter 3D scene: severe camera-pan slowdowns. Both are disabled; do not repeat these tests.",14,MUTED));
        CheckBox turnipSysmem=setting(past,"Turnip system-memory rendering","turnip_sysmem",false,"Changes how the Turnip GPU driver renders; it does not select a different driver. No demonstrated benefit beyond two compiler workers. Recommended off. Stop and relaunch to apply.");
        CheckBox stagedGeometry=setting(past,"Staged geometry uploads","dxvk_staged_buffers",false,"The recent camera-pan comparison found no improvement. Recommended off. Requires DXVK 2.7.1 and retains the startup pixel check and compatibility fallback.");
        button(proven,"Use tested shader settings",()->{
            getSharedPreferences("runtime",0).edit().putBoolean("dxvk_two_compilers",true).putBoolean("proot_acceleration",true).putBoolean("turnip_sysmem",false).putBoolean("dxvk_staged_buffers",false).putString("performance_trial","none").apply();
            trial.setSelection(0);compilerWorkers.setChecked(true);acceleration.setChecked(true);turnipSysmem.setChecked(false);stagedGeometry.setChecked(false);
            toast("Syscall filtering and two compiler workers on; experiments off. Stop and relaunch to apply.");
        });
        past.addView(label("Earlier metadata caching, quieter status overlays and hidden-cursor polling did not noticeably improve gameplay. Hidden-cursor polling did remove 85% of position queries in the 0.5.20 run and remains active. Full x87 precision and changing DXVK versions did not fix the old corruption. The CPU-feature correction alone also left it unresolved; it remains part of runtime v3 for correctness.",13,MUTED));
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
        String loginHost=source.config().host;boolean savedLogin=SavedLogin.matches(this,loginHost);
        EditText account=loginField(accountColumn,"Account",SavedLogin.account(this,loginHost),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText secret=loginField(passwordColumn,"Password",SavedLogin.password(this,loginHost),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setSaveEnabled(false);loginPassword=secret;
        CheckBox remember=new CheckBox(this);remember.setText("Remember account and password for quick login");remember.setTextColor(TEXT);remember.setChecked(savedLogin);card.addView(remember);
        card.addView(label("Saved logins stay in this app and are included in complete session backups. Reported login failures stop the launcher so you can retry here.",13,MUTED));
        Button launch=button(card,savedLogin?"Quick login":"Launch FFXI",()->{
            LoginRequest login=null;
            try{
                if(rt.alive())throw new IOException("Stop the current client first");
                login=new LoginRequest(server.getText().toString().trim(),account.getText().toString(),secret.getText().toString());
                if(remember.isChecked())SavedLogin.save(this,login.host,account.getText().toString(),secret.getText().toString());
                else if(SavedLogin.matches(this,loginHost))SavedLogin.forget(this);
                LaunchConfig old=source.config();source.saveConfig(new LaunchConfig(login.host,old.region,old.polCore));
                String ticket=RuntimeService.queueLogin(login);login=null;secret.setText("");account.setText("");
                rt.launchError="";
                String renderer=getSharedPreferences("runtime",MODE_PRIVATE).getString("renderer","turnip26");
                String displayProfile=displayProfiles[display.getSelectedItemPosition()];getSharedPreferences("runtime",MODE_PRIVATE).edit().putString("display_profile",displayProfile).putBoolean("startup_trace",startupTrace.isChecked()).apply();
                startForegroundService(new Intent(this,RuntimeService.class).putExtra("operation","launch").putExtra("renderer",renderer).putExtra("audio",true).putExtra("login_ticket",ticket).putExtra("display_profile",displayProfile).putExtra("startup_trace",startupTrace.isChecked()));
                startActivity(new Intent(this,RuntimeActivity.class));
            }catch(Exception e){if(login!=null)login.close();RuntimeService.clearLogin();error(e);}
        });launch.setEnabled(rt.installed()&&!rt.alive());
        watchLoginHost(server,account,secret,remember,launch,"Launch FFXI");
        if(savedLogin)button(card,"Forget saved login",()->{try{SavedLogin.forget(this);draw();}catch(Exception e){error(e);}});
        button(card,"Open client display",()->startActivity(new Intent(this,RuntimeActivity.class))).setEnabled(rt.alive());
        button(card,"Stop client",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"))).setEnabled(rt.alive());
        button(diagnostics,"Check launcher dependencies",()->startInitialization("check-launcher")).setEnabled(!rt.alive());
        button(diagnostics,"View launch results",()->{try{JSONObject current=rt.preparationState().getJSONObject("current");showText("Launch results",current.has("last_launch")?current.getJSONObject("last_launch").toString(2):"No launch check has run yet.");}catch(Exception e){error(e);}});
        {
            diagnostics.addView(label("If launch diagnostics identify a missing dependency, select its official x86 installer. Repair makes a full recovery copy, runs the installer, and activates it only after client and loader checks pass.",14,MUTED));
            button(diagnostics,"Select launcher prerequisite (.exe)",()->pick("prerequisite")).setEnabled(!updateStaged&&!rt.alive());
            button(diagnostics,"Repair launcher prerequisites",()->startInitialization("repair-launcher")).setEnabled(!updateStaged&&!rt.alive()&&state.optBoolean("prerequisite_selected"));
        }
    }
    private void watchLoginHost(EditText server,EditText account,EditText secret,CheckBox remember,Button launch,String normalLabel){
        server.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                // Never carry a saved password into an edited server address.
                String host=s.toString();boolean saved=SavedLogin.matches(MainActivity.this,host);
                account.setText(SavedLogin.account(MainActivity.this,host));secret.setText(SavedLogin.password(MainActivity.this,host));
                remember.setChecked(saved);launch.setText(saved?"Quick login":normalLabel);
            }
            public void afterTextChanged(android.text.Editable value){}
        });
    }
    private void initializationCard(ClientStore source)throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);JSONObject prepared=rt.preparationState();
        boolean candidate=prepared.has("candidate"), copied=candidate&&prepared.getJSONObject("candidate").optBoolean("copy_complete");
        boolean repair=candidate&&prepared.getJSONObject("candidate").has("repair_of");
        boolean update=candidate&&prepared.getJSONObject("candidate").has("update_of");
        LinearLayout card=card("Prepare PlayOnline and FFXI");
        if(runtimeStatus==null){runtimeStatus=label(rt.status,15,ACCENT);card.addView(runtimeStatus);}
        card.addView(label("Create a separate working copy and Windows environment, register the selected client, then test its PlayOnline and FFXI interfaces. Successful checks activate both copies together. This step does not log in or start the game.",15,TEXT));
        card.addView(label("Free runtime storage: "+rt.home.getUsableSpace()/1073741824L+" GiB. Preparation needs room for another full client and Windows copy; space is checked before copying. This may take several minutes.",14,MUTED));
        if(prepared.has("current"))card.addView(label("A validated preparation is available. A new attempt preserves it until checks pass.",14,ACCENT));
        if(candidate)card.addView(label(update?"A client update is staged. Continue it from Client update · PlayOnline.":copied?"A staged working copy is available. Retry uses its captured region and files without copying the full import again.":"The previous copy was interrupted. Preparation will replace only that incomplete candidate.",14,MUTED));
        if(!rt.installed())card.addView(label("Install the Windows runtime from More → Advanced → Runtime installation first.",14,MUTED));
        button(card,repair?"Retry launcher prerequisite repair":copied?"Retry client initialization":"Prepare imported client",()->{
            try{if(!copied&&!repair&&tab.equals("Client"))saveConnection();startInitialization(repair?"repair-launcher":"initialize");}catch(Exception e){error(e);}
        }).setEnabled(!update&&(source.hasClient()||copied||repair)&&!source.hasPendingImport()&&rt.installed()&&!rt.alive());
        if(source.releasedImport())card.addView(label("Reimport a complete client ZIP to create a fresh preparation. Repairs and PlayOnline updates can still copy your active prepared client.",14,MUTED));
        button(card,"Open initialization display",()->startActivity(new Intent(this,RuntimeActivity.class))).setEnabled(rt.alive());
        button(card,"Stop initialization",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"))).setEnabled(rt.alive());
        button(card,"View preparation results",()->{try{showText("Prepared client",rt.preparationState().toString(2));}catch(Exception e){error(e);}});
        if(copied&&!update){
            card.addView(label("If diagnostics identify a missing dependency, select its official x86 prerequisite installer. It runs interactively in the staged copy, then registration checks run again.",14,MUTED));
            button(card,"Select prerequisite installer (.exe)",()->pick("prerequisite")).setEnabled(!rt.alive());
            button(card,"Run prerequisite and retry checks",()->startInitialization(repair?"repair-launcher":"installer")).setEnabled(!rt.alive()&&prepared.optBoolean("prerequisite_selected"));
        }
        button(card,"Discard staged preparation",()->confirm("Discard staged copy","Remove the unsuccessful working copy and its Windows environment? The original import and validated preparations are kept.",()->run("Discarding staged preparation",(ctx,p)->ClientRuntime.get(ctx).discardPreparation()))).setEnabled(!update&&candidate&&!rt.alive());
        button(card,"Restore previous preparation",()->run("Restoring previous preparation",(ctx,p)->ClientRuntime.get(ctx).rollbackPreparation())).setEnabled(!update&&prepared.has("previous")&&!rt.alive());
        card.addView(label("Complete session backups include prepared clients and their Windows environments, including FEX. Export from Backup and recovery after stopping both runtimes.",13,MUTED));
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
    private Spinner spinner(LinearLayout parent,String title,String[] values,int selected) {
        parent.addView(label(title,14,MUTED));Spinner choice=new Spinner(this);
        choice.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));choice.setSelection(selected);parent.addView(choice);return choice;
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
        ServerRuntime sr=ServerRuntime.get(this);boolean running=sr.alive();serverAliveUi=running;
        boolean idle=!running&&!ClientRuntime.get(this).alive();
        LinearLayout live=featuredCard("Your LandSandBoat server");serverStatus=label(sr.status,15,ACCENT);live.addView(serverStatus);
        serverStartup=label(sr.startupLog(),12,MUTED);live.addView(serverStartup);
        JSONObject active=sr.deployment();
        if(active.has("generation"))live.addView(label("Last saved count · "+active.optInt("accounts")+" accounts · "+active.optInt("characters")+" characters\nExpected client: "+active.optString("expected_client"),15,TEXT));
        else live.addView(label("Open the Build tab to fetch or import source, compile with jemalloc, prepare a database, and check the exact build before deploying.",15,TEXT));
        button(live,"Start managed server",()->startForegroundService(new Intent(this,ServerService.class))).setEnabled(sr.installed()&&active.has("generation")&&!running);
        button(live,"Stop managed server",()->startForegroundService(new Intent(this,ServerService.class).setAction("stop"))).setEnabled(running);
        if(active.has("generation")){
            final String generation=active.getString("generation");
            button(live,"Repair missing profile service",()->run("Building missing profile service with jemalloc",(ctx,p)->ServerRuntime.get(ctx).repairProfile(generation,p))).setEnabled(idle&&sr.toolsCurrent());
            live.addView(label("For newer xiloader versions: stop the client and server, then repair once if this deployment lacks its profile service. This compiles only the missing service from the deployed source with jemalloc, using the worker count selected on Build. Progress appears in Diagnostics → Server operation log. Your database and other server programs are retained.",13,MUTED));
        }
        live.addView(label("Missing map files are downloaded before startup; this first download needs an internet connection. Progress appears here and in Server logs. Wait for Ready before connecting: mob scripts can take several minutes to load after login opens. Stop any server in Termux or the other LSB app first; they share login/map ports. Then connect to 127.0.0.1. Stop the client and server before backups or maintenance.",13,MUTED));
        supportTile();
        button(live,"Open Build tab",()->{tab="Build";draw();},true);
        LinearLayout accounts=card("Create account");
        accounts.addView(label("Create a normal player account in the deployed database. Existing accounts and characters are preserved. Stop the client and server first.",15,TEXT));
        EditText username=loginField(accounts,"Account name","",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);username.setContentDescription("New server account name");
        EditText password=loginField(accounts,"Password","",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setContentDescription("New server account password");serverPassword=password;
        EditText confirmation=loginField(accounts,"Confirm password","",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);confirmation.setContentDescription("Confirm server account password");serverPasswordConfirm=confirmation;
        accounts.addView(label("Account: 1–16 printable ASCII characters. Password: 1–32. Passwords are used once, never saved in launcher settings or support logs. Account creation checks the imported server's supported login format.",13,MUTED));
        button(accounts,"Create player account",()->{
            if(!password.getText().toString().equals(confirmation.getText().toString()))throw new IllegalArgumentException("The passwords do not match");
            final ServerAccountRequest credentials=new ServerAccountRequest(username.getText().toString(),password.getText().toString());
            password.setText("");confirmation.setText("");
            run("Creating player account",new WorkService.Job(){
                public String run(Context context,SafeZip.Progress progress)throws Exception{String result=ServerRuntime.get(context).createAccount(credentials,progress);context.getSharedPreferences("setup",0).edit().putBoolean("account_ready",true).apply();return result;}
                public void close(){credentials.close();}
            });
        }).setEnabled(sr.toolsCurrent()&&active.has("generation")&&idle);
        if(!active.has("generation"))accounts.addView(label("Deploy your matching server and database first.",13,MUTED));
        else if(!sr.toolsCurrent())accounts.addView(label("Open Build → Server build tools to update the runtime first.",13,MUTED));
        LinearLayout recovery=card("Database backup & restore");
        recovery.addView(label("Full database backups include accounts, characters and world data. Keep an exported copy outside the app before making changes.",15,TEXT));
        button(recovery,"Export full database backup",()->create("server-db","lsb-database.sql.gz")).setEnabled(active.has("generation")&&idle);
        button(recovery,"Prepare a replacement database on Build",()->{tab="Build";draw();},true);
        button(recovery,"Restore previous server + database",()->confirm("Restore previous deployment","This switches both server files and player data to the retained previous generation. Progress made after that snapshot remains in the other generation.",()->run("Switching server generation",(ctx,p)->ServerRuntime.get(ctx).perform("rollback",false,p)))).setEnabled(active.has("generation")&&idle);
        LinearLayout checkpoints=card("Database checkpoints");
        checkpoints.addView(label("Small dated saves of accounts, characters and world data, without copying the client or runtimes. Stop the client and server first. Full working-combination backups remain the way to recover after an app uninstall or a server build change.",15,TEXT));
        SharedPreferences checkpointPrefs=getSharedPreferences("server",0);int[] keepValues={2,3,5,10};int selectedKeep=checkpointPrefs.getInt("checkpoint_keep",5),keepIndex=2;
        for(int i=0;i<keepValues.length;i++)if(keepValues[i]==selectedKeep)keepIndex=i;
        Spinner retention=spinner(checkpoints,"Checkpoints to keep",new String[]{"Keep newest 2","Keep newest 3","Keep newest 5","Keep newest 10"},keepIndex);retention.setContentDescription("Database checkpoint retention");
        checkpoints.addView(label("The selected limit applies after the next successful save. Older checkpoints are then removed. These saves are stored inside the app and included in complete backups.",13,MUTED));
        button(checkpoints,"Save database checkpoint",()->{
            final int keep=keepValues[retention.getSelectedItemPosition()];checkpointPrefs.edit().putInt("checkpoint_keep",keep).apply();
            run("Saving database checkpoint",(ctx,p)->ServerRuntime.get(ctx).performCheckpoint("create-checkpoint",null,keep,p));
        }).setEnabled(active.has("generation")&&idle);
        JSONArray saved=sr.checkpoints();String[] saveLabels=new String[Math.max(1,saved.length())];
        if(saved.length()==0)saveLabels[0]="No database checkpoints yet";
        else for(int i=0;i<saved.length();i++){
            JSONObject item=saved.getJSONObject(i);JSONObject pair=object(item,"compatibility");String buildId=pair.optString("build_id","");
            saveLabels[i]=receiptTime(item,"created_at")+" · "+item.optInt("accounts")+" accounts / "+item.optInt("characters")+" characters · "+String.format(Locale.ROOT,"%.1f MiB",item.optLong("bytes")/1048576.0)+" · "+(buildId.isEmpty()?"legacy build":buildId.substring(0,Math.min(8,buildId.length())));
        }
        Spinner checkpointChoice=spinner(checkpoints,"Saved database",saveLabels,0);checkpointChoice.setContentDescription("Saved database checkpoint");
        button(checkpoints,"Restore selected database checkpoint",()->{
            JSONObject selected=saved.optJSONObject(checkpointChoice.getSelectedItemPosition());final String id=selected.optString("checkpoint_id");final int keep=keepValues[retention.getSelectedItemPosition()];
            confirm("Restore database checkpoint",saveLabels[checkpointChoice.getSelectedItemPosition()]+"\n\nThis restores player and world progress to this date. The app checks that the checkpoint matches the deployed server build. Your current server and database are retained as the previous deployment.",()->run("Restoring database checkpoint",(ctx,p)->ServerRuntime.get(ctx).performCheckpoint("restore-checkpoint",id,keep,p)));
        }).setEnabled(active.has("generation")&&idle&&saved.length()>0);
        LinearLayout diagnostics=card("Server logs");
        button(diagnostics,"View server operation log",this::showLiveServerLog,true);
        button(diagnostics,"Probe saved server address",()->run("Checking server TCP ports",(ctx,p)->{String address=store(ctx).config().host;StringBuilder result=new StringBuilder("TCP reachability only: "+address+"\n");for(int port:new int[]{54231,54230,54001,51220,51240})try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(address,port),2500);result.append(port).append(": reachable\n");}catch(IOException e){result.append(port).append(": unavailable\n");}FilesEx.text(new File(ctx.getFilesDir(),"server-probe.txt"),result.toString());return result.toString();}));
    }
    private static JSONObject object(JSONObject parent,String name){JSONObject child=parent.optJSONObject(name);return child==null?new JSONObject():child;}
    private static String sourceIdentity(JSONObject source){
        if(source.length()==0)return "Source identity is unavailable. Fetch or import source to record its identity.";
        StringBuilder text=new StringBuilder();
        String repository=source.optString("repository"),ref=source.optString("ref"),commit=source.optString("commit"),origin=source.optString("origin"),snapshot=source.optString("snapshot_id");
        if(!repository.isEmpty())text.append("Repository: ").append(repository).append('\n');
        if(!ref.isEmpty())text.append("Requested ref: ").append(ref).append('\n');
        if(!commit.isEmpty())text.append("Exact commit: ").append(commit).append('\n');
        else text.append("Exact commit: unverified ZIP / legacy import\n");
        if(!origin.isEmpty())text.append("Origin: ").append(origin).append('\n');
        if(!snapshot.isEmpty())text.append("Snapshot: ").append(snapshot).append('\n');
        String client=source.optString("expected_client");if(!client.isEmpty())text.append("Expected client: ").append(client).append('\n');
        return text.toString().trim();
    }
    private static boolean sameSource(JSONObject first,JSONObject second){
        String a=first.optString("snapshot_id"),b=second.optString("snapshot_id");
        return !a.isEmpty()&&!b.isEmpty()&&a.equals(b);
    }
    private static String databaseModeLabel(String mode){
        switch(mode){case "fresh":return "Fresh database from this build's SQL";case "import":return "Imported SQL backup, updated for this build";case "copy-current":return "Copy of current accounts and characters, updated for this build";default:return "Database choice not recorded";}
    }
    private static String receiptTime(JSONObject receipt,String key){
        double seconds=receipt.optDouble(key,0);if(seconds<=0)return "not recorded";
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z",Locale.getDefault()).format(new Date((long)(seconds*1000)));
    }
    private void addLiveServerLog(LinearLayout panel){
        panel.addView(label("Updates automatically. Scroll up to read earlier output; return to the bottom to follow new lines.",13,MUTED));
        serverLogBody=label("Reading server output…",12,TEXT);serverLogBody.setTypeface(Typeface.MONOSPACE);
        serverLogScroll=new ScrollView(this);serverLogScroll.addView(serverLogBody);panel.addView(serverLogScroll,new LinearLayout.LayoutParams(-1,dp(260)));
        trackServerLogTouches(serverLogScroll,serverLogBody);
    }
    private void buildPage() throws Exception {
        ServerRuntime sr=ServerRuntime.get(this);serverAliveUi=sr.alive();boolean idle=!sr.alive()&&!ClientRuntime.get(this).alive();
        JSONObject workspace=sr.buildState(),selected=object(workspace,"selected_source"),build=object(workspace,"build"),staged=object(workspace,"staged"),deployed=object(workspace,"deployed"),sql=object(workspace,"database_import");
        JSONObject builtSource=object(build,"selected_source"),stagedSource=object(staged,"selected_source");
        String buildId=build.optString("build_id"),stagedBuildId=staged.optString("build_id"),stagedId=staged.optString("generation");
        boolean built="passed".equals(build.optString("state"))&&!buildId.isEmpty()&&"jemalloc".equals(build.optString("allocator"));
        boolean hasStaged=!stagedId.isEmpty()&&!stagedBuildId.isEmpty();
        File report=new File(storage(this),"server/current/source-report.txt");
        LinearLayout summary=featuredCard("Build workspace");
        summary.addView(label("Fetch → Build with jemalloc → Prepare database → Check staging → Deploy",15,TEXT));
        summary.addView(label("Each step names its source or build. Fetching, compiling and staging keep your current server and database in place. Stop the client and server before changing this workspace.",13,MUTED));
        if(deployed.has("generation")){
            summary.addView(label("CURRENT DEPLOYMENT\nGeneration: "+deployed.optString("generation")+"\nBuild: "+deployed.optString("build_id","legacy deployment")+"\nDatabase: "+deployed.optString("database","unknown")+"\nExpected client: "+deployed.optString("expected_client","unknown")+"\nLast saved count: "+deployed.optInt("accounts")+" accounts · "+deployed.optInt("characters")+" characters",13,TEXT));
            JSONObject activeSource=object(deployed,"selected_source");if(activeSource.length()>0)summary.addView(label(sourceIdentity(activeSource),12,MUTED));
        }else summary.addView(label("Current deployment: none",14,MUTED));
        LinearLayout live=featuredCard("Live build progress");serverStatus=label(sr.status,14,ACCENT);live.addView(serverStatus);addLiveServerLog(live);
        LinearLayout tools=featuredCard("Server build tools");
        button(tools,sr.toolsCurrent()?"Server runtime ready":sr.installed()?"Update server runtime and build tools":"Install server runtime and build tools",()->run("Installing server runtime",(ctx,p)->ServerRuntime.get(ctx).install(p))).setEnabled(idle&&!sr.toolsCurrent());
        if(!sr.toolsCurrent())tools.addView(label("Install or update the ARM64 compiler, MariaDB and jemalloc before building. Existing source and deployed databases are retained.",13,MUTED));
        button(tools,sr.compilerCacheInstalled()?"Compiler cache installed":"Install compiler cache",()->run("Installing compiler cache",(ctx,p)->ServerRuntime.get(ctx).perform("install-build-cache",false,p))).setEnabled(idle&&sr.toolsCurrent()&&!sr.compilerCacheInstalled());
        tools.addView(label("Reuses compiled objects across source builds, with compiler, header and option checks. Limited to 2 GiB; every result still passes binary and jemalloc validation.",12,MUTED));
        LinearLayout source=featuredCard("1 · Fetch or import source");
        source.addView(label("FETCHED SOURCE\n"+sourceIdentity(selected),13,TEXT));
        EditText repo=loginField(source,"GitHub repository",getSharedPreferences("server",0).getString("repository","https://github.com/LandSandBoat/server"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);repo.setContentDescription("Source GitHub repository");
        EditText ref=loginField(source,"Branch, tag or exact commit",getSharedPreferences("server",0).getString("ref","base"),android.text.InputType.TYPE_CLASS_TEXT);ref.setContentDescription("Source branch tag or commit");
        source.addView(label("Editing these fields does not change the fetched source until you tap Fetch.",12,MUTED));
        button(source,"Fetch this source",()->{String repository=repo.getText().toString().trim(),revision=ref.getText().toString().trim();getSharedPreferences("server",0).edit().putString("repository",repository).putString("ref",revision).apply();run("Fetching source snapshot",(ctx,p)->SourceImport.download(new File(storage(ctx),"server"),repository,revision,p));}).setEnabled(idle);
        button(source,"Import source / existing server ZIP",()->pick("source")).setEnabled(idle);
        if(report.exists())button(source,"View fetched source report",()->{try{showText("Fetched source report",FilesEx.read(report,8192));}catch(Exception e){error(e);}},true);
        LinearLayout compile=featuredCard("2 · Build this source with jemalloc");
        compile.addView(label("Next build uses the fetched source shown in step 1. jemalloc is required and checked for every required server program, including the profile service in newer source.",13,MUTED));
        compile.addView(label("Build workers",14,TEXT));
        Spinner workers=new Spinner(this);String[] workerLabels=new String[16];for(int i=0;i<workerLabels.length;i++)workerLabels[i]="j"+(i+1)+" · "+(i+1)+" worker"+(i==0?"":"s");
        workers.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,workerLabels));workers.setContentDescription("Build worker count");
        workers.setSelection(Math.max(1,Math.min(16,getSharedPreferences("server",0).getInt("jobs",2)))-1);compile.addView(workers);
        workers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View view,int position,long id){getSharedPreferences("server",0).edit().putInt("jobs",position+1).apply();}public void onNothingSelected(AdapterView<?> parent){}});
        compile.addView(label("Default j2. More workers use more memory and may heat the device; j1 uses the least memory.",12,MUTED));
        button(compile,"Build fetched source with jemalloc",()->{getSharedPreferences("server",0).edit().putInt("jobs",workers.getSelectedItemPosition()+1).apply();run("Building fetched source with jemalloc",(ctx,p)->ServerRuntime.get(ctx).performBuild("build-source","","","",p));}).setEnabled(sr.toolsCurrent()&&report.exists()&&idle);
        if(!built&&buildId.isEmpty()&&"passed".equals(build.optString("state"))&&"jemalloc".equals(build.optString("allocator"))){
            compile.addView(label("A successful build from the previous app version is available. Verify its saved binaries and assign a build ID to reuse it without compiling again.",13,ACCENT));
            button(compile,"Verify and reuse previous successful build",()->run("Verifying previous jemalloc build",(ctx,p)->ServerRuntime.get(ctx).performBuild("adopt-build","","","",p))).setEnabled(sr.toolsCurrent()&&idle);
        }
        if(built){
            compile.addView(label("COMPLETED BUILD\nBuild ID: "+buildId+"\nAllocator: jemalloc · Workers: j"+build.optInt("jobs",2)+"\nCompleted: "+receiptTime(build,"finished_at")+"\n"+sourceIdentity(builtSource),13,TEXT));
            if(!sameSource(selected,builtSource))compile.addView(label("This completed build is from a different or unverified source snapshot. Step 3 stages the build ID above, not the newly fetched source.",14,ACCENT));
        }else compile.addView(label(buildId.isEmpty()?"No identified successful build yet. Build the fetched source or verify an available previous build before preparing its database.":"Latest build status: "+build.optString("state","unknown")+". Finish a successful jemalloc build before staging.",13,MUTED));
        LinearLayout database=featuredCard("3 · Prepare database and stage build");
        database.addView(label(built?"Build to stage: "+buildId+"\n"+sourceIdentity(builtSource):"Build to stage: none",13,TEXT));
        button(database,"Import database backup (.sql / .sql.gz)",()->pick("server-sql")).setEnabled(idle);
        database.addView(label(sql.optBoolean("available")?"Imported SQL: "+sql.optString("label","selected backup")+" · "+sql.optLong("bytes")/1048576+" MiB\nSHA-256: "+sql.optString("sha256","not recorded"):"Imported SQL: none. A server source ZIP does not include live accounts or characters.",13,MUTED));
        EditText databaseName=loginField(database,"Database name for fresh / imported SQL",getSharedPreferences("server",0).getString("database","xidb"),android.text.InputType.TYPE_CLASS_TEXT);databaseName.setContentDescription("Server database name");
        if(deployed.has("generation"))database.addView(label("Keep current player data uses the active database name: "+deployed.optString("database","unknown"),12,MUTED));
        CheckBox local=new CheckBox(this);local.setText("Configure all zones for one local map process");local.setTextColor(TEXT);local.setChecked(getSharedPreferences("server",0).getBoolean("local_zones",true));database.addView(local);
        Runnable saveDatabase=()->{String name=databaseName.getText().toString().trim();if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,47}"))throw new IllegalArgumentException("Enter a database name using letters, digits and underscores");getSharedPreferences("server",0).edit().putString("database",name).putBoolean("local_zones",local.isChecked()).apply();};
        final String[] modes={"copy-current","import","fresh"};
        Spinner mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Keep current player data · copy and update","Replace player data · use imported SQL","Replace player data · create fresh database"}));mode.setContentDescription("Staged database source");
        String preferred=getSharedPreferences("server",0).getString("database_mode",deployed.has("generation")?"copy-current":sql.optBoolean("available")?"import":"fresh");int modeIndex=Arrays.asList(modes).indexOf(preferred);mode.setSelection(modeIndex<0?0:modeIndex);database.addView(mode);
        TextView modeExplanation=label("",13,ACCENT);database.addView(modeExplanation);
        Button prepare=button(database,"Prepare database and stage this build",()->{
            saveDatabase.run();final String choice=modes[mode.getSelectedItemPosition()];
            String message="Build ID: "+buildId+"\n"+sourceIdentity(builtSource)+"\n\n"+databaseModeLabel(choice)+".\n\n"+(choice.equals("copy-current")?"Copies your current accounts and characters, then applies this build's database updates.":choice.equals("import")?"When deployed, accounts and characters become those in the imported backup.":"When deployed, current player data is replaced by the data supplied by this build's SQL. Existing progress is not copied.")+"\n\nThis step prepares a separate server/database pair. Deploy in step 5 after checking it.";
            confirm("Prepare this build and database",message,()->run("Preparing database and staging build",(ctx,p)->ServerRuntime.get(ctx).performBuild("stage-build",buildId,"",choice,p)));
        });
        Runnable updateMode=()->{
            String choice=modes[mode.getSelectedItemPosition()];getSharedPreferences("server",0).edit().putString("database_mode",choice).apply();
            boolean available=choice.equals("fresh")||choice.equals("import")&&sql.optBoolean("available")||choice.equals("copy-current")&&deployed.has("generation");
            modeExplanation.setText(!available?(choice.equals("import")?"Import an SQL backup first.":"No current deployment to copy. Choose imported SQL or a fresh database."):choice.equals("copy-current")?"Preserves player data by updating a separate copy. Downloads any missing map files before staging.":choice.equals("import")?"Deployment will replace current player data with the imported backup.":"Deployment will replace current player data with this build's SQL data. Existing progress is not copied.");
            prepare.setEnabled(built&&available&&sr.toolsCurrent()&&idle&&!WorkService.busy);
        };
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View view,int position,long id){updateMode.run();}public void onNothingSelected(AdapterView<?> parent){}});updateMode.run();
        LinearLayout check=featuredCard("4 · Check staged build and database");
        String stagedDescription=hasStaged?"STAGED PAIR\nBuild ID: "+stagedBuildId+"\nGeneration: "+stagedId+"\n"+sourceIdentity(stagedSource)+"\nDatabase: "+staged.optString("database","unknown")+"\nDatabase source: "+databaseModeLabel(staged.optString("database_mode"))+"\n"+staged.optInt("accounts")+" accounts · "+staged.optInt("characters")+" characters\nExpected client: "+staged.optString("expected_client","unknown")+"\nPrepared: "+receiptTime(staged,"created_at"):"No staged build/database pair yet. Complete step 3 first.";
        check.addView(label(stagedDescription,13,TEXT));
        if(hasStaged){
            JSONObject meshes=object(staged,"meshes");
            check.addView(label("Staged meshes: navmeshes · "+(meshes.optBoolean("navmeshes")?"present":"missing or not recorded")+"\nximeshes · "+(meshes.optBoolean("ximeshes")?"present":"missing or not recorded")+"\nStaging checks required map file headers. World navigation still needs a gameplay test.",13,MUTED));
            JSONObject pair=object(staged,"client_pair");String expected=staged.optString("expected_client"),recorded=pair.optString("client_version");
            boolean versionsKnown=expected.matches("[0-9]{8}_[0-9]+")&&recorded.matches("[0-9]{8}_[0-9]+");
            check.addView(label("Client version recorded when staged: "+(recorded.isEmpty()?"unknown":recorded)+"\n"+(versionsKnown?(expected.equals(recorded)?"Recorded client version matches this build's expected version.":"Version mismatch: this build expects "+expected+". Update or choose a matching client before connecting."):"Client/server version match is unverified.")+"\nA matching version still needs a client and loader connection test.",13,versionsKnown&&!expected.equals(recorded)?ACCENT:MUTED));
        }
        if(!staged.optString("staged_from_generation").isEmpty())check.addView(label("Based on deployed generation: "+staged.optString("staged_from_generation"),12,MUTED));
        if(hasStaged&&!stagedBuildId.equals(buildId))check.addView(label("The staged pair uses an earlier build. Check and Deploy below use the staged build ID shown here.",14,ACCENT));
        check.addView(label(hasStaged?"Staging check: "+staged.optString("state","not checked"):"Staging check: not run",14,ACCENT));
        if(staged.has("checked_at"))check.addView(label("Last checked: "+receiptTime(staged,"checked_at"),12,MUTED));
        if(!staged.optString("check_error").isEmpty())check.addView(label("Check needs attention: "+staged.optString("check_error"),13,ACCENT));
        if(staged.optBoolean("stale_database"))check.addView(label("Current player data changed after staging. Prepare a new database copy before deployment.",14,ACCENT));
        button(check,"Check staged build and database",()->run("Checking staged server and database",(ctx,p)->ServerRuntime.get(ctx).performBuild("check-staged",stagedBuildId,stagedId,"",p))).setEnabled(hasStaged&&sr.toolsCurrent()&&idle);
        check.addView(label("Checks the staged files, required map assets, all required binaries and jemalloc, database contents, and saved source identity. Confirm the expected client version shown above matches your client and loader before connecting.",13,MUTED));
        LinearLayout deploy=featuredCard("5 · Deploy the checked pair");
        deploy.addView(label(hasStaged?"Deploy build: "+stagedBuildId+"\nDatabase: "+staged.optString("database","unknown")+"\nDatabase generation: "+stagedId+"\n"+databaseModeLabel(staged.optString("database_mode")):"Nothing is staged for deployment.",13,TEXT));
        button(deploy,"Deploy checked build and database",()->confirm("Replace current server and database",stagedDescription+"\n\nThis exact staged pair will replace the current server and database. Player data will become the staged counts shown above. The current pair is retained for rollback. Deployment checks this pair again and never rebuilds from newly fetched source.",()->run("Deploying checked server and database",(ctx,p)->ServerRuntime.get(ctx).performBuild("deploy-staged",stagedBuildId,stagedId,"",p)))).setEnabled(hasStaged&&"checked".equals(staged.optString("state"))&&!staged.optBoolean("stale_database")&&sr.toolsCurrent()&&idle);
        deploy.addView(label("After deployment, start the managed server from the Server tab. Match its expected client version before connecting.",13,MUTED));
        LinearLayout existing=card("Advanced: imported compiled server");
        existing.addView(label("For an existing server ZIP that already contains matching Linux ARM64 binaries. This path uses the fetched/imported snapshot in step 1 and the imported SQL backup, not the completed build in step 2.",13,MUTED));
        button(existing,"Deploy imported binaries with imported SQL",()->{saveDatabase.run();confirm("Deploy imported binaries and SQL",sourceIdentity(selected)+"\n\nUses binaries inside this selected snapshot and the imported SQL backup. Current accounts and characters will be replaced by those in the backup; the current server/database pair is retained for rollback.",()->run("Deploying imported server and SQL",(ctx,p)->ServerRuntime.get(ctx).perform("deploy",false,p)));}).setEnabled(sr.toolsCurrent()&&report.exists()&&sql.optBoolean("available")&&idle);
        button(existing,"Export Termux packaging helper",()->create("server-helper","export-existing-lsb.py"));
        existing.addView(label("Run inside your existing server's Linux distro: python3 export-existing-lsb.py /path/to/server. It creates a server ZIP and SQL.gz.",13,MUTED));
    }
    private void diagnosticsPage() throws IOException {
        LinearLayout latest=featuredCard("Latest operation");latest.addView(label(OperationProgress.current().summary(),14,TEXT));button(latest,"Live progress details",this::showProgressDetails,true);
        latest.addView(label("Older failures belong to earlier operations. A clean client exit is shown as completed; it does not by itself verify world entry.",13,MUTED));
        button(latest,"Advanced settings and repairs",()->navigate("Advanced",""),true);
        supportTile();
        LinearLayout live=featuredCard("Live server log");
        addLiveServerLog(live);
        LinearLayout d = card("Support and validation");
        d.addView(label("Support ZIPs contain the reference profile, key-file hashes, import summary, saved server/region, and app operation/probe logs. They exclude game payloads, Wine registry hives, and account passwords.", 14, MUTED));
        button(d, "View client inventory", () -> { try { showText("Client inventory", store(this).inventory()); } catch (Exception e) { error(e); } });
        button(d, "View repair script", () -> { try { File f = new File(getFilesDir(), "repair-preview.txt"); showText("Repair recipe · not yet executed", f.exists() ? FilesEx.read(f, 32768) : "Use Client → Validate client and preview repair script first."); } catch (Exception e) { error(e); } });
        button(d, "View historical operation log", () -> { try { File f = new File(getFilesDir(), "operations.log"); showText("Historical operations · includes earlier failures", f.exists() ? FilesEx.read(f, 262144) : "No operations yet"); } catch (Exception e) { error(e); } });
        LinearLayout next = card("Runtime status");
        next.addView(label("Client files: managed import available\nRegistry/COM repair: in-app staged initialization\nWindows runtime: Box64 fallback and verified FEX v3 world play\nDisplay, D3D8 and audio: open-probe checks in Runtime tab\nFFXI registration/COM: staged preparation on Client tab\nLogin: prepared-client launch on Client tab\nController: native joystick mapping\nServer: isolated source/database deployment\n\nA successful import means the file layout and selected PE headers passed checks. It does not mean the client has launched.", 14, TEXT));
    }
    private void pick(String kind) { pending = kind; Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"); startActivityForResult(i, PICK); }
    private void create(String kind, String name) { pending = kind; Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(kind.equals("profile") ? "application/json" : kind.equals("server-helper")?"text/x-python":kind.equals("server-db")?"application/gzip":"application/zip").putExtra(Intent.EXTRA_TITLE, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i, CREATE); }
    @Override protected void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);
        if ((request != PICK && request != CREATE) || response != RESULT_OK || data == null || data.getData() == null) { pending = ""; return; }
        final Uri uri = data.getData(); final String kind = pending; pending = ""; final boolean preserveFiles = preserveOnImport;
        final int grant = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        final int existingGrant = ExportedBackups.persistedFlags(this, uri);
        try { getContentResolver().takePersistableUriPermission(uri, grant); } catch (SecurityException ignored) { }
        final Context exportContext=getApplicationContext();
        run(request == PICK ? "Reading selected file" : "Writing export", new WorkService.Job() {
            private boolean keepGrant,released;
            private synchronized void release(Context ctx) {
                if(released)return;released=true;
                int flags=grant&~existingGrant;if(!keepGrant&&flags!=0)try{ctx.getContentResolver().releasePersistableUriPermission(uri,flags);}catch(SecurityException ignored){}
            }
            @Override public void close(){release(exportContext);}
            @Override public String run(Context ctx,SafeZip.Progress p)throws Exception {
            try {
            if (request == PICK) {
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Cannot open the selected document");
                    switch (kind) {
                        case "client": store(ctx).importClient(in, false, preserveFiles, p); return store(ctx).hasPendingImport() ? "Extraction complete. Choose the PlayOnline version on the Client tab to finish import." : "Client imported and validated. The previous copy, if any, is retained.";
                        case "restore": {String result=SessionBackup.restore(ctx,in,p);SetupGuide.restored(ctx);return result+" Open Play to continue with the restored setup.";}
                        case "restore-legacy": store(ctx).importClient(in, true, false, p); return store(ctx).hasPendingImport() ? "Backup extracted. Choose the PlayOnline version on the Client tab to finish restore." : "Legacy client backup restored and validated.";
                        case "prerequisite": return ClientRuntime.get(ctx).importPrerequisite(in);
                        case "loader": {
                            ClientRuntime runtime=ClientRuntime.get(ctx);
                            if(runtime.alive())throw new IOException("Stop the client and runtime before importing xiloader");
                            JSONObject prepared=runtime.preparationState();
                            if(prepared.has("current")&&prepared.has("candidate"))throw new IOException("Finish or discard the staged client preparation or update before importing xiloader");
                            runtime.importLoader(in,p);
                            JSONObject selection=runtime.loaderSelectionState();
                            if(selection.optBoolean("prepared_available"))return runtime.applyImportedLoader(selection.getString("imported_sha256"),p);
                            return "32-bit xiloader imported. Prepare the client before testing login.";
                        }
                        case "server-sql": return ServerRuntime.get(ctx).importDatabase(in,p);
                        case "source": if(ServerRuntime.get(ctx).alive())throw new IOException("Stop the managed server before importing source"); return SourceImport.stage(new File(storage(ctx), "server"), in, "User-selected ZIP (revision unverified)", p);
                        default: throw new IOException("File operation was lost; please select it again");
                    }
                }
            }
            JSONObject workingCombination=kind.equals("working-backup")?InstallationSummary.snapshot(ctx):null;
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot write to the selected destination");
                switch (kind) {
                    case "server-helper": try(InputStream helper=ctx.getAssets().open("server/export_existing.py")){byte[] b=new byte[8192];int n;while((n=helper.read(b))!=-1)out.write(b,0,n);} break;
                    case "server-db": ServerRuntime.get(ctx).exportDatabase(out,p); break;
                    case "backup": SessionBackup.export(ctx,out,p); break;
                    case "working-backup": SessionBackup.export(ctx,out,p,workingCombination);break;
                    case "prepared": case "launcher": store(ctx).exportPrepared(out, profile(ctx), windowsLauncher(ctx), kind.equals("prepared"), p); break;
                    case "profile": out.write(profile(ctx).getBytes(StandardCharsets.UTF_8)); break;
                    case "support": support(ctx, out); break;
                    default: throw new IOException("Export operation was lost; please select it again");
                }
            } catch (Exception e) { try { DocumentsContract.deleteDocument(ctx.getContentResolver(), uri); } catch (Exception ignored) { } throw e; }
            String receiptWarning="";
            if(ExportedBackups.trackedKind(kind)){
                keepGrant=true; // Only after the completed stream has closed successfully.
                try{ExportedBackups.record(ctx,uri,kind);}
                catch(Exception error){receiptWarning=" The export is saved, but its storage-browser receipt could not be recorded. Use Locate existing backup in Backups and storage.";}
            }
            if(workingCombination!=null){
                try{InstallationSummary.recordSaved(ctx,workingCombination,uri.toString());}
                catch(Exception error){return "Working-combination backup saved, but its local summary could not be recorded. Keep the selected ZIP for recovery.";}
                return "Working combination saved. Find the selected ZIP under Backups and storage → Exports. Restore it to recover the client, loader, server, database and settings together."+receiptWarning;
            }
            return "Export completed: " + kind + "."+receiptWarning;
            } finally { release(ctx); }
            }
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
    private void run(String title, WorkService.Job job) { if(ClientRuntime.get(this).alive()){try{job.close();}catch(Exception ignored){}toast("Stop the runtime before file operations or exports.");return;} if (!WorkService.submit(getApplicationContext(), title, job)) toast("An operation is already running or could not start."); draw(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private void error(Exception e) { new AlertDialog.Builder(this).setTitle("Unable to continue").setMessage(e.getMessage()).setPositiveButton("OK", null).show(); }
    private void confirm(String title, String message, Runnable action) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Continue", (d, w) -> action.run()).show(); }
    private void showText(String title, String text) { TextView view = label(text, 13, TEXT); view.setTypeface(Typeface.MONOSPACE); view.setPadding(dp(16), dp(8), dp(16), dp(8)); ScrollView scroll = new ScrollView(this); scroll.addView(view); new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show(); }
    private void showLiveServerLog(){
        if(serverLogDialog!=null)serverLogDialog.dismiss();
        serverLogDialogBody=label("Reading server output…",12,TEXT);serverLogDialogBody.setTypeface(Typeface.MONOSPACE);serverLogDialogBody.setPadding(dp(16),dp(8),dp(16),dp(8));
        serverLogDialogScroll=new ScrollView(this);serverLogDialogScroll.addView(serverLogDialogBody);
        trackServerLogTouches(serverLogDialogScroll,serverLogDialogBody);
        serverLogDialog=new AlertDialog.Builder(this).setTitle("Live server operation log").setView(serverLogDialogScroll).setPositiveButton("Close",null).create();
        serverLogDialog.setOnDismissListener(dialog->{serverLogDialog=null;serverLogDialogBody=null;serverLogDialogScroll=null;});serverLogDialog.show();
        logReadAfter=0;renderServerLogs();requestServerLogs();
    }
    private void renderServerLogs(){
        ServerRuntime runtime=ServerRuntime.get(this);
        ServerRuntime.LogSnapshot snapshot=serverLogSnapshot;
        if(snapshot!=null&&snapshot.epoch!=runtime.logEpoch())snapshot=null;
        String latest=snapshot!=null&&snapshot.running&&runtime.hasLogOperation()&&(WorkService.busy||OperationProgress.server.running)?snapshot.latest:"";
        if(latestServerOutput!=null){String line=!latest.isEmpty()?"Latest server output: "+latest:OperationProgress.current().running?"Latest update: "+OperationProgress.current().step:"";latestServerOutput.setVisibility(line.isEmpty()?View.GONE:View.VISIBLE);latestServerOutput.setText(line);}
        if(progressDetails!=null)progressDetails.setText(OperationProgress.current().details()+(latest.isEmpty()?"":"\n\nLatest server output\n"+latest));
        String text=snapshot==null?"Reading server output…":snapshot.text;
        updateServerLogView(serverLogBody,serverLogScroll,text);updateServerLogView(serverLogDialogBody,serverLogDialogScroll,text);
    }
    private void updateServerLogView(TextView view,ScrollView scroll,String text){
        if(view==null||text.contentEquals(view.getText()))return;
        boolean follow=!scroll.canScrollVertically(1);final int beforeY=scroll.getScrollY();
        final long gesture=logScrollGeneration,lifecycle=logViewGeneration;view.setText(text);
        if(follow)scroll.post(()->{
            if(resumed&&lifecycle==logViewGeneration&&gesture==logScrollGeneration&&beforeY==scroll.getScrollY()&&(scroll==serverLogScroll||scroll==serverLogDialogScroll))
                // Scroll without moving keyboard/selection focus into the log.
                scroll.scrollTo(0,Math.max(0,view.getBottom()+scroll.getPaddingBottom()-scroll.getHeight()));
        });
    }
    private void trackServerLogTouches(ScrollView scroll,TextView body){
        // A gesture over selectable text also belongs to this inner scroll area.
        View.OnTouchListener listener=(view,event)->{
            logScrollGeneration++;
            ViewParent parent=scroll.getParent();if(parent!=null)parent.requestDisallowInterceptTouchEvent(event.getActionMasked()!=MotionEvent.ACTION_UP&&event.getActionMasked()!=MotionEvent.ACTION_CANCEL);
            return false;
        };
        scroll.setOnTouchListener(listener);body.setOnTouchListener(listener);
    }
    private void requestServerLogs(){
        if(!resumed||SessionBackup.active||!SessionBackup.recoveryError.isEmpty()||serverLogReadPending||SystemClock.uptimeMillis()<logReadAfter)return;
        ServerRuntime runtime=ServerRuntime.get(this);
        if(!(runtime.hasLogOperation()&&(WorkService.busy||OperationProgress.server.running))&&serverLogBody==null&&serverLogDialogBody==null)return;
        if(serverLogReader==null)serverLogReader=Executors.newSingleThreadExecutor();
        serverLogReadPending=true;logReadAfter=SystemClock.uptimeMillis()+1000;final long viewGeneration=logViewGeneration;
        serverLogReader.execute(()->{
            ServerRuntime.LogSnapshot snapshot=runtime.liveLog();
            handler.post(()->{serverLogReadPending=false;if(resumed&&viewGeneration==logViewGeneration&&snapshot.epoch==runtime.logEpoch()){serverLogSnapshot=snapshot;renderServerLogs();}});
        });
    }
}
