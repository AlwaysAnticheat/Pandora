package com.pandora.core;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Clean Java reconstruction of the injected AppComponentFactory. */
public class AppFactory extends AppComponentFactory {
    public static final class DATA {
        public static String packageName;
        public static String apkPath;
        public static String dataDir;
        public static String repPath;

        /*
         * The supplied DEX declares this field but contains no initializer or setter.
         * It is kept exactly as recovered so missing behavior is not invented.
         */
        public static String signatureData;

        public DATA() {
        }
    }

    public static native void initRedirect(String originalPath, String replacementPath);
    public static native boolean isRedirectActive();
    public static native int getTracerPid();

    static {
        try {
            initializeAppData();
            start();
        } catch (Throwable ignored) {
            // The original class initializer suppresses all failures.
        }
    }

    private static void initializeAppData() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method currentMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
        Object activityThread = currentMethod.invoke(null);

        Field boundField = activityThreadClass.getDeclaredField("mBoundApplication");
        boundField.setAccessible(true);
        Object boundApplication = boundField.get(activityThread);

        Field infoField = boundApplication.getClass().getDeclaredField("appInfo");
        infoField.setAccessible(true);
        ApplicationInfo info = (ApplicationInfo) infoField.get(boundApplication);

        DATA.packageName = info.packageName;
        DATA.apkPath = info.sourceDir;
        DATA.dataDir = info.dataDir;
        if (DATA.apkPath == null) {
            System.err.println("Get apk path failed");
            return;
        }

        File replacement = new File(DATA.dataDir, "base.apk");
        try (ZipFile archive = new ZipFile(new File(DATA.apkPath))) {
            ZipEntry entry = archive.getEntry("assets/base.apk");
            if (entry == null) {
                System.err.println("Entry not found: assets/base.apk");
                return;
            }
            if (!replacement.exists() || replacement.length() != entry.getSize()) {
                try (InputStream input = archive.getInputStream(entry);
                     OutputStream output = new FileOutputStream(replacement)) {
                    byte[] buffer = new byte[102400];
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        output.write(buffer, 0, length);
                    }
                }
            }
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
        DATA.repPath = replacement.getAbsolutePath();
    }

    public static void start() {
        try {
            System.loadLibrary("pandora-core");
            initRedirect(DATA.apkPath, DATA.repPath);
            CreatorProxy.initCreatorProxy();
        } catch (Throwable error) {
            error.printStackTrace();
        }
    }
}
