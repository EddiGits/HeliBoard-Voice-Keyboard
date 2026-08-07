// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
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
    private static final String LIB_FILE_NAME = "lib" + JniUtils.JNI_LIB_NAME_GOOGLE + ".so";

    private GestureLibAutoImport() {}

    /** Call early (before JniUtils is first used) — no-op if a library is already imported. */
    public static void tryImportFromInstalledGboard(final Context context) {
        try {
            final String failure = importInner(context);
            if (failure != null) {
                Log.w(TAG, failure);
                Toast.makeText(context, failure, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "auto-import failed", t);
            try {
                Toast.makeText(context, "Glide import error: " + t, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    // returns null when nothing needs reporting (already imported, disabled, or success)
    private static String importInner(final Context context) throws Exception {
        if (BuildConfig.BUILD_TYPE.equals("nouserlib")) return null;
        final File libFile = new File(context.getFilesDir(), JniUtils.JNI_LIB_IMPORT_FILE_NAME);
        if (libFile.isFile()) return null; // already imported (manually or by us)

        // candidate packages: known Gboard ids plus every keyboard app on the device
        final LinkedHashSet<String> packages = new LinkedHashSet<>();
        packages.add("com.google.android.inputmethod.latin");
        final InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (final InputMethodInfo info : imm.getInputMethodList()) {
                packages.add(info.getPackageName());
            }
        }
        packages.remove(context.getPackageName());

        boolean anyPackageFound = false;
        final List<String> scanned = new ArrayList<>();
        for (final String pkg : packages) {
            final ApplicationInfo info;
            try {
                info = context.getPackageManager().getApplicationInfo(pkg, 0);
            } catch (Exception e) {
                continue; // not installed or not visible
            }
            anyPackageFound = true;
            scanned.add(pkg);

            final List<String> apks = new ArrayList<>();
            if (info.sourceDir != null) apks.add(info.sourceDir);
            if (info.splitSourceDirs != null) {
                for (final String split : info.splitSourceDirs) apks.add(split);
            }
            for (final String apkPath : apks) {
                if (scanApkAndImport(apkPath, context, libFile)) {
                    Log.i(TAG, "imported glide typing library from " + pkg
                            + " (" + apkPath + ")");
                    loadNow(libFile);
                    Toast.makeText(context,
                            "Glide typing enabled (library imported from " + pkg + ")",
                            Toast.LENGTH_LONG).show();
                    return null;
                }
            }
        }

        if (!anyPackageFound) {
            return "Glide import: no Gboard/keyboard apps visible on this device";
        }
        return "Glide import: no glide library found in " + scanned;
    }

    // scans all entries of the apk for the gesture library, preferring the device's best ABI
    private static boolean scanApkAndImport(final String apkPath, final Context context,
            final File libFile) {
        try (ZipFile zip = new ZipFile(apkPath)) {
            final List<String> libEntries = new ArrayList<>();
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final String name = entries.nextElement().getName();
                if (name.endsWith("/" + LIB_FILE_NAME) || name.equals(LIB_FILE_NAME)) {
                    libEntries.add(name);
                }
            }
            if (libEntries.isEmpty()) return false;
            for (final String abi : Build.SUPPORTED_ABIS) {
                for (final String name : libEntries) {
                    if (name.contains("/" + abi + "/")
                            && extractEntry(zip, name, context, libFile)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "could not scan " + apkPath, e);
            return false;
        }
    }

    private static boolean extractEntry(final ZipFile zip, final String entryName,
            final Context context, final File libFile) {
        final File tmpFile = new File(context.getFilesDir(), "tmp_gesture_lib");
        try {
            final ZipEntry entry = zip.getEntry(entryName);
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
            Log.w(TAG, "could not extract " + entryName, e);
            tmpFile.delete();
            return false;
        }
    }

    private static void copyFile(final File from, final File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
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
