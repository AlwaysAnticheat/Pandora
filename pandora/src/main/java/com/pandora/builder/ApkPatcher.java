package com.pandora.builder;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.FilterOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ApkPatcher {
    private static final String PayloadFactory = "com.pandora.core.AppFactory";
    private static final String NativeLibrary = "lib/arm64-v8a/libpandora-core.so";
    private final ProgressListener Progress;

    public interface ProgressListener {
        void OnStage(String Message);
        void OnBytes(String Entry, long Completed, long Total);
    }

    public ApkPatcher() {
        this(null);
    }

    public ApkPatcher(ProgressListener Progress) {
        this.Progress = Progress;
    }

    private void Stage(String Message) {
        if (Progress != null) {
            Progress.OnStage(Message);
        }
    }

    public static final class Result {
        public final String PackageName;
        public final int AddedDexFiles;

        private Result(String PackageName, int AddedDexFiles) {
            this.PackageName = PackageName;
            this.AddedDexFiles = AddedDexFiles;
        }
    }

    private static final class ManifestResult {
        final byte[] Bytes;
        final String OriginalFactory;

        ManifestResult(byte[] Bytes, String OriginalFactory) {
            this.Bytes = Bytes;
            this.OriginalFactory = OriginalFactory;
        }
    }

    public Result Patch(File InputApk, InputStream PayloadApk, File OutputApk,
                        PackageManager Packages) throws IOException {
        if (InputApk == null || !InputApk.isFile() || OutputApk == null) {
            throw new IOException("Choose a valid input APK");
        }
        Stage("Inspect input package");
        PackageInfo Package = Packages.getPackageArchiveInfo(InputApk.getAbsolutePath(), 0);
        if (Package == null || Package.packageName == null) {
            throw new IOException("The input APK has no readable package information");
        }
        Stage("Package " + Package.packageName);

        File PayloadFile = File.createTempFile("pandora-payload-", ".apk", OutputApk.getParentFile());
        try {
            Stage("Stage embedded Pandora payload");
            Files.copy(PayloadApk, PayloadFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return PatchArchives(InputApk, PayloadFile, OutputApk, Package.packageName);
        } finally {
            Files.deleteIfExists(PayloadFile.toPath());
        }
    }

    private Result PatchArchives(File InputApk, File PayloadFile, File OutputApk,
                                 String PackageName) throws IOException {
        try (ZipFile Input = new ZipFile(InputApk); ZipFile Payload = new ZipFile(PayloadFile)) {
            Stage("Read input and payload archives");
            ZipEntry ManifestEntry = Input.getEntry("AndroidManifest.xml");
            ZipEntry LibraryEntry = Payload.getEntry(NativeLibrary);
            if (ManifestEntry == null || LibraryEntry == null || Input.getEntry("classes.dex") == null) {
                throw new IOException("The input or payload APK is incomplete");
            }
            if (Input.getEntry("assets/base.apk") != null || Input.getEntry(NativeLibrary) != null) {
                throw new IOException("This APK already contains Pandora payload paths");
            }

            Stage("Rewrite binary AndroidManifest.xml");
            ManifestResult Manifest = RewriteManifest(Input.getInputStream(ManifestEntry));
            Stage(Manifest.OriginalFactory == null ? "Install Pandora component factory"
                    : "Chain original factory " + Manifest.OriginalFactory);
            List<ZipEntry> PayloadDex = FindDexEntries(Payload);
            int NextDex = FindNextDexIndex(Input);
            if (PayloadDex.isEmpty()) {
                throw new IOException("The payload has no DEX files");
            }

            CountingOutputStream Counted = new CountingOutputStream(new FileOutputStream(OutputApk));
            try (ZipOutputStream Output = new ZipOutputStream(Counted)) {
                Enumeration<? extends ZipEntry> Entries = Input.entries();
                while (Entries.hasMoreElements()) {
                    ZipEntry Entry = Entries.nextElement();
                    String Name = Entry.getName();
                    if (Name.equals("AndroidManifest.xml") || IsSigningMetadata(Name)) {
                        Stage("Replace or remove " + Name);
                        continue;
                    }
                    if (Name.equals("resources.arsc")) {
                        CopyStoredEntry(Input.getInputStream(Entry), Output, Counted, Entry);
                    } else {
                        CopyEntry(Input.getInputStream(Entry), Output, Name, Entry.getSize());
                    }
                }

                AddEntry(Output, "AndroidManifest.xml", Manifest.Bytes);
                try (InputStream Original = new FileInputStream(InputApk)) {
                    CopyEntry(Original, Output, "assets/base.apk", InputApk.length());
                }
                if (Manifest.OriginalFactory != null) {
                    AddEntry(Output, "assets/pandora/original_factory.txt",
                            Manifest.OriginalFactory.getBytes(StandardCharsets.UTF_8));
                }

                for (ZipEntry Entry : PayloadDex) {
                    String Name = NextDex == 1 ? "classes.dex" : "classes" + NextDex + ".dex";
                    CopyEntry(Payload.getInputStream(Entry), Output, Name, Entry.getSize());
                    NextDex++;
                }
                CopyEntry(Payload.getInputStream(LibraryEntry), Output, NativeLibrary, LibraryEntry.getSize());
                Stage("Finalize ZIP central directory");
            }

            Stage("Check required output entries");
            try (ZipFile Result = new ZipFile(OutputApk)) {
                if (Result.getEntry("AndroidManifest.xml") == null ||
                        Result.getEntry(NativeLibrary) == null ||
                        Result.getEntry("assets/base.apk") == null) {
                    throw new IOException("The output APK failed validation");
                }
            }
            Stage("Build complete · " + OutputApk.length() + " output bytes · unsigned APK");
            return new Result(PackageName, PayloadDex.size());
        }
    }

    private static ManifestResult RewriteManifest(InputStream Input) throws IOException {
        AndroidManifestBlock Manifest;
        try (InputStream Source = Input) {
            Manifest = AndroidManifestBlock.load(Source);
        }
        ResXmlElement Application = Manifest.getApplicationElement();
        if (Application == null) {
            throw new IOException("The input APK has no application element");
        }
        String OriginalFactory = null;
        for (int Index = 0; Index < Application.getAttributeCount(); Index++) {
            ResXmlAttribute Attribute = Application.getAttributeAt(Index);
            if ("appComponentFactory".equals(Attribute.getName())) {
                OriginalFactory = Attribute.getValueString();
                if (OriginalFactory == null || OriginalFactory.isEmpty() ||
                        OriginalFactory.equals(PayloadFactory)) {
                    throw new IOException("The input component factory cannot be chained");
                }
                OriginalFactory = Manifest.fullClassName(OriginalFactory);
            }
        }

        Application.getOrCreateAndroidAttribute("appComponentFactory",
                android.R.attr.appComponentFactory).setValueAsString(PayloadFactory);
        Manifest.setExtractNativeLibs(true);
        Manifest.refreshFull();
        File Temporary = File.createTempFile("pandora-manifest-", ".xml");
        try {
            Manifest.writeBytes(Temporary);
            return new ManifestResult(Files.readAllBytes(Temporary.toPath()), OriginalFactory);
        } finally {
            Files.deleteIfExists(Temporary.toPath());
        }
    }

    private static List<ZipEntry> FindDexEntries(ZipFile Archive) {
        List<ZipEntry> Entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> All = Archive.entries();
        while (All.hasMoreElements()) {
            ZipEntry Entry = All.nextElement();
            if (IsDexName(Entry.getName())) {
                Entries.add(Entry);
            }
        }
        return Entries;
    }

    private static int FindNextDexIndex(ZipFile Archive) {
        int Highest = 0;
        Enumeration<? extends ZipEntry> Entries = Archive.entries();
        while (Entries.hasMoreElements()) {
            String Name = Entries.nextElement().getName();
            if (IsDexName(Name)) {
                int Index = Name.equals("classes.dex") ? 1
                        : Integer.parseInt(Name.substring(7, Name.length() - 4));
                Highest = Math.max(Highest, Index);
            }
        }
        return Highest + 1;
    }

    private static boolean IsDexName(String Name) {
        return Name.equals("classes.dex") || Name.matches("classes(?:[2-9]|[1-9][0-9]+)\\.dex");
    }

    private static boolean IsSigningMetadata(String Name) {
        if (!Name.startsWith("META-INF/")) {
            return false;
        }
        String Upper = Name.toUpperCase(Locale.ROOT);
        return Upper.equals("META-INF/MANIFEST.MF") || Upper.endsWith(".SF") ||
                Upper.endsWith(".RSA") || Upper.endsWith(".DSA") || Upper.endsWith(".EC");
    }

    private void AddEntry(ZipOutputStream Output, String Name, byte[] Content)
            throws IOException {
        CopyEntry(new java.io.ByteArrayInputStream(Content), Output, Name, Content.length);
    }

    private void CopyEntry(InputStream Input, ZipOutputStream Output, String Name, long Size)
            throws IOException {
        try (InputStream Source = Input) {
            Stage("Write " + Name);
            ZipEntry Entry = new ZipEntry(Name);
            Output.putNextEntry(Entry);
            long Copied = Transfer(Source, Output, Name, Size);
            Output.closeEntry();
            Stage("Wrote " + Name + " · " + Copied + " bytes");
        }
    }

    private void CopyStoredEntry(InputStream Input, ZipOutputStream Output,
                                        CountingOutputStream Counted, ZipEntry Original)
            throws IOException {
        try (InputStream Source = Input) {
            Stage("Write and align " + Original.getName());
            ZipEntry Entry = new ZipEntry(Original.getName());
            Entry.setMethod(ZipEntry.STORED);
            Entry.setSize(Original.getSize());
            Entry.setCompressedSize(Original.getSize());
            Entry.setCrc(Original.getCrc());

            int NameLength = Entry.getName().getBytes(StandardCharsets.UTF_8).length;
            int Padding = (int) ((4 - (Counted.Count + 30 + NameLength + 4) % 4) % 4);
            byte[] Extra = new byte[4 + Padding];
            Extra[0] = (byte) 0xfe;
            Extra[1] = (byte) 0xca;
            Extra[2] = (byte) Padding;
            Entry.setExtra(Extra);

            Output.putNextEntry(Entry);
            long Copied = Transfer(Source, Output, Original.getName(), Original.getSize());
            Output.closeEntry();
            Stage("Wrote aligned " + Original.getName() + " · " + Copied + " bytes");
        }
    }

    private long Transfer(InputStream Input, OutputStream Output, String Name, long Size)
            throws IOException {
        byte[] Buffer = new byte[65536];
        long Copied = 0;
        long LastUpdate = System.nanoTime();
        int Count;
        while ((Count = Input.read(Buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Build interrupted");
            }
            Output.write(Buffer, 0, Count);
            Copied += Count;
            if (Progress != null && System.nanoTime() - LastUpdate >= 100000000) {
                Progress.OnBytes(Name, Copied, Size);
                LastUpdate = System.nanoTime();
            }
        }
        if (Progress != null) {
            Progress.OnBytes(Name, Copied, Size);
        }
        return Copied;
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long Count;

        private CountingOutputStream(OutputStream Output) {
            super(Output);
        }

        @Override
        public void write(int Value) throws IOException {
            out.write(Value);
            Count++;
        }

        @Override
        public void write(byte[] Buffer, int Offset, int Length) throws IOException {
            out.write(Buffer, Offset, Length);
            Count += Length;
        }
    }
}
