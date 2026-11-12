package com.pandora.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipFile;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class ApkPatcherTest {
    @Test
    public void BuildsUnsignedPayloadArchive() throws Exception {
        Context App = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File Input = new File(App.getApplicationInfo().sourceDir);
        File Output = new File(App.getCacheDir(), "patcher-test.apk");
        List<String> Stages = new ArrayList<>();
        Map<String, Long> CopiedBytes = new HashMap<>();
        ApkPatcher.ProgressListener Progress = new ApkPatcher.ProgressListener() {
            @Override
            public void OnStage(String Message) {
                Stages.add(Message);
            }

            @Override
            public void OnBytes(String Entry, long Completed, long Total) {
                assertTrue(Completed >= CopiedBytes.getOrDefault(Entry, 0L));
                assertTrue(Total < 0 || Completed <= Total);
                CopiedBytes.put(Entry, Completed);
            }
        };

        try (InputStream Payload = App.getAssets().open("pandora/payload.apk")) {
            ApkPatcher.Result Result = new ApkPatcher(Progress).Patch(
                    Input, Payload, Output, App.getPackageManager());
            assertEquals(App.getPackageName(), Result.PackageName);
            assertTrue(Result.AddedDexFiles > 0);
        }
        assertTrue(Stages.contains("Rewrite binary AndroidManifest.xml"));
        assertTrue(Stages.get(Stages.size() - 1).startsWith("Build complete"));
        assertEquals(Input.length(), (long) CopiedBytes.get("assets/base.apk"));

        try (ZipFile Archive = new ZipFile(Output)) {
            assertNotNull(Archive.getEntry("assets/base.apk"));
            assertNull(Archive.getEntry("assets/pandora/signature.txt"));
            assertNotNull(Archive.getEntry("assets/pandora/original_factory.txt"));
            assertNotNull(Archive.getEntry("lib/arm64-v8a/libpandora-core.so"));
            for (Map.Entry<String, Long> Transfer : CopiedBytes.entrySet()) {
                assertEquals(Archive.getEntry(Transfer.getKey()).getSize(), (long) Transfer.getValue());
            }
            try (InputStream ManifestInput = Archive.getInputStream(
                    Archive.getEntry("AndroidManifest.xml"))) {
                AndroidManifestBlock Manifest = AndroidManifestBlock.load(ManifestInput);
                ResXmlElement Application = Manifest.getApplicationElement();
                assertEquals("com.pandora.core.AppFactory",
                        Application.getOrCreateAndroidAttribute("appComponentFactory",
                                android.R.attr.appComponentFactory).getValueString());
            }
        }
    }
}
