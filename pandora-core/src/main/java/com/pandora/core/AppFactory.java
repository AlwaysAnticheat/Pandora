package com.pandora.core;

import android.app.AppComponentFactory;
import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AppFactory extends AppComponentFactory {
    private static final String LogTag = "Pandora";
    private volatile AppComponentFactory OriginalFactory;

    public static final class Data {
        public static String ApkPath;
        public static String ReplacementPath;

        private Data() {
        }
    }

    public static native void initRedirect(String OriginalPath, String ReplacementPath);
    public static native boolean isRedirectActive();
    public static native int getTracerPid();

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader Loader, ApplicationInfo Info) {
        OriginalFactory = CreateOriginalFactory(Loader, Info.sourceDir);
        return OriginalFactory == null
                ? super.instantiateClassLoader(Loader, Info)
                : OriginalFactory.instantiateClassLoader(Loader, Info);
    }

    @Override
    public Application instantiateApplication(ClassLoader Loader, String Name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateApplication(Loader, Name)
                : OriginalFactory.instantiateApplication(Loader, Name);
    }

    @Override
    public Activity instantiateActivity(ClassLoader Loader, String Name, Intent Intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateActivity(Loader, Name, Intent)
                : OriginalFactory.instantiateActivity(Loader, Name, Intent);
    }

    @Override
    public Service instantiateService(ClassLoader Loader, String Name, Intent Intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateService(Loader, Name, Intent)
                : OriginalFactory.instantiateService(Loader, Name, Intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader Loader, String Name, Intent Intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateReceiver(Loader, Name, Intent)
                : OriginalFactory.instantiateReceiver(Loader, Name, Intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader Loader, String Name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateProvider(Loader, Name)
                : OriginalFactory.instantiateProvider(Loader, Name);
    }

    private AppComponentFactory CreateOriginalFactory(ClassLoader Loader, String ApkPath) {
        try (ZipFile Archive = new ZipFile(ApkPath)) {
            String Name = ReadAssetText(Archive, "assets/pandora/original_factory.txt", 512);
            if (Name == null) {
                return null;
            }
            return Loader.loadClass(Name.trim()).asSubclass(AppComponentFactory.class)
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | IOException Error) {
            throw new IllegalStateException("The original component factory could not be loaded", Error);
        }
    }

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

        Data.ApkPath = Info.sourceDir;
        Data.ReplacementPath = ExtractOriginalApk(Info);

        System.loadLibrary("pandora-core");
        initRedirect(Data.ApkPath, Data.ReplacementPath);
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

    private static String ReadAssetText(ZipFile Archive, String Name, int Limit)
            throws IOException {
        ZipEntry Entry = Archive.getEntry(Name);
        if (Entry == null) {
            return null;
        }
        if (Entry.getSize() > Limit) {
            throw new IOException("Payload metadata exceeds its limit");
        }
        try (InputStream Input = Archive.getInputStream(Entry)) {
            ByteArrayOutputStream Output = new ByteArrayOutputStream();
            byte[] Buffer = new byte[4096];
            int Count;
            while ((Count = Input.read(Buffer)) != -1) {
                if (Output.size() + Count > Limit) {
                    throw new IOException("Payload metadata exceeds its limit");
                }
                Output.write(Buffer, 0, Count);
            }
            return new String(Output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
