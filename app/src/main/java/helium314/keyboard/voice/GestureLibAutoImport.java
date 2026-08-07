// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import helium314.keyboard.latin.BuildConfig;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.utils.ChecksumCalculator;
import helium314.keyboard.latin.utils.JniUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.Log;

/**
 * Imports the glide typing library from a Gboard installation already present
 * on this device, so gesture typing works out of the box without the user
 * having to obtain and select the library file manually. Nothing is
 * redistributed: the library is read from the user's own installed copy of
 * Gboard and stored exactly like a manually imported library (see
 * LoadGestureLibPreference / JniUtils).
 */
public final class GestureLibAutoImport {
    private static final String TAG = "GestureLibAutoImport";
    // Gboard's package name; its APK contains libjni_latinimegoogle.so
    private static final String[] SOURCE_PACKAGES = {
            "com.google.android.inputmethod.latin"
    };

    private GestureLibAutoImport() {}

    /** Call early (before JniUtils is first used) — no-op if a library is already imported. */
    public static void tryImportFromInstalledGboard(final Context context) {
        try {
            importInner(context);
        } catch (Throwable t) {
            Log.w(TAG, "auto-import failed", t);
        }
    }

    private static void importInner(final Context context) throws Exception {
        if (BuildConfig.BUILD_TYPE.equals("nouserlib")) return;
        final File libFile = new File(context.getFilesDir(), JniUtils.JNI_LIB_IMPORT_FILE_NAME);
        if (libFile.isFile()) return; // already imported (manually or by us)

        for (final String pkg : SOURCE_PACKAGES) {
            final ApplicationInfo info;
            try {
                info = context.getPackageManager().getApplicationInfo(pkg, 0);
            } catch (Exception e) {
                continue; // not installed
            }
            final List<String> apks = new ArrayList<>();
            if (info.sourceDir != null) apks.add(info.sourceDir);
            if (info.splitSourceDirs != null) {
                for (final String split : info.splitSourceDirs) apks.add(split);
            }
            for (final String abi : Build.SUPPORTED_ABIS) {
                final String entryPath = "lib/" + abi + "/lib" + JniUtils.JNI_LIB_NAME_GOOGLE + ".so";
                for (final String apkPath : apks) {
                    if (extractEntry(apkPath, entryPath, context, libFile)) {
                        Log.i(TAG, "imported glide typing library from " + pkg + " (" + abi + ")");
                        loadNow(libFile);
                        android.widget.Toast.makeText(context,
                                "Glide typing enabled (library imported from Gboard)",
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                }
            }
        }
    }

    private static boolean extractEntry(final String apkPath, final String entryPath,
            final Context context, final File libFile) {
        final File tmpFile = new File(context.getFilesDir(), "tmp_gesture_lib");
        try (ZipFile zip = new ZipFile(apkPath)) {
            final ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) return false;
            try (InputStream in = zip.getInputStream(entry);
                    OutputStream out = new FileOutputStream(tmpFile)) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            final String checksum = ChecksumCalculator.INSTANCE.checksum(tmpFile);
            if (checksum == null || checksum.isEmpty()) {
                tmpFile.delete();
                return false;
            }
            // store the checksum first, like the manual import does, so
            // JniUtils accepts the file on the next startup as well
            KtxKt.protectedPrefs(context).edit()
                    .putString(Settings.PREF_LIBRARY_CHECKSUM, checksum).commit();
            libFile.setWritable(true);
            libFile.delete();
            copyFile(tmpFile, libFile);
            libFile.setReadOnly();
            tmpFile.delete();
            return true;
        } catch (Exception e) {
            tmpFile.delete();
            return false;
        }
    }

    private static void copyFile(final File from, final File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
                OutputStream out = new FileOutputStream(to)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private static void loadNow(final File libFile) {
        // if JniUtils was already initialized without a library, load it in
        // this process too; otherwise the next startup picks it up anyway
        if (JniUtils.sHaveGestureLib) return;
        try {
            System.load(libFile.getAbsolutePath());
            JniUtils.sHaveGestureLib = true;
        } catch (Throwable t) {
            Log.w(TAG, "could not load imported library in this process", t);
        }
    }
}
