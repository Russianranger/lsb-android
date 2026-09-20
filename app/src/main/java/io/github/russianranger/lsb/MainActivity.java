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
    private static final int PICK = 20, CREATE = 21;
    private static final int BG = Color.rgb(12, 20, 31), CARD = Color.rgb(24, 36, 49), TEXT = Color.rgb(232, 239, 247), MUTED = Color.rgb(163, 182, 198), ACCENT = Color.rgb(106, 207, 193);
    private String tab = "Client", pending = "";
    private LinearLayout content;
    private TextView operation, runtimeStatus;
    private ProgressBar progress;
    private Button cancel;
    private EditText host;
    private EditText loginPassword;
    private boolean showPreparation;
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
    @Override protected void onResume() { super.onResume(); if(tab.equals("Runtime")||tab.equals("Client"))draw(); handler.post(poll); }
    @Override protected void onPause() { if(loginPassword!=null)loginPassword.setText("");handler.removeCallbacks(poll); super.onPause(); }
    @Override public void onSaveInstanceState(Bundle out) { out.putString("tab", tab); out.putString("pending", pending); out.putBoolean("preserve", preserveOnImport); super.onSaveInstanceState(out); }
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
    private LinearLayout card(String title) {
        LinearLayout v = column(); v.setPadding(dp(16), dp(12), dp(16), dp(14)); v.setBackground(background(CARD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(8), 0, dp(8)); content.addView(v, lp);
        TextView heading = label(title, 20, TEXT); heading.setTypeface(null, Typeface.BOLD); v.addView(heading); return v;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(TEXT); b.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(4), 0, dp(4)); parent.addView(b, lp);
        b.setOnClickListener(v -> { if (WorkService.busy) { toast("Wait for the current operation, or cancel it."); return; } try { action.run(); } catch (Exception e) { error(e); } }); return b;
    }
    private void draw() {
        runtimeStatus=null;if(loginPassword!=null)loginPassword.setText("");loginPassword=null;
        LinearLayout page = column(); page.setBackgroundColor(BG); page.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView heading = label("LSB Android", 26, TEXT); heading.setTypeface(null, Typeface.BOLD); page.addView(heading);
        page.addView(label("FFXI client runtime preview · 0.4.0", 13, ACCENT));
        LinearLayout nav = new LinearLayout(this);
        for (String name : new String[]{"Client", "Runtime", "Profile", "Server", "Diagnostics"}) {
            Button b = new Button(this); b.setText(name); b.setAllCaps(false); b.setTextSize(12); b.setPadding(0, 0, 0, 0); b.setTextColor(name.equals(tab) ? ACCENT : TEXT); b.setMinHeight(dp(48));
            nav.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            b.setOnClickListener(v -> { tab = name; draw(); });
        }
        page.addView(nav);
        operation = label(WorkService.message + (WorkService.result.isEmpty() ? "" : "\n" + WorkService.result), 13, MUTED); operation.setMaxLines(5); page.addView(operation);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE); page.addView(progress);
        cancel = new Button(this); cancel.setText("Cancel operation"); cancel.setAllCaps(false); cancel.setVisibility(WorkService.busy ? View.VISIBLE : View.GONE); cancel.setOnClickListener(v -> { WorkService.cancel(); toast("Cancelling; waiting for the current file operation to stop."); }); page.addView(cancel);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); content = column(); scroll.addView(content); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        try { switch (tab) { case "Runtime": runtimePage(); break; case "Profile": profilePage(); break; case "Server": serverPage(); break; case "Diagnostics": diagnosticsPage(); break; default: clientPage(); } }
        catch (Exception e) { content.addView(label("Cannot read app state: " + e.getMessage(), 16, TEXT)); }
    }
    private void runtimePage() throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);
        LinearLayout panel=card("Windows runtime · verified foundation");
        panel.addView(label("A separate environment for testing Windows, Direct3D 8, sound and input. Your imported FFXI files are not mounted or modified by these checks.",16,TEXT));
        runtimeStatus=label(rt.status,15,ACCENT);panel.addView(runtimeStatus);
        panel.addView(label("Wine 10 / Box64 0.4.4. Use the Client tab to launch your prepared FFXI installation.",14,MUTED));
        button(panel,"Install runtime (338 MiB download)",()->run("Installing Windows runtime",(ctx,p)->ClientRuntime.get(ctx).install(p))).setEnabled(!rt.alive()&&!rt.installed());
        panel.addView(label("Graphics for this test",14,MUTED));
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
        button(panel,"View runtime details",()->{try{showText("Runtime details",rt.state().toString(2));}catch(Exception e){error(e);}});
        button(panel,"Create fresh Windows prefix",()->confirm("Fresh Windows environment","The current prefix will be preserved in a separate backup. Your imported client and session backups stay in place.",()->run("Preserving Windows prefix",(ctx,p)->ClientRuntime.get(ctx).freshPrefix()))).setEnabled(rt.installed()&&!rt.alive());
        panel.addView(label("Current session backups contain your imported client, not this experimental runtime. A fresh-prefix action preserves its previous folder. Keep at least 3 GiB free for first setup.",14,MUTED));
    }
    private void clientPage() throws Exception {
        ClientStore s = store(this);
        boolean ready=ClientRuntime.get(this).preparationState().has("current");
        if(ready){
            loginCard(s);
            LinearLayout options=column();content.addView(options);
            button(options,showPreparation?"Hide preparation and recovery":"Preparation and recovery",()->{showPreparation=!showPreparation;draw();});
        }
        if(!ready||showPreparation)initializationCard(s);
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
        LinearLayout card=card("Play FINAL FANTASY XI");runtimeStatus=label(rt.status,15,ACCENT);card.addView(runtimeStatus);
        card.addView(label("Start your existing Termux server, then log in with your server account. The prepared client is reused.",15,TEXT));
        EditText server=loginField(card,"Server address",source.config().host,android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText account=loginField(card,"Account","",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText secret=loginField(card,"Password","",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);loginPassword=secret;
        card.addView(label("Account and password are used for this launch only. They are not saved. If login fails, Stop the client and enter them again.",13,MUTED));
        button(card,"Launch FFXI",()->{
            LoginRequest login=null;
            try{
                if(rt.alive())throw new IOException("Stop the current client first");
                login=new LoginRequest(server.getText().toString().trim(),account.getText().toString(),secret.getText().toString());
                LaunchConfig old=source.config();source.saveConfig(new LaunchConfig(login.host,old.region,old.polCore));
                String ticket=RuntimeService.queueLogin(login);login=null;secret.setText("");account.setText("");
                String renderer=getSharedPreferences("runtime",MODE_PRIVATE).getString("renderer","turnip26");
                startForegroundService(new Intent(this,RuntimeService.class).putExtra("operation","launch").putExtra("renderer",renderer).putExtra("audio",true).putExtra("login_ticket",ticket));
                startActivity(new Intent(this,RuntimeActivity.class));
            }catch(Exception e){if(login!=null)login.close();RuntimeService.clearLogin();error(e);}
        }).setEnabled(rt.installed()&&!rt.alive());
        button(card,"Open client display",()->startActivity(new Intent(this,RuntimeActivity.class))).setEnabled(rt.alive());
        button(card,"Stop client",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"))).setEnabled(rt.alive());
        button(card,"Check launcher dependencies",()->startInitialization("check-launcher")).setEnabled(!rt.alive());
        button(card,"View launch results",()->{try{JSONObject current=rt.preparationState().getJSONObject("current");showText("Launch results",current.has("last_launch")?current.getJSONObject("last_launch").toString(2):"No launch check has run yet.");}catch(Exception e){error(e);}});
        if(showPreparation){
            card.addView(label("If launch diagnostics identify a missing dependency, select its official x86 installer. Repair makes a full recovery copy, runs the installer, and activates it only after client and loader checks pass.",14,MUTED));
            button(card,"Select launcher prerequisite (.exe)",()->pick("prerequisite")).setEnabled(!rt.alive());
            button(card,"Repair launcher prerequisites",()->startInitialization("repair-launcher")).setEnabled(!rt.alive()&&state.optBoolean("prerequisite_selected"));
        }
    }
    private void initializationCard(ClientStore source)throws Exception {
        ClientRuntime rt=ClientRuntime.get(this);JSONObject prepared=rt.preparationState();
        boolean candidate=prepared.has("candidate"), copied=candidate&&prepared.getJSONObject("candidate").optBoolean("copy_complete");
        LinearLayout card=card("Prepare PlayOnline and FFXI");
        if(runtimeStatus==null){runtimeStatus=label(rt.status,15,ACCENT);card.addView(runtimeStatus);}
        card.addView(label("Create a separate working copy and Windows environment, register the selected client, then test its PlayOnline and FFXI interfaces. Successful checks activate both copies together. This step does not log in or start the game.",15,TEXT));
        card.addView(label("Free runtime storage: "+rt.home.getUsableSpace()/1073741824L+" GiB. Preparation needs room for another full client and Windows copy; space is checked before copying. This may take several minutes.",14,MUTED));
        if(prepared.has("current"))card.addView(label("A validated preparation is available. A new attempt preserves it until checks pass.",14,ACCENT));
        if(candidate)card.addView(label(copied?"A staged working copy is available. Retry uses its captured region and files without copying the full import again.":"The previous copy was interrupted. Preparation will replace only that incomplete candidate.",14,MUTED));
        if(!rt.installed())card.addView(label("Install the Windows runtime on the Runtime tab first.",14,MUTED));
        button(card,copied?"Retry client initialization":"Prepare imported client",()->{
            try{if(!copied)saveConnection();startInitialization("initialize");}catch(Exception e){error(e);}
        }).setEnabled(source.hasClient()&&!source.hasPendingImport()&&rt.installed()&&!rt.alive());
        button(card,"Open initialization display",()->startActivity(new Intent(this,RuntimeActivity.class))).setEnabled(rt.alive());
        button(card,"Stop initialization",()->startForegroundService(new Intent(this,RuntimeService.class).setAction("stop"))).setEnabled(rt.alive());
        button(card,"View preparation results",()->{try{showText("Prepared client",rt.preparationState().toString(2));}catch(Exception e){error(e);}});
        if(copied){
            card.addView(label("If diagnostics identify a missing dependency, select its official x86 prerequisite installer. It runs interactively in the staged copy, then registration checks run again.",14,MUTED));
            button(card,"Select prerequisite installer (.exe)",()->pick("prerequisite")).setEnabled(!rt.alive());
            button(card,"Run prerequisite and retry checks",()->startInitialization("installer")).setEnabled(!rt.alive()&&prepared.optBoolean("prerequisite_selected"));
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
    private void serverPage() throws IOException {
        LinearLayout source = card("LandSandBoat source");
        source.addView(label("Import source now for the later compiler/launcher milestone. This build does not compile, import a database, or start server processes.", 14, MUTED));
        File report = new File(storage(this), "server/current/source-report.txt"); if (report.exists()) source.addView(label(FilesEx.read(report, 8192), 14, TEXT));
        EditText repo = new EditText(this); repo.setTextColor(TEXT); repo.setSingleLine(true); repo.setText("https://github.com/Russianranger/LSB-server"); source.addView(repo);
        EditText ref = new EditText(this); ref.setTextColor(TEXT); ref.setSingleLine(true); ref.setHint("Branch, tag, or commit"); ref.setText("base"); source.addView(ref);
        button(source, "Download source from GitHub", () -> { final String r = repo.getText().toString().trim(), branch = ref.getText().toString().trim(); run("Resolving source revision", (ctx, p) -> SourceImport.download(new File(storage(ctx), "server"), r, branch, p)); });
        button(source, "Import source ZIP", () -> pick("source"));
        LinearLayout connect = card("Test your existing server");
        connect.addView(label("Probe TCP ports 54231 (authentication), 54230 (login data), and 54001 (view) at the saved Client address. This does not verify authentication, the UDP map port, or world entry.", 14, MUTED));
        button(connect, "Probe saved server address", () -> run("Checking server TCP ports", (ctx, p) -> {
            String address = store(ctx).config().host; StringBuilder reportText = new StringBuilder("TCP reachability only: " + address + "\n");
            for (int port : new int[]{54231, 54230, 54001}) { SafeZip.checkCancelled(); p.update("Checking TCP " + port); try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(address, port), 2500); reportText.append(port).append(": reachable\n"); } catch (IOException e) { reportText.append(port).append(": unavailable (").append(e.getClass().getSimpleName()).append(")\n"); } }
            FilesEx.text(new File(ctx.getFilesDir(), "server-probe.txt"), reportText.toString()); return reportText.toString();
        }));
    }
    private void diagnosticsPage() throws IOException {
        LinearLayout d = card("Support and validation");
        d.addView(label("Support ZIPs contain the reference profile, key-file hashes, import summary, saved server/region, and app operation/probe logs. They exclude game payloads, Wine registry hives, and account passwords.", 14, MUTED));
        button(d, "Export support ZIP", () -> create("support", "lsb-support.zip"));
        button(d, "View client inventory", () -> { try { showText("Client inventory", store(this).inventory()); } catch (Exception e) { error(e); } });
        button(d, "View repair script", () -> { try { File f = new File(getFilesDir(), "repair-preview.txt"); showText("Repair recipe · not yet executed", f.exists() ? FilesEx.read(f, 32768) : "Use Client → Validate client and preview repair script first."); } catch (Exception e) { error(e); } });
        button(d, "View operation log", () -> { try { File f = new File(getFilesDir(), "operations.log"); showText("Operation log", f.exists() ? FilesEx.read(f, 262144) : "No operations yet"); } catch (Exception e) { error(e); } });
        LinearLayout next = card("Runtime status");
        next.addView(label("Client files: managed import available\nRegistry/COM repair: in-app staged initialization\nWindows runtime: experimental Wine 10 / Box64 candidate\nDisplay, D3D8 and audio: open-probe checks in Runtime tab\nFFXI registration/COM: staged preparation on Client tab\nLogin: prepared-client launch on Client tab\nFEX and controller mappings: later milestones\nServer toolchain and database runtime: not bundled\n\nA successful import means the file layout and selected PE headers passed checks. It does not mean the client has launched.", 14, TEXT));
    }
    private void pick(String kind) { pending = kind; Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"); startActivityForResult(i, PICK); }
    private void create(String kind, String name) { pending = kind; Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(kind.equals("profile") ? "application/json" : "application/zip").putExtra(Intent.EXTRA_TITLE, name); startActivityForResult(i, CREATE); }
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
                        case "source": return SourceImport.stage(new File(storage(ctx), "server"), in, "User-selected ZIP (revision unverified)", p);
                        default: throw new IOException("File operation was lost; please select it again");
                    }
                }
            }
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot write to the selected destination");
                switch (kind) {
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
            SafeZip.entry(zip, "runtime-profile.json", profile(ctx)); SafeZip.entry(zip, "inventory.json", s.inventory()); SafeZip.entry(zip, "summary.txt", s.summary()); SafeZip.entry(zip, "session.properties", s.config().properties());
            SafeZip.entry(zip, "playonline-candidates.txt", String.join("\n", s.playOnlineChoices()) + "\n");
            if (s.hasPendingImport()) SafeZip.entry(zip, "pending-playonline.txt", "Waiting for PlayOnline selection\n" + String.join("\n", s.pendingChoices()) + "\n");
            SafeZip.entry(zip, "device.txt", "app=0.4.0\nandroid=" + Build.VERSION.RELEASE + "\nsdk=" + Build.VERSION.SDK_INT + "\nmodel=" + Build.MODEL + "\nabis=" + Arrays.toString(Build.SUPPORTED_ABIS) + "\nfreeBytes=" + storage(ctx).getUsableSpace() + "\ninAppRuntime=prepared_client_launch\n");
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
