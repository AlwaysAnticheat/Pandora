package com.pandora.builder.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pandora.builder.ApkPatcher;
import com.pandora.builder.R;

import java.util.Locale;

public final class BuildProgressDialog extends Dialog implements ApkPatcher.ProgressListener {
    private final Handler Main = new Handler(Looper.getMainLooper());
    private final StringBuilder Log = new StringBuilder();
    private final StringBuilder PendingLog = new StringBuilder();
    private final long Started = SystemClock.elapsedRealtime();
    private final TextView Stage;
    private final TextView Bytes;
    private final TextView Terminal;
    private final TextView Action;
    private final ProgressBar Progress;
    private final ScrollView Scroll;
    private final TextView Latest;
    private boolean AutoFollow = true;
    private boolean Touching;
    private int TouchSequence;
    private boolean UpdatePending;
    private boolean Finished;
    private String CurrentStage = "Preparing…";
    private long Completed;
    private long Total = -1;

    public BuildProgressDialog(Activity Activity) {
        super(Activity, R.style.PandoraTheme);
        setContentView(R.layout.dialog_build_progress);
        setCancelable(false);
        Stage = findViewById(R.id.BuildStage);
        Bytes = findViewById(R.id.BuildBytes);
        Terminal = findViewById(R.id.TerminalLog);
        Action = findViewById(R.id.BuildAction);
        Progress = findViewById(R.id.EntryProgress);
        Scroll = findViewById(R.id.TerminalScroll);
        Latest = findViewById(R.id.LatestBuildLog);
        Terminal.setText("", TextView.BufferType.EDITABLE);
        Scroll.setOnTouchListener((View, Event) -> {
            if (Event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                Touching = true;
                TouchSequence++;
                AutoFollow = false;
            } else if (Event.getActionMasked() == MotionEvent.ACTION_UP ||
                    Event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                Touching = false;
                AutoFollow = AtBottom();
            }
            Latest.setVisibility(AutoFollow ? View.INVISIBLE : View.VISIBLE);
            return false;
        });
        Scroll.setOnScrollChangeListener((View, X, Y, OldX, OldY) -> {
            AutoFollow = AtBottom();
            Latest.setVisibility(AutoFollow ? View.INVISIBLE : View.VISIBLE);
        });
        Latest.setOnClickListener(View -> {
            AutoFollow = true;
            Scroll.fullScroll(View.FOCUS_DOWN);
            Latest.setVisibility(View.INVISIBLE);
        });
        findViewById(R.id.CopyBuildLog).setOnClickListener(View -> {
            String Text;
            synchronized (this) {
                Text = Log.toString();
            }
            ClipboardManager Clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            Clipboard.setPrimaryClip(ClipData.newPlainText("Pandora build log", Text));
            ((TextView) findViewById(R.id.CopyBuildLog)).setText("Copied");
            Main.postDelayed(() -> ((TextView) findViewById(R.id.CopyBuildLog)).setText("Copy log"), 1500);
        });
    }

    @Override
    public void show() {
        super.show();
        if (getWindow() != null) {
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            getWindow().setStatusBarColor(getContext().getColor(R.color.background));
            getWindow().setNavigationBarColor(getContext().getColor(R.color.background));
        }
    }

    @Override
    public synchronized void OnStage(String Message) {
        long Elapsed = SystemClock.elapsedRealtime() - Started;
        String Line = String.format(Locale.ROOT, "[%02d:%02d.%03d] %s%n",
                Elapsed / 60000, (Elapsed / 1000) % 60, Elapsed % 1000, Message);
        Log.append(Line);
        PendingLog.append(Line);
        if (Log.length() > 1048576) {
            Log.delete(0, Log.indexOf("\n", Log.length() - 786432) + 1);
            Log.insert(0, "[Earlier log entries trimmed]\n");
        }
        CurrentStage = Message;
        Total = -1;
        Schedule();
    }

    @Override
    public synchronized void OnBytes(String Entry, long Completed, long Total) {
        CurrentStage = Entry;
        this.Completed = Completed;
        this.Total = Total;
        Schedule();
    }

    private void Schedule() {
        if (!UpdatePending && !Finished) {
            UpdatePending = true;
            Main.postDelayed(this::Render, 150);
        }
    }

    private boolean AtBottom() {
        return Scroll.getScrollY() + Scroll.getHeight() >= Terminal.getHeight() +
                Scroll.getPaddingTop() + Scroll.getPaddingBottom() -
                Math.round(24 * getContext().getResources().getDisplayMetrics().density);
    }

    private void Render() {
        String Message;
        String Lines;
        long Done;
        long Size;
        synchronized (this) {
            UpdatePending = false;
            Message = CurrentStage;
            Done = Completed;
            Size = Total;
            Lines = PendingLog.toString();
            PendingLog.setLength(0);
        }
        Stage.setText(Message);
        if (Progress.isIndeterminate() != (Size <= 0)) {
            Progress.setIndeterminate(Size <= 0);
        }
        if (Size > 0) {
            Progress.setMax(1000);
            Progress.setProgress((int) Math.min(1000, Done * 1000 / Size));
            Bytes.setText(String.format(Locale.ROOT, "Current entry · %,d / %,d bytes · %d%%",
                    Done, Size, Math.min(100, Done * 100 / Size)));
        } else {
            Bytes.setText("Processing current operation");
        }
        boolean Follow = AutoFollow && !Touching;
        int Sequence = TouchSequence;
        if (!Lines.isEmpty()) {
            Terminal.append(Lines);
            if (Follow && Terminal.length() > 1048576) {
                Terminal.getEditableText().delete(0, Terminal.length() - 786432);
            }
            if (Follow) {
                Scroll.post(() -> {
                    if (!Touching && TouchSequence == Sequence) {
                        Scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        }
    }

    public void Complete(Runnable Save) {
        Main.post(() -> Finish("Ready to save", "Save unsigned APK", () -> {
            dismiss();
            Save.run();
        }));
    }

    public void Fail(String Message) {
        OnStage("ERROR " + (Message == null ? "Build failed" : Message));
        Main.post(() -> Finish("Build failed", "Close", this::dismiss));
    }

    private void Finish(String Message, String Label, Runnable Click) {
        synchronized (this) {
            Finished = true;
        }
        Main.removeCallbacksAndMessages(null);
        Render();
        Stage.setText(Message);
        Bytes.setText("Log shows completed operations");
        Progress.setVisibility(View.GONE);
        Action.setText(Label);
        Action.setTextColor(getContext().getColor(R.color.primary));
        Action.setEnabled(true);
        Action.setOnClickListener(View -> Click.run());
        setCancelable(true);
    }

    @Override
    public synchronized void dismiss() {
        Finished = true;
        Main.removeCallbacksAndMessages(null);
        super.dismiss();
    }
}
