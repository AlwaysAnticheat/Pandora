package com.pandora.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

@RunWith(AndroidJUnit4.class)
public final class ApkPatcherTest {
    @Test
    public void BuildsUnsignedPayloadArchive() throws Exception {
        Context App = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File Input = new File(App.getApplicationInfo().sourceDir);
        File Output = new File(App.getCacheDir(), "patcher-test.apk");

        try (InputStream Payload = App.getAssets().open("pandora/payload.apk")) {
            ApkPatcher.Result Result = new ApkPatcher().Patch(
                    Input, Payload, Output, App.getPackageManager());
            assertEquals(App.getPackageName(), Result.PackageName);
            assertTrue(Result.AddedDexFiles > 0);
        }

        try (ZipFile Archive = new ZipFile(Output)) {
            assertNotNull(Archive.getEntry("assets/base.apk"));
            assertNotNull(Archive.getEntry("assets/pandora/signature.txt"));
            assertNotNull(Archive.getEntry("assets/pandora/original_factory.txt"));
            assertNotNull(Archive.getEntry("lib/arm64-v8a/libpandora-core.so"));
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
