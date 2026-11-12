package com.pandora.builder;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class InstalledAppsActivity extends Activity {
    public static final String SelectedPackage = "SelectedPackage";

    private final ExecutorService Worker = Executors.newSingleThreadExecutor();
    private final List<AppEntry> Apps = new ArrayList<>();
    private final List<AppEntry> VisibleApps = new ArrayList<>();
    private final LruCache<String, Drawable> Icons = new LruCache<>(64);
    private final Set<String> PendingIcons = new HashSet<>();
    private final AppAdapter Adapter = new AppAdapter();
    private EditText Search;
    private TextView Count;
    private TextView Empty;
    private boolean ShowSystem;
    private boolean Loaded;
    private Dialog MessageDialog;

    @Override
    protected void onCreate(Bundle State) {
        super.onCreate(State);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        getWindow().getDecorView().setSystemUiVisibility(0);
        setContentView(R.layout.activity_installed_apps);
        Search = findViewById(R.id.AppSearch);
        Count = findViewById(R.id.AppCount);
        Empty = findViewById(R.id.AppEmpty);
        ShowSystem = State != null && State.getBoolean("ShowSystem");

        ListView List = findViewById(R.id.AppList);
        List.setAdapter(Adapter);
        List.setOnItemClickListener((Parent, Row, Position, Id) -> Select(VisibleApps.get(Position)));
        findViewById(R.id.BackButton).setOnClickListener(View -> finish());
        findViewById(R.id.UserAppsFilter).setOnClickListener(View -> SetFilter(false));
        findViewById(R.id.AllAppsFilter).setOnClickListener(View -> SetFilter(true));
        Search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence Text, int Start, int Count, int After) {}

            @Override
            public void onTextChanged(CharSequence Text, int Start, int Before, int Count) {
                Filter();
            }

            @Override
            public void afterTextChanged(Editable Text) {}
        });
        Search.setOnEditorActionListener((View, Action, Event) -> {
            if (Action == EditorInfo.IME_ACTION_DONE) {
                HideKeyboard();
                return true;
            }
            return false;
        });
        SetFilter(ShowSystem);
        Worker.execute(this::LoadApps);
    }

    private void LoadApps() {
        try {
            List<AppEntry> Result = new ArrayList<>();
            for (ApplicationInfo Info : getPackageManager().getInstalledApplications(0)) {
                if (Info.sourceDir == null) {
                    continue;
                }
                try {
                    Result.add(new AppEntry(Info, Info.loadLabel(getPackageManager()).toString()));
                } catch (RuntimeException Error) {
                    continue;
                }
            }
            Collator Names = Collator.getInstance();
            Result.sort((Left, Right) -> {
                int Order = Names.compare(Left.Name, Right.Name);
                return Order != 0 ? Order : Left.Info.packageName.compareTo(Right.Info.packageName);
            });
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                Apps.addAll(Result);
                Loaded = true;
                findViewById(R.id.AppLoading).setVisibility(View.GONE);
                Filter();
            });
        } catch (RuntimeException Error) {
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    findViewById(R.id.AppLoading).setVisibility(View.GONE);
                    Count.setText("Apps could not be loaded");
                    Empty.setText("Close this screen and try again.");
                    Empty.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private void SetFilter(boolean AllApps) {
        ShowSystem = AllApps;
        findViewById(R.id.UserAppsFilter).setBackgroundResource(AllApps ? 0 : R.drawable.segmented_selected);
        findViewById(R.id.AllAppsFilter).setBackgroundResource(AllApps ? R.drawable.segmented_selected : 0);
        ((TextView) findViewById(R.id.UserAppsFilter)).setTextColor(getColor(AllApps ? R.color.muted : R.color.text));
        ((TextView) findViewById(R.id.AllAppsFilter)).setTextColor(getColor(AllApps ? R.color.text : R.color.muted));
        Filter();
    }

    private void Filter() {
        if (!Loaded) {
            return;
        }
        String Query = Search.getText().toString().trim().toLowerCase(Locale.ROOT);
        VisibleApps.clear();
        for (AppEntry App : Apps) {
            if ((ShowSystem || !App.System) && App.SearchKey.contains(Query)) {
                VisibleApps.add(App);
            }
        }
        Adapter.notifyDataSetChanged();
        Count.setText(VisibleApps.size() + " apps");
        Empty.setText(Query.isEmpty() ? "No installed apps to show." : "No apps match your search.");
        Empty.setVisibility(VisibleApps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void Select(AppEntry App) {
        HideKeyboard();
        if (App.Split) {
            Dialog Message = new Dialog(this);
            MessageDialog = Message;
            Message.setContentView(R.layout.dialog_message);
            Message.findViewById(R.id.MessageClose).setOnClickListener(View -> Message.dismiss());
            Message.show();
            if (Message.getWindow() != null) {
                float Density = getResources().getDisplayMetrics().density;
                int Width = Math.min((int) (320 * Density), getResources().getDisplayMetrics().widthPixels - (int) (48 * Density));
                Message.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                Message.getWindow().setLayout(Width, WindowManager.LayoutParams.WRAP_CONTENT);
                Message.getWindow().setDimAmount(0.65f);
            }
            return;
        }
        setResult(RESULT_OK, new Intent().putExtra(SelectedPackage, App.Info.packageName));
        finish();
    }

    private void HideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(Search.getWindowToken(), 0);
        Search.clearFocus();
    }

    @Override
    protected void onSaveInstanceState(Bundle State) {
        super.onSaveInstanceState(State);
        State.putBoolean("ShowSystem", ShowSystem);
    }

    @Override
    protected void onDestroy() {
        if (MessageDialog != null) {
            MessageDialog.dismiss();
        }
        Worker.shutdownNow();
        super.onDestroy();
    }

    private static final class AppEntry {
        final ApplicationInfo Info;
        final String Name;
        final String SearchKey;
        final boolean System;
        final boolean Split;

        AppEntry(ApplicationInfo Info, String Name) {
            this.Info = Info;
            this.Name = Name;
            SearchKey = (Name + " " + Info.packageName).toLowerCase(Locale.ROOT);
            System = (Info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            Split = Info.splitSourceDirs != null && Info.splitSourceDirs.length > 0;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() { return VisibleApps.size(); }

        @Override
        public AppEntry getItem(int Position) { return VisibleApps.get(Position); }

        @Override
        public long getItemId(int Position) { return Position; }

        @Override
        public View getView(int Position, View Row, ViewGroup Parent) {
            if (Row == null) {
                Row = getLayoutInflater().inflate(R.layout.installed_app_row, Parent, false);
            }
            AppEntry App = getItem(Position);
            ((TextView) Row.findViewById(R.id.AppName)).setText(App.Name);
            ((TextView) Row.findViewById(R.id.AppPackage)).setText(App.Info.packageName);
            ((TextView) Row.findViewById(R.id.AppFormat)).setText(App.Split ? "Split APK · standalone APK required" : "Standalone APK");
            ImageView Icon = Row.findViewById(R.id.AppIcon);
            String Package = App.Info.packageName;
            Icon.setTag(Package);
            Drawable Cached = Icons.get(Package);
            Icon.setImageDrawable(Cached != null ? Cached : getPackageManager().getDefaultActivityIcon());
            if (Cached == null && PendingIcons.add(Package)) {
                Worker.execute(() -> {
                    Drawable LoadedIcon;
                    try {
                        LoadedIcon = App.Info.loadIcon(getPackageManager());
                    } catch (RuntimeException Error) {
                        LoadedIcon = getPackageManager().getDefaultActivityIcon();
                    }
                    Icons.put(Package, LoadedIcon);
                    runOnUiThread(() -> {
                        PendingIcons.remove(Package);
                        if (!isDestroyed()) {
                            Adapter.notifyDataSetChanged();
                        }
                    });
                });
            }
            return Row;
        }
    }
}
