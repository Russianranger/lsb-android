package io.github.russianranger.lsb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import io.github.russianranger.lsb.core.SafeZip;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Read-only inventory with deletion limited to the backend's retained-copy allowlist. */
public final class BackupBrowserActivity extends Activity {
    private static final int BG = Color.rgb(12, 21, 32);
    private static final int TEXT = Color.rgb(244, 234, 209);
    private static final int MUTED = Color.rgb(164, 186, 201);
    private static final int ACCENT = Color.rgb(221, 183, 102);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Rows adapter = new Rows();
    private final List<StorageBackups.Item> items = new ArrayList<>();
    private AppStorage.Report storage;
    private List<ExportedBackups.Entry> exports = Collections.emptyList();
    private String mode = "files";
    private static final int LOCATE = 91;
    private List<StorageBackups.Node> nodes = Collections.emptyList();
    private Future<?> scan;
    private volatile boolean resumed;
    private volatile int scanGeneration;
    private boolean scanning;
    private boolean showingWork;
    private boolean lastRuntimeActive;
    private long workGeneration;
    private long scanWorkGeneration;
    private String itemId = "";
    private String relativePath = "";
    private String itemLabel = "";
    private String idleStatus = "Loading retained copies…";
    private String scanStatus = "";
    private String completedOperation = "";
    private TextView location;
    private TextView status;
    private TextView totals;
    private TextView note;
    private Button locate;
    private ProgressBar progress;
    private Button cancel;
    private Button refresh;
    private Button up;
    private AlertDialog dialog;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            boolean active = runtimesActive();
            if (lastRuntimeActive != active) { lastRuntimeActive = active; adapter.notifyDataSetChanged(); updateStatus(); }
            if (maintenanceBlocked()) {
                if (scanning) stopScan();
                showingWork = true;
                updateStatus();
            } else if (showingWork || workGeneration != WorkService.generation) {
                showingWork = false;
                workGeneration = WorkService.generation;
                completedOperation = WorkService.message + (WorkService.result.isEmpty() ? "" : ": " + WorkService.result);
                load();
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            itemId = state.getString("item", "");
            relativePath = state.getString("path", "");
            itemLabel = state.getString("label", "Retained copy");
            showingWork = state.getBoolean("work", false);
            completedOperation = state.getString("completed", "");
            mode = state.getString("mode", "files");
        }
        LinearLayout page = column();
        page.setBackgroundColor(BG);
        page.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView title = label("Backups and storage" + (getPackageName().endsWith(".restoretest") ? " · Restore Test" : ""), 22, ACCENT);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        page.addView(title);
        LinearLayout actions = new LinearLayout(this);
        up = button(actions, "Back to launcher", this::goUp, true);
        refresh = button(actions, "Refresh", this::load, true);
        page.addView(actions);
        LinearLayout sections = new LinearLayout(this);
        button(sections, "App files", () -> section("files"), true);
        button(sections, "Copies", () -> section("copies"), true);
        button(sections, "Exports", () -> section("exports"), true);
        page.addView(sections);
        totals = label("Measuring all app data…", 13, TEXT);
        totals.setMaxLines(2); totals.setEllipsize(TextUtils.TruncateAt.END);
        totals.setOnClickListener(v -> { if(storage!=null)dialog=new AlertDialog.Builder(this).setTitle("Complete app storage").setMessage(storageDetails()).setPositiveButton("Close",null).show(); });
        page.addView(totals);
        locate = button(page, "Locate existing backup", this::locateExport, false);
        location = label("", 14, TEXT);
        location.setMaxLines(2);
        location.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        page.addView(location);
        status = label("", 13, MUTED);
        status.setMaxLines(4);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> {
            if (!scanning && !WorkService.busy) dialog = new AlertDialog.Builder(this).setTitle("Storage status")
                    .setMessage(status.getText()).setPositiveButton("Close", null).show();
        });
        page.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        page.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        cancel = button(page, "Cancel scan", this::cancelOperation, false);
        ListView list = new ListView(this);
        list.setDividerHeight(dp(6));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openRow(position));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        note = label("", 12, MUTED);
        page.addView(note);
        setContentView(page);
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        workGeneration = WorkService.generation;
        lastRuntimeActive = runtimesActive();
        if (showingWork && !WorkService.busy) completedOperation = WorkService.message + (WorkService.result.isEmpty() ? "" : ": " + WorkService.result);
        load();
        ui.post(tick);
    }

    @Override protected void onPause() {
        resumed = false;
        stopScan();
        ui.removeCallbacksAndMessages(null);
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        super.onPause();
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("item", itemId);
        state.putString("path", relativePath);
        state.putString("label", itemLabel);
        state.putBoolean("work", showingWork);
        state.putString("completed", completedOperation);
        state.putString("mode", mode);
    }

    @Override public void onBackPressed() { goUp(); }

    private static boolean maintenanceBlocked() {
        return WorkService.busy || SessionBackup.active || !SessionBackup.recoveryError.isEmpty();
    }

    private boolean runtimesActive() {
        // Runtime construction creates directories. A complete restore may currently
        // be moving those roots, or deliberately leave them absent until recovery.
        return maintenanceBlocked() || ClientRuntime.get(this).alive() || ServerRuntime.get(this).alive();
    }

    private void goUp() {
        if (itemId.isEmpty()) { finish(); return; }
        if (relativePath.isEmpty()) { itemId = ""; itemLabel = ""; }
        else {
            int slash = relativePath.lastIndexOf('/');
            relativePath = slash < 0 ? "" : relativePath.substring(0, slash);
        }
        nodes = Collections.emptyList();
        adapter.notifyDataSetChanged();
        load();
    }

    private void section(String selected) {
        mode=selected;itemId="";itemLabel="";relativePath="";nodes=Collections.emptyList();adapter.notifyDataSetChanged();load();
    }

    private void stopScan() {
        ++scanGeneration;
        if (scan != null) { scan.cancel(true); scan = null; }
        scanning = false;
    }

    private void load() {
        stopScan();
        if (!resumed) return;
        if (maintenanceBlocked()) { showingWork = true; updateStatus(); return; }
        showingWork = false;
        scanning = true;
        final int expected = scanGeneration;
        scanWorkGeneration = WorkService.generation;
        final Context context = getApplicationContext();
        final String selected = itemId;
        final String path = relativePath;
        final String selectedMode = mode;
        scanStatus = selected.isEmpty() ? "exports".equals(mode)?"Reading export receipts…":"Measuring all app data…" : "Reading folder…";
        updateStatus();
        scan = worker.submit(() -> {
            try {
                final long[] lastProgress = {0};
                SafeZip.Progress updates = message -> {
                        long now = System.currentTimeMillis();
                        if (now - lastProgress[0] < 300) return;
                        lastProgress[0] = now;
                        ui.post(() -> { if (current(expected)) { scanStatus = message; updateStatus(); } });
                };
                if (selected.isEmpty() && selectedMode.equals("exports")) {
                    List<ExportedBackups.Entry> found=ExportedBackups.list(context);
                    ui.post(()->{if(!current(expected))return;exports=found;idleStatus=found.size()+" exported backups · selected external documents";finishScan();});
                } else if (selected.isEmpty()) {
                    AppStorage.Report measured=AppStorage.scan(context,updates);
                    List<StorageBackups.Item> found = selectedMode.equals("copies")?StorageBackups.inventory(context,updates):Collections.emptyList();
                    ui.post(() -> {
                        if (!current(expected)) return;
                        storage=measured;
                        items.clear(); items.addAll(found);
                        long removable = 0; int count = 0;
                        for (StorageBackups.Item item : items) if (item.deletable) { removable += item.bytes; count++; }
                        idleStatus = selectedMode.equals("files")?measured.entries.size()+" app folders/files · tap to browse; read-only"
                                : count == 0 ? "No removable copies found. Current data is protected."
                                : count + (count == 1 ? " retained copy" : " retained copies") + " · " + size(removable) + " in retained files. Current data is protected.";
                        finishScan();
                    });
                } else {
                    List<StorageBackups.Node> found = selectedMode.equals("files")?AppStorage.children(context,selected,path,updates):StorageBackups.children(context, selected, path);
                    ui.post(() -> {
                        if (!current(expected)) return;
                        nodes = found;
                        idleStatus = nodes.isEmpty() ? "This folder is empty." : nodes.size() + " entries · names and sizes only";
                        finishScan();
                    });
                }
            } catch (Exception error) {
                ui.post(() -> {
                    if (!current(expected)) return;
                    if (selected.isEmpty()) {items.clear();exports=Collections.emptyList();if(selectedMode.equals("files"))storage=null;} else nodes = Collections.emptyList();
                    idleStatus = "Could not read storage: " + message(error);
                    finishScan();
                });
            }
        });
    }

    private boolean current(int expected) {
        return resumed && expected == scanGeneration && !maintenanceBlocked()
                && scanWorkGeneration == WorkService.generation;
    }

    private void finishScan() { scanning = false; scan = null; adapter.notifyDataSetChanged(); updateStatus(); }

    private void updateStatus() {
        if (status == null) return;
        boolean busy = WorkService.busy;
        boolean blocked = maintenanceBlocked();
        location.setText(itemId.isEmpty() ? mode.equals("files")?"All app data · sorted by file size":mode.equals("exports")?"Exported backups · chosen destinations":"Retained copies in this installation" : display(itemLabel) + (relativePath.isEmpty() ? "" : " / " + display(relativePath)));
        up.setText(itemId.isEmpty() ? "Back to launcher" : relativePath.isEmpty() ? mode.equals("files")?"All app files":"All copies" : "Parent folder");
        if(storage!=null)totals.setText("App files: "+size(storage.bytes)+(storage.errors==0?"":" · partial scan")+" · tap for full totals\n"+(storage.androidData>=0?"Android app data: "+size(storage.androidData):storage.allocationKnown?"Allocated on disk: "+size(storage.allocated):"Allocated size unavailable"));
        locate.setVisibility(mode.equals("exports")?View.VISIBLE:View.GONE);locate.setEnabled(!blocked&&!scanning);
        note.setText(mode.equals("exports")?"Archives stay in your chosen destination. Locating an existing backup grants access; it does not copy or restore it.":mode.equals("files")?"Includes internal files, caches, restore staging and app external folders. Links are not followed. Tap totals for GB/GiB and scan details.":"Copies are already included in App files totals. Current clients, runtimes and databases are protected.");
        String text = !SessionBackup.recoveryError.isEmpty() ? "Return to the launcher to finish session recovery.\n" + SessionBackup.recoveryError
                : busy ? WorkService.message + (WorkService.result.isEmpty() ? "" : "\n" + WorkService.result)
                : SessionBackup.active ? "A complete session transfer is in progress. Storage browsing will resume when it finishes."
                : scanning ? scanStatus : (completedOperation.isEmpty() ? "" : completedOperation + "\n") + idleStatus;
        if (!blocked && !scanning && mode.equals("copies") && runtimesActive()) text += "\nStop the client and server before deleting a copy.";
        status.setText(text);
        progress.setVisibility(busy || SessionBackup.active || scanning ? View.VISIBLE : View.GONE);
        cancel.setVisibility(busy || scanning ? View.VISIBLE : View.GONE);
        cancel.setText(busy ? "Cancel operation" : "Cancel scan");
        refresh.setEnabled(!blocked);
    }

    private void cancelOperation() {
        if (WorkService.busy) {
            dialog = new AlertDialog.Builder(this).setTitle("Cancel current operation?")
                    .setMessage("The operation will stop at its next safe point. Files already deleted cannot be recovered.")
                    .setNegativeButton("Keep running", null)
                    .setPositiveButton("Cancel operation", (d, which) -> { WorkService.cancel(); toast("Cancelling; waiting for the current file operation to stop."); }).show();
        } else {
            stopScan();
            idleStatus = "Scan cancelled. Tap Refresh to measure copies again.";
            updateStatus();
        }
    }

    private void openRow(int position) {
        if (maintenanceBlocked() || scanning) return;
        if (itemId.isEmpty()) {
            if(mode.equals("exports")){if(position<exports.size())showExport(exports.get(position));return;}
            if(mode.equals("files")){
                if(storage==null||position>=storage.entries.size())return;
                AppStorage.Entry entry=storage.entries.get(position);
                if(entry.directory&&!entry.link){itemId=entry.id;itemLabel=entry.label;relativePath="";nodes=Collections.emptyList();adapter.notifyDataSetChanged();load();}
                else toast(entry.link?"Links are listed without following their target.":"File contents are private; this browser shows names and sizes only.");
                return;
            }
            if (position >= items.size()) return;
            showItem(items.get(position));
        } else if (position < nodes.size()) {
            StorageBackups.Node node = nodes.get(position);
            if (node.directory && !node.link) {
                relativePath = node.path;
                nodes = Collections.emptyList();
                adapter.notifyDataSetChanged();
                load();
            } else toast(node.link ? "Links are listed without following their target." : "File contents are private; this browser shows names and sizes only.");
        }
    }

    private String storageDetails() {
        String text="Unique app file sizes: "+size(storage.bytes)+" ("+String.format(Locale.ROOT,"%.2f GB",storage.bytes/1e9)+")\nAllocated on disk: "+(storage.allocationKnown?size(storage.allocated):"unavailable on this filesystem")
                +"\nUnreadable/changed entries: "+storage.errors;
        if(storage.androidData>=0)text+="\n\nAndroid app data (installed volume): "+size(storage.androidData)+" ("+String.format(Locale.ROOT,"%.2f GB",storage.androidData/1e9)+")\nCache included in Android data: "+size(storage.androidCache)+"\nInstalled app/code (separate): "+size(storage.androidCode);
        else text+="\n\nAndroid system statistics are unavailable.";
        text+="\n\nThe scan includes all accessible internal and app external folders, including caches and restore staging. Hard links count once; symbolic links are not followed. An unreadable folder makes the scan a lower bound. GB uses 1,000,000,000 bytes; GiB uses 1,073,741,824 bytes. Running processes and filesystem overhead can change totals. Shared-storage exports are listed separately and are not added to these totals.";
        return text;
    }

    private void locateExport() {
        if(maintenanceBlocked()||scanning)return;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker,LOCATE);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=LOCATE||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(maintenanceBlocked()){toast("Wait for maintenance before locating a backup.");return;}
        Uri uri=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri,flags);
            ExportedBackups.record(this,uri,"located");
            toast("Existing backup located. No files were copied or restored.");mode="exports";load();
        }catch(Exception error){toast("Could not retain access to this document: "+message(error));}
    }

    private void showExport(ExportedBackups.Entry entry) {
        String type=entry.kind.equals("working-backup")?"Working combination":entry.kind.equals("server-db")?"SQL database backup":"Complete session / located backup";
        String body=type+"\n"+(entry.bytes<0?"Size not supplied by Android Files":size(entry.bytes))+"\nSaved: "+new java.util.Date(entry.saved)
                +"\n\n"+(entry.accessible?"Stored in the destination you chose; no internal archive copy was created.":"Access is unavailable. Use Locate existing backup to select this same external file. Older versions released its access permission.");
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(display(entry.name)).setMessage(body).setNegativeButton("Close",null);
        builder.setNeutralButton("Locate existing backup",(d,w)->locateExport());
        if(entry.canDelete)builder.setPositiveButton("Delete export…",(d,w)->{
            dialog=new AlertDialog.Builder(this).setTitle("Delete exported backup?")
                    .setMessage(display(entry.name)+"\n\nThis permanently deletes only the selected external archive. It does not remove your installed client, server, database or settings.")
                    .setNegativeButton("Keep backup",null).setPositiveButton("Delete export",(confirmation,which)->{
                        if(maintenanceBlocked()){toast("Wait for maintenance before deleting an export.");return;}
                        stopScan();scanning=true;scanStatus="Deleting selected export…";updateStatus();final int expected=scanGeneration;
                        scan=worker.submit(()->{String outcome;try{ExportedBackups.delete(getApplicationContext(),entry.uri);outcome="Export deleted. Installed data preserved.";}catch(Exception error){outcome="Could not delete export: "+message(error);}final String message=outcome;ui.post(()->{if(!current(expected))return;completedOperation=message;load();});});
                    }).show();
        });
        dialog=builder.show();
    }

    private void showItem(StorageBackups.Item item) {
        String body = "File sizes: " + size(item.bytes) + " · " + item.files + " files · " + item.directories + " folders · " + item.links + " links\n\n"
                + item.detail + "\n\nLocation: " + display(item.path);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(display(item.label)).setMessage(body).setNegativeButton("Close", null);
        if (item.directory) builder.setNeutralButton("Browse contents", (d, which) -> {
            itemId = item.id; itemLabel = item.label; relativePath = "";
            nodes = Collections.emptyList(); adapter.notifyDataSetChanged(); load();
        });
        if (item.deletable) builder.setPositiveButton("Delete copy…", (d, which) -> confirmDelete(item));
        dialog = builder.show();
        if (item.deletable && runtimesActive()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void confirmDelete(StorageBackups.Item item) {
        if (WorkService.busy || runtimesActive()) { toast("Stop the client and server and wait for other operations before deleting."); return; }
        dialog = new AlertDialog.Builder(this).setTitle("Delete this retained copy?")
                .setMessage(display(item.label) + "\n" + size(item.bytes) + "\n\n" + item.detail
                        + "\n\nThis permanently removes this copy. Any rollback that depends on it will no longer be available. Export a complete session backup first if you want to keep it.")
                .setNegativeButton("Keep copy", null)
                .setPositiveButton("Delete copy", (d, which) -> {
                    final String id = item.id;
                    if (runtimesActive()) { toast("Stop the client and server before deleting."); return; }
                    stopScan();
                    boolean accepted = WorkService.submit(getApplicationContext(), "Deleting retained copy", (context, progress) -> StorageBackups.delete(context, id, progress));
                    if (!accepted) { toast("An operation is already running or could not start."); load(); }
                    else { showingWork = true; updateStatus(); }
                }).show();
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return !itemId.isEmpty()?nodes.size():mode.equals("exports")?exports.size():mode.equals("files")?storage==null?0:storage.entries.size():items.size(); }
        @Override public Object getItem(int position) { return !itemId.isEmpty()?nodes.get(position):mode.equals("exports")?exports.get(position):mode.equals("files")?storage.entries.get(position):items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = column(); row.setPadding(dp(14), dp(8), dp(14), dp(8));
                row.setBackground(new FantasyTiles.Panel(getResources().getDisplayMetrics().density, false));
                TextView name = label("", 16, TEXT); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); row.addView(name);
                TextView detail = label("", 12, MUTED); detail.setMaxLines(3); detail.setEllipsize(TextUtils.TruncateAt.END); row.addView(detail);
            }
            TextView name = (TextView) row.getChildAt(0), detail = (TextView) row.getChildAt(1);
            if(itemId.isEmpty()&&mode.equals("exports")){
                ExportedBackups.Entry entry=exports.get(position);name.setText(display(entry.name));
                detail.setText((entry.bytes<0?"Size unknown":size(entry.bytes))+" · External export\n"+(entry.accessible?"Tap to review":"Locate existing backup to reconnect"));
            }else if(itemId.isEmpty()&&mode.equals("files")){
                AppStorage.Entry entry=storage.entries.get(position);name.setText(display(entry.label));
                detail.setText(size(entry.bytes)+(entry.errors==0?"":" · partial")+" · "+(entry.link?"Link · target not followed":entry.directory?"Folder · tap to browse":"Read-only file")+"\n"+entry.files+" files · "+entry.links+" links");
            }else if (itemId.isEmpty()) {
                StorageBackups.Item item = items.get(position);
                name.setText(display(item.label));
                detail.setText(size(item.bytes) + " · " + (item.deletable ? "Retained copy · tap to review" : "Protected · tap to review") + "\n" + item.detail);
            } else {
                StorageBackups.Node node = nodes.get(position);
                name.setText(display(node.name));
                detail.setText(node.link ? "Link · target not followed" : node.directory ? (mode.equals("files")?size(node.bytes)+(node.sizeIncomplete?" · partial":"")+" · ":"")+"Folder · tap to browse" : size(node.bytes) + " · file");
            }
            return row;
        }
    }

    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(3), 0, dp(3)); return view;
    }
    private Button button(LinearLayout parent, String title, Runnable action, boolean horizontal) {
        Button view = new Button(this); view.setText(title); view.setAllCaps(false); view.setTextColor(TEXT); view.setMinHeight(dp(44));
        view.setTypeface(Typeface.create("serif", Typeface.BOLD));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x446ed5e8), new FantasyTiles.Panel(getResources().getDisplayMetrics().density, true), null));
        LinearLayout.LayoutParams place = horizontal ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        place.setMargins(dp(2), dp(3), dp(2), dp(3)); parent.addView(view, place);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"}; double n = bytes; int index = -1;
        do { n /= 1024; index++; } while (n >= 1024 && index < units.length - 1);
        return String.format(Locale.ROOT, n >= 10 ? "%.1f %s" : "%.2f %s", n, units[index]);
    }
    private static String display(String value) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c >= '\u202a' && c <= '\u202e' || c >= '\u2066' && c <= '\u2069') safe.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else safe.append(c);
        }
        return safe.toString();
    }
}
