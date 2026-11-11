package com.pandora.core;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AppFactory extends AppComponentFactory {
    private static final String LogTag = "Pandora";

    public static final class Data {
        public static String PackageName;
        public static String ApkPath;
        public static String ReplacementPath;
        public static String SignatureData;

        private Data() {
        }
    }

    public static native void initRedirect(String OriginalPath, String ReplacementPath);
    public static native boolean isRedirectActive();
    public static native int getTracerPid();

    static {
        try {
            Initialize();
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError Error) {
            Log.e(LogTag, "Initialization failed", Error);
        }
    }

    private static void Initialize() throws ReflectiveOperationException, IOException {
        ApplicationInfo Info = ReadApplicationInfo();
        if (Info == null || Info.sourceDir == null || Info.dataDir == null) {
            throw new IllegalStateException("Application information is unavailable");
        }

        Data.PackageName = Info.packageName;
        Data.ApkPath = Info.sourceDir;
        Data.ReplacementPath = ExtractOriginalApk(Info);

        System.loadLibrary("pandora-core");
        initRedirect(Data.ApkPath, Data.ReplacementPath);
        if (Data.SignatureData != null && !Data.SignatureData.isEmpty()) {
            CreatorProxy.Install(Data.PackageName, Data.SignatureData);
        }
    }

    private static ApplicationInfo ReadApplicationInfo() throws ReflectiveOperationException {
        Class<?> ThreadClass = Class.forName("android.app.ActivityThread");
        Method CurrentThread = ThreadClass.getDeclaredMethod("currentActivityThread");
        Object Thread = CurrentThread.invoke(null);
        if (Thread == null) {
            return null;
        }

        Field BoundApplication = ThreadClass.getDeclaredField("mBoundApplication");
        BoundApplication.setAccessible(true);
        Object Bound = BoundApplication.get(Thread);
        if (Bound == null) {
            return null;
        }

        Field AppInfo = Bound.getClass().getDeclaredField("appInfo");
        AppInfo.setAccessible(true);
        return (ApplicationInfo) AppInfo.get(Bound);
    }

    private static String ExtractOriginalApk(ApplicationInfo Info) throws IOException {
        File Directory = new File(Info.dataDir);
        File Destination = new File(Directory, "base.apk");
        try (ZipFile Archive = new ZipFile(Info.sourceDir)) {
            ZipEntry Entry = Archive.getEntry("assets/base.apk");
            if (Entry == null) {
                throw new IOException("The embedded APK is missing");
            }

            File Temporary = File.createTempFile("pandora-", ".apk", Directory);
            try {
                try (InputStream Input = Archive.getInputStream(Entry)) {
                    Files.copy(Input, Temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (Entry.getSize() >= 0 && Temporary.length() != Entry.getSize()) {
                    throw new IOException("The embedded APK is incomplete");
                }
                Files.move(Temporary.toPath(), Destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(Temporary.toPath());
            }
        }
        return Destination.getAbsolutePath();
    }
}
