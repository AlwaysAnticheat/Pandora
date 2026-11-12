package com.pandora.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.pandora.builder.ui.BuildProgressDialog;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class BuildProgressTest {
    @Test
    public void KeepsUserScrollPositionWhileNewLinesArrive() throws Exception {
        try (ActivityScenario<MainActivity> Screen = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<BuildProgressDialog> Dialog = new AtomicReference<>();
            Screen.onActivity(Activity -> {
                Dialog.set(new BuildProgressDialog(Activity));
                Dialog.get().show();
            });
            try {
                for (int Index = 0; Index < 300; Index++) {
                    Dialog.get().OnStage("Entry " + Index);
                }
                Thread.sleep(600);
                AtomicInteger Position = new AtomicInteger();
                Screen.onActivity(Activity -> {
                    ScrollView Scroll = Dialog.get().findViewById(R.id.TerminalScroll);
                    long Time = SystemClock.uptimeMillis();
                    MotionEvent Down = MotionEvent.obtain(Time, Time, MotionEvent.ACTION_DOWN, 30, 30, 0);
                    Scroll.dispatchTouchEvent(Down);
                    Down.recycle();
                    Scroll.scrollTo(0, 300);
                    Position.set(Scroll.getScrollY());
                });
                assertTrue(Position.get() > 0);
                for (int Index = 300; Index < 500; Index++) {
                    Dialog.get().OnStage("Entry " + Index);
                }
                Thread.sleep(500);
                Screen.onActivity(Activity -> {
                    ScrollView Scroll = Dialog.get().findViewById(R.id.TerminalScroll);
                    assertEquals(Position.get(), Scroll.getScrollY());
                    long Time = SystemClock.uptimeMillis();
                    MotionEvent Up = MotionEvent.obtain(Time, Time, MotionEvent.ACTION_UP, 30, 30, 0);
                    Scroll.dispatchTouchEvent(Up);
                    Up.recycle();
                });
                Dialog.get().OnStage("Another entry after releasing the gesture");
                Thread.sleep(400);
                Screen.onActivity(Activity -> {
                    ScrollView Scroll = Dialog.get().findViewById(R.id.TerminalScroll);
                    assertEquals(Position.get(), Scroll.getScrollY());
                    assertEquals(View.VISIBLE, Dialog.get().findViewById(R.id.LatestBuildLog).getVisibility());
                    Dialog.get().findViewById(R.id.LatestBuildLog).performClick();
                });
                Thread.sleep(200);
                Screen.onActivity(Activity -> {
                    ScrollView Scroll = Dialog.get().findViewById(R.id.TerminalScroll);
                    assertTrue(Scroll.getScrollY() > Position.get());
                });
            } finally {
                Screen.onActivity(Activity -> Dialog.get().dismiss());
            }
        }
    }
}
