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
import java.util.Arrays;
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

    /** Receives one human-readable progress line per step. */
    public interface Logger {
        void log(String line);
    }

    private GestureLibAutoImport() {}

    /** Silent automatic variant for app startup — no-op if already imported. */
    public static void tryImportFromInstalledGboard(final Context context) {
        try {
            final File libFile = new File(context.getFilesDir(), JniUtils.JNI_LIB_IMPORT_FILE_NAME);
            if (libFile.isFile()) return; // already imported (manually or by us)
            final boolean ok = runImport(context, new Logger() {
                @Override
                public void log(final String line) {
                    Log.i(TAG, line);
                }
            });
            if (ok) {
                Toast.makeText(context, "Glide typing enabled (imported from Gboard)",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "auto-import failed", t);
        }
    }

    /**
     * Verbose variant for the settings screen: runs (or re-runs) the import,
     * narrating every step through the logger. Returns true on success.
     */
    public static boolean runImport(final Context context, final Logger logger) {
        try {
            return importInner(context, logger);
        } catch (Throwable t) {
            logger.log("FATAL: " + t);
            Log.w(TAG, "import failed", t);
            return false;
        }
    }

    private static boolean importInner(final Context context, final Logger log) throws Exception {
        log.log("build type: " + BuildConfig.BUILD_TYPE);
        if (BuildConfig.BUILD_TYPE.equals("nouserlib")) {
            log.log("STOP: this build type does not allow loading a user library");
            return false;
        }
        final File libFile = new File(context.getFilesDir(), JniUtils.JNI_LIB_IMPORT_FILE_NAME);
        log.log("target: " + libFile.getAbsolutePath());
        log.log("target exists: " + libFile.isFile()
                + ", library currently loaded: " + JniUtils.sHaveGestureLib);
        log.log("device ABIs: " + Arrays.toString(Build.SUPPORTED_ABIS));

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
        log.log("candidate packages: " + packages);

        boolean anyFound = false;
        for (final String pkg : packages) {
            final ApplicationInfo info;
            try {
                info = context.getPackageManager().getApplicationInfo(pkg, 0);
            } catch (Exception e) {
                log.log("✗ " + pkg + ": not visible/installed (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            anyFound = true;
            log.log("✓ " + pkg);
            log.log("  base apk: " + info.sourceDir);
            final List<String> apks = new ArrayList<>();
            if (info.sourceDir != null) apks.add(info.sourceDir);
            if (info.splitSourceDirs != null) {
                for (final String split : info.splitSourceDirs) {
                    log.log("  split apk: " + split);
                    apks.add(split);
                }
            }
            log.log("  native lib dir: " + info.nativeLibraryDir);

            // 1) look inside the APK zips
            for (final String apkPath : apks) {
                final String shortName = apkPath.substring(apkPath.lastIndexOf('/') + 1);
                try (ZipFile zip = new ZipFile(apkPath)) {
                    final List<String> libEntries = new ArrayList<>();
                    int total = 0;
                    final Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        total++;
                        final String name = entries.nextElement().getName();
                        if (name.endsWith("/" + LIB_FILE_NAME) || name.equals(LIB_FILE_NAME)) {
                            libEntries.add(name);
                        }
                    }
                    log.log("  scanning " + shortName + ": " + total + " entries, "
                            + libEntries.size() + " gesture-lib match(es)");
                    for (final String name : libEntries) log.log("    found: " + name);
                    for (final String abi : Build.SUPPORTED_ABIS) {
                        for (final String name : libEntries) {
                            if (!name.contains("/" + abi + "/")) continue;
                            log.log("  extracting " + name);
                            try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                                if (finishImport(context, in, libFile, log)) return true;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.log("  ERROR scanning " + shortName + ": " + e);
                }
            }

            // 2) fall back to the extracted native library directory
            //    (system apps often keep libs outside the APK)
            if (info.nativeLibraryDir != null) {
                final File nativeDir = new File(info.nativeLibraryDir);
                final File[] files = nativeDir.listFiles();
                if (files == null) {
                    log.log("  native lib dir not listable");
                } else {
                    log.log("  native lib dir contains " + files.length + " file(s):");
                    for (final File f : files) {
                        log.log("    " + f.getName() + " (" + f.length() + " bytes)");
                    }
                }
                final File nativeLib = new File(nativeDir, LIB_FILE_NAME);
                if (nativeLib.isFile()) {
                    log.log("  found in native lib dir, importing");
                    try (InputStream in = new FileInputStream(nativeLib)) {
                        if (finishImport(context, in, libFile, log)) return true;
                    } catch (Exception e) {
                        log.log("  ERROR reading native lib: " + e);
                    }
                } else {
                    log.log("  " + LIB_FILE_NAME + " not present in native lib dir");
                }
            }
        }

        // 3) factory-preinstalled Gboard: scan system partitions directly
        if (scanSystemPartitions(context, libFile, log)) return true;

        if (!anyFound) {
            log.log("FAILED: no keyboard packages visible on this device");
        } else {
            log.log("FAILED: no glide typing library found in any scanned package");
        }
        return false;
    }

    // System-image app directories often hold the factory Gboard APK with its
    // libraries either inside the APK or in an adjacent lib/<arch> directory.
    private static boolean scanSystemPartitions(final Context context, final File libFile,
            final Logger log) {
        final String[] roots = {
                "/system/app", "/system/priv-app", "/product/app", "/product/priv-app",
                "/system_ext/app", "/system_ext/priv-app", "/vendor/app",
        };
        // extracted system lib dirs use short arch names
        final String[] archDirs = {"arm64", "arm"};
        log.log("scanning system partitions…");
        for (final String root : roots) {
            final File rootDir = new File(root);
            final File[] appDirs = rootDir.listFiles();
            if (appDirs == null) {
                log.log("  " + root + ": not listable");
                continue;
            }
            for (final File appDir : appDirs) {
                final String lower = appDir.getName().toLowerCase(java.util.Locale.US);
                if (!lower.contains("latinime") && !lower.contains("gboard")
                        && !lower.contains("inputmethod")) continue;
                log.log("  candidate dir: " + appDir.getAbsolutePath());
                // adjacent extracted libs
                for (final String arch : archDirs) {
                    final File lib = new File(appDir, "lib/" + arch + "/" + LIB_FILE_NAME);
                    log.log("    checking " + lib.getAbsolutePath()
                            + (lib.isFile() ? " — FOUND" : " — no"));
                    if (lib.isFile()) {
                        try (InputStream in = new FileInputStream(lib)) {
                            if (finishImport(context, in, libFile, log)) return true;
                        } catch (Exception e) {
                            log.log("    ERROR reading: " + e);
                        }
                    }
                }
                // apks inside the dir
                final File[] apkFiles = appDir.listFiles();
                if (apkFiles == null) continue;
                for (final File apk : apkFiles) {
                    if (!apk.getName().endsWith(".apk")) continue;
                    try (ZipFile zip = new ZipFile(apk)) {
                        final List<String> libEntries = new ArrayList<>();
                        final Enumeration<? extends ZipEntry> entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            final String name = entries.nextElement().getName();
                            if (name.endsWith("/" + LIB_FILE_NAME)) libEntries.add(name);
                        }
                        log.log("    scanning " + apk.getName() + ": "
                                + libEntries.size() + " gesture-lib match(es)");
                        for (final String abi : Build.SUPPORTED_ABIS) {
                            for (final String name : libEntries) {
                                if (!name.contains("/" + abi + "/")) continue;
                                log.log("    extracting " + name);
                                try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                                    if (finishImport(context, in, libFile, log)) return true;
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.log("    ERROR scanning " + apk.getName() + ": " + e);
                    }
                }
            }
        }
        return false;
    }

    private static boolean finishImport(final Context context, final InputStream in,
            final File libFile, final Logger log) {
        final File tmpFile = new File(context.getFilesDir(), "tmp_gesture_lib");
        try {
            try (OutputStream out = new FileOutputStream(tmpFile)) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            log.log("  copied " + tmpFile.length() + " bytes");
            final String checksum = ChecksumCalculator.INSTANCE.checksum(tmpFile);
            log.log("  checksum: " + checksum);
            if (checksum == null || checksum.isEmpty()) {
                log.log("  ERROR: could not compute checksum");
                tmpFile.delete();
                return false;
            }
            // store the checksum first, like the manual import does, so
            // JniUtils accepts the file on the next startup as well
            KtxKt.protectedPrefs(context).edit()
                    .putString(Settings.PREF_LIBRARY_CHECKSUM, checksum).commit();
            log.log("  checksum stored in prefs");
            libFile.setWritable(true);
            libFile.delete();
            copyFile(tmpFile, libFile);
            libFile.setReadOnly();
            tmpFile.delete();
            log.log("  library installed at " + libFile.getAbsolutePath());
            if (!JniUtils.sHaveGestureLib) {
                log.log("  loading library into this process…");
                try {
                    System.load(libFile.getAbsolutePath());
                    JniUtils.sHaveGestureLib = true;
                    log.log("  loaded OK");
                } catch (Throwable t) {
                    log.log("  load failed (" + t + "), will load on next app start");
                }
            } else {
                log.log("  a library is already loaded in this process");
            }
            log.log("SUCCESS — glide typing library imported. Gesture typing is enabled;"
                    + " if swiping does not work yet, switch keyboards once or reboot.");
            return true;
        } catch (Exception e) {
            log.log("  ERROR during import: " + e);
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
}
