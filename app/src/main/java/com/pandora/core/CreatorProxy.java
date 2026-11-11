package com.pandora.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;

import org.lsposed.hiddenapibypass.LSPass;

import java.lang.reflect.Field;
import java.util.Map;

/** Clean Java reconstruction verified against CreatorProxy.smali. */
public final class CreatorProxy implements Parcelable.Creator<PackageInfo> {
    private static boolean sCreatorProxyInited;
    private final Parcelable.Creator<PackageInfo> mOriginalCreator;

    private CreatorProxy(Parcelable.Creator<PackageInfo> original) {
        mOriginalCreator = original;
    }

    @Override
    public PackageInfo createFromParcel(Parcel source) {
        PackageInfo info = mOriginalCreator.createFromParcel(source);
        if (info.packageName.equals(AppFactory.DATA.packageName)) {
            Signature replacement = new Signature(Base64.decode(AppFactory.DATA.signatureData, 0));
            if (info.signatures != null && info.signatures.length > 0) {
                info.signatures[0] = replacement;
            }
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                Signature[] signers = info.signingInfo.getApkContentsSigners();
                if (signers != null && signers.length > 0) {
                    signers[0] = replacement;
                }
            }
        }
        return info;
    }

    @Override
    public PackageInfo[] newArray(int size) {
        return mOriginalCreator.newArray(size);
    }

    @SuppressWarnings("unchecked")
    public static boolean initCreatorProxy() {
        if (Build.VERSION.SDK_INT >= 28) {
            LSPass.addHiddenApiExemptions(
                    "Landroid/os/Parcel;",
                    "Landroid/content/pm",
                    "Landroid/app");
        }
        try {
            Parcelable.Creator<PackageInfo> original = PackageInfo.CREATOR;
            if (original instanceof CreatorProxy) {
                return true;
            }

            findField(PackageInfo.class, "CREATOR").set(null, new CreatorProxy(original));
            Object cache = findField(PackageManager.class, "sPackageInfoCache").get(null);
            cache.getClass().getMethod("clear").invoke(cache);
            ((Map<?, ?>) findField(Parcel.class, "mCreators").get(null)).clear();
            ((Map<?, ?>) findField(Parcel.class, "sPairedCreators").get(null)).clear();
            findField(Class.class, "name").set(CreatorProxy.class,
                    "android.content.pm.PackageInfo$1");
            sCreatorProxyInited = true;

            if (Build.VERSION.SDK_INT >= 28) {
                LSPass.clearHiddenApiExemptions();
            }
            return sCreatorProxyInited;
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        try {
            Field result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (NoSuchFieldException firstError) {
            for (Class<?> parent = type.getSuperclass();
                 parent != null && parent != Object.class;
                 parent = parent.getSuperclass()) {
                try {
                    Field result = parent.getDeclaredField(name);
                    result.setAccessible(true);
                    return result;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw firstError;
        }
    }
}
