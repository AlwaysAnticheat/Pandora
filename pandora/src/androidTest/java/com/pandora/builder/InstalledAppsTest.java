package com.pandora.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class InstalledAppsTest {
    @Test
    public void ImportsInstalledSelectionAndCompletesBuildLog() throws Exception {
        Context App = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> Screen = ActivityScenario.launch(MainActivity.class)) {
            Screen.onActivity(Activity -> Activity.onActivityResult(MainActivity.InstalledRequest, Activity.RESULT_OK,
                    new Intent().putExtra(InstalledAppsActivity.SelectedPackage, App.getPackageName())));
            AtomicBoolean Ready = new AtomicBoolean(false);
            for (int Attempt = 0; Attempt < 200 && !Ready.get(); Attempt++) {
                Screen.onActivity(Activity -> Ready.set(Activity.findViewById(R.id.BuildButton).isEnabled()
                        && ((TextView) Activity.findViewById(R.id.InputDetails)).getText().toString().contains(App.getPackageName())));
                if (!Ready.get()) {
                    Thread.sleep(50);
                }
            }
            assertTrue("Installed APK was not imported", Ready.get());
            Screen.onActivity(Activity -> Activity.findViewById(R.id.BuildButton).performClick());
            boolean Complete = false;
            for (int Attempt = 0; Attempt < 300; Attempt++) {
                AccessibilityNodeInfo Root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
                if (Root != null && !Root.findAccessibilityNodeInfosByText("Save unsigned APK").isEmpty()) {
                    Complete = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue("Build did not reach its save state", Complete);
        }
    }

    @Test
    public void FindsAndSelectsInstalledAppByPackage() throws Exception {
        Context App = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InstalledAppsActivity> Screen = ActivityScenario.launchActivityForResult(
                new Intent(App, InstalledAppsActivity.class))) {
            Screen.onActivity(Activity -> ((EditText) Activity.findViewById(R.id.AppSearch)).setText(App.getPackageName()));
            AtomicInteger Position = new AtomicInteger(-1);
            for (int Attempt = 0; Attempt < 200 && Position.get() < 0; Attempt++) {
                Screen.onActivity(Activity -> {
                    ListView List = Activity.findViewById(R.id.AppList);
                    for (int Index = 0; Index < List.getAdapter().getCount(); Index++) {
                        android.view.View Row = List.getAdapter().getView(Index, null, List);
                        String Package = ((TextView) Row.findViewById(R.id.AppPackage)).getText().toString();
                        if (App.getPackageName().equals(Package)) {
                            Position.set(Index);
                            break;
                        }
                    }
                });
                if (Position.get() < 0) {
                    Thread.sleep(50);
                }
            }
            assertTrue("Installed app was absent from search results", Position.get() >= 0);
            Screen.onActivity(Activity -> {
                ListView List = Activity.findViewById(R.id.AppList);
                List.performItemClick(null, Position.get(), Position.get());
            });
            assertEquals(Activity.RESULT_OK, Screen.getResult().getResultCode());
            assertEquals(App.getPackageName(), Screen.getResult().getResultData()
                    .getStringExtra(InstalledAppsActivity.SelectedPackage));
        }
    }
}
