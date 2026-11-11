package com.pandora.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;

import org.lsposed.hiddenapibypass.LSPass;

import java.lang.reflect.Field;
import java.util.Map;

public final class CreatorProxy implements Parcelable.Creator<PackageInfo> {
    private final Parcelable.Creator<PackageInfo> OriginalCreator;
    private final String TargetPackageName;
    private final Signature ReplacementSignature;

    private CreatorProxy(Parcelable.Creator<PackageInfo> OriginalCreator,
                         String TargetPackageName, Signature ReplacementSignature) {
        this.OriginalCreator = OriginalCreator;
        this.TargetPackageName = TargetPackageName;
        this.ReplacementSignature = ReplacementSignature;
    }

    @Override
    public PackageInfo createFromParcel(Parcel Source) {
        PackageInfo Info = OriginalCreator.createFromParcel(Source);
        if (Info == null || !TargetPackageName.equals(Info.packageName)) {
            return Info;
        }

        if (Info.signatures != null && Info.signatures.length > 0) {
            Info.signatures[0] = ReplacementSignature;
        }
        if (Info.signingInfo != null) {
            Signature[] Signers = Info.signingInfo.getApkContentsSigners();
            if (Signers != null && Signers.length > 0) {
                Signers[0] = ReplacementSignature;
            }
        }
        return Info;
    }

    @Override
    public PackageInfo[] newArray(int Size) {
        return OriginalCreator.newArray(Size);
    }

    public static void Install(String PackageName, String SignatureData)
            throws ReflectiveOperationException {
        if (PackageName == null || SignatureData == null || SignatureData.isEmpty()) {
            throw new IllegalArgumentException("Package and signature are required");
        }

        Signature Replacement = new Signature(Base64.decode(SignatureData, Base64.DEFAULT));
        LSPass.addHiddenApiExemptions("Landroid/os/Parcel;", "Landroid/content/pm/");
        try {
            Parcelable.Creator<PackageInfo> Original = PackageInfo.CREATOR;
            if (Original instanceof CreatorProxy) {
                return;
            }

            Field CreatorField = FindField(PackageInfo.class, "CREATOR");
            CreatorField.set(null, new CreatorProxy(Original, PackageName, Replacement));
            ClearFieldMap(Parcel.class, "mCreators");
            ClearFieldMap(Parcel.class, "sPairedCreators");
            ClearPackageInfoCache();
        } finally {
            LSPass.clearHiddenApiExemptions();
        }
    }

    private static void ClearFieldMap(Class<?> Type, String Name)
            throws ReflectiveOperationException {
        try {
            Object Value = FindField(Type, Name).get(null);
            if (Value instanceof Map) {
                ((Map<?, ?>) Value).clear();
            }
        } catch (NoSuchFieldException Error) {
            if (!"sPairedCreators".equals(Name)) {
                throw Error;
            }
        }
    }

    private static void ClearPackageInfoCache() throws ReflectiveOperationException {
        try {
            Object Cache = FindField(PackageManager.class, "sPackageInfoCache").get(null);
            Cache.getClass().getMethod("clear").invoke(Cache);
        } catch (NoSuchFieldException Error) {
            return;
        }
    }

    private static Field FindField(Class<?> Type, String Name) throws NoSuchFieldException {
        Field Result = Type.getDeclaredField(Name);
        Result.setAccessible(true);
        return Result;
    }
}
