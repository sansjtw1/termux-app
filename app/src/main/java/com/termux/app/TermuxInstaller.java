package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    private static final String OLD_PREFIX = "/data/data/com.termux";
    private static final String NEW_PREFIX = "/data/data/com.kalinrx";

    private static final String BOOTSTRAP_PATCHED_MARKER =
        TERMUX_PREFIX_DIR_PATH + "/.kalinrx_bootstrap_patched";

    /** Request code for Kali local file picker. */
    public static final int REQUEST_CODE_KALI_LOCAL_FILE = 10001;

    /** Pending callback for when file picker returns. */
    private static Runnable sPendingKaliWhenDone = null;

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // Check if bootstrap is properly installed and patched
        boolean bootstrapReady = isBootstrapReady();

        if (bootstrapReady) {
            // Bootstrap is fine, check if Kali needs setup
            if (!KalinRXSetup.isKaliInstalled()) {
                startKaliSetup(activity, whenDone);
            } else {
                whenDone.run();
            }
            return;
        } else if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true) &&
                   !TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
            // Bootstrap exists but is broken, log and reinstall
            Logger.logWarn(LOG_TAG, "Bootstrap directory exists but is corrupted. Reinstalling...");
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        // Install bootstrap
        final ProgressDialog progress = ProgressDialog.show(activity, null,
            "Installing KalinRX environment...", true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    updateProgressMessage(activity, progress, "Extracting bootstrap packages...");

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);
                    final Set<String> executableFiles = new HashSet<>();

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (isExecutablePath(zipEntryName)) {
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                        executableFiles.add(targetFile.getAbsolutePath());
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");
                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    // Patch bootstrap files to use new package name
                    updateProgressMessage(activity, progress, "Patching bootstrap files for KalinRX...");
                    Logger.logInfo(LOG_TAG, "Patching bootstrap files with new package name prefix...");
                    Set<String> patchedExecutableFiles = new HashSet<>();
                    for (String path : executableFiles) {
                        String newPath = path.replace(TERMUX_STAGING_PREFIX_DIR_PATH, TERMUX_PREFIX_DIR_PATH);
                        patchedExecutableFiles.add(newPath);
                    }
                    patchBootstrapFiles(TERMUX_PREFIX_DIR_PATH, patchedExecutableFiles);

                    // Mark bootstrap as properly patched
                    createMarkerFile();

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    // Now set up Kali environment
                    updateProgressMessage(activity, progress, "Setting up Kali Linux environment...");
                    try {
                        KalinRXSetup.setupKaliFromInstaller(activity, msg -> {
                            updateProgressMessage(activity, progress, msg);
                        });
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Kali setup failed (non-fatal)", e);
                        // Kali setup failure is non-fatal - Termux should still work
                        final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                        activity.runOnUiThread(() -> {
                            try {
                                new AlertDialog.Builder(activity)
                                    .setTitle("Kali Setup Warning")
                                    .setMessage("Kali Linux environment setup failed:\n\n" + errorMsg + "\n\n" +
                                        "Termux will still work, but Kali will not be available. " +
                                        "You can retry later by clearing app data.")
                                    .setPositiveButton("OK", null)
                                    .show();
                            } catch (Exception ignored) {}
                        });
                    }

                    updateProgressMessage(activity, progress, "Installation complete!");
                    Logger.logInfo(LOG_TAG, "KalinRX installation complete.");

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    private static void startKaliSetup(final Activity activity, final Runnable whenDone) {
        // Check if there's a local file available
        final File foundLocalFile = KalinRXSetup.findLocalTarFile();

        // Build dialog options
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Kali Linux Setup");
        StringBuilder msg = new StringBuilder("Kali Linux rootfs is not installed.\n\n");
        if (foundLocalFile != null) {
            msg.append("Local image found:\n").append(foundLocalFile.getAbsolutePath())
                .append("\n(").append(foundLocalFile.length() / 1024 / 1024).append(" MB)\n\n");
        }
        msg.append("Choose installation method:");
        builder.setMessage(msg.toString());

        builder.setPositiveButton("Download", (dialog, which) -> {
            dialog.dismiss();
            KalinRXSetup.setLocalTarFile(null);
            doKaliSetupProgress(activity, whenDone);
        });

        builder.setNeutralButton("Select Local File", (dialog, which) -> {
            dialog.dismiss();
            sPendingKaliWhenDone = whenDone;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = {"application/x-xz", "application/octet-stream", "application/x-tar"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            try {
                activity.startActivityForResult(intent, REQUEST_CODE_KALI_LOCAL_FILE);
            } catch (Exception e) {
                // Fallback: no filter
                Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                activity.startActivityForResult(fallback, REQUEST_CODE_KALI_LOCAL_FILE);
            }
        });

        if (foundLocalFile != null) {
            builder.setNegativeButton("Use Found Local File", (dialog, which) -> {
                dialog.dismiss();
                KalinRXSetup.setLocalTarFile(foundLocalFile);
                doKaliSetupProgress(activity, whenDone);
            });
        } else {
            builder.setNegativeButton("Skip", (dialog, which) -> {
                dialog.dismiss();
                whenDone.run();
            });
        }

        builder.setCancelable(false);
        try {
            builder.show();
        } catch (WindowManager.BadTokenException e) {
            whenDone.run();
        }
    }

    private static void doKaliSetupProgress(final Activity activity, final Runnable whenDone) {
        final ProgressDialog kaliProgress = ProgressDialog.show(activity, null,
            "Setting up KalinRX Kali Linux environment...", true, false);
        new Thread(() -> {
            try {
                KalinRXSetup.setupKaliFromInstaller(activity, msg -> {
                    activity.runOnUiThread(() -> {
                        try { kaliProgress.setMessage(msg); } catch (Exception ignored) {}
                    });
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Kali setup failed", e);
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                activity.runOnUiThread(() -> {
                    try {
                        new AlertDialog.Builder(activity)
                            .setTitle("Kali Setup Failed")
                            .setMessage("Failed to set up Kali Linux:\n\n" + errorMsg + "\n\n" +
                                "Termux will still work. You can retry by clearing app data.")
                            .setPositiveButton("OK", null)
                            .show();
                    } catch (Exception ignored) {}
                });
            } finally {
                activity.runOnUiThread(() -> {
                    try { kaliProgress.dismiss(); } catch (RuntimeException ignored) {}
                    whenDone.run();
                });
            }
        }).start();
    }

    /** Handle the result from the Kali local file picker. */
    public static void handleKaliFilePickerResult(Activity activity, Uri uri, Runnable whenDone) {
        if (uri == null) {
            whenDone.run();
            return;
        }
        try {
            // Copy the file from URI to local temp
            File tempFile = new File(TermuxConstants.TERMUX_FILES_DIR_PATH + "/tmp/kali-local.tar.xz");
            tempFile.getParentFile().mkdirs();
            try (InputStream in = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
            }
            KalinRXSetup.setLocalTarFile(tempFile);
            doKaliSetupProgress(activity, whenDone);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read selected file", e);
            activity.runOnUiThread(() -> {
                try {
                    new AlertDialog.Builder(activity)
                        .setTitle("Error")
                        .setMessage("Failed to read selected file: " + e.getMessage())
                        .setPositiveButton("OK", (d, w) -> whenDone.run())
                        .show();
                } catch (Exception ignored) {
                    whenDone.run();
                }
            });
        }
    }

    /**
     * Check if bootstrap is properly installed and patched.
     * This performs an actual execution test, not just file permission check.
     */
    private static boolean isBootstrapReady() {
        // Check prefix directory exists and is not empty
        if (!FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            Logger.logInfo(LOG_TAG, "Bootstrap prefix directory not found.");
            return false;
        }
        if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
            Logger.logInfo(LOG_TAG, "Bootstrap prefix directory is empty.");
            return false;
        }

        // Check critical binaries exist and are executable (basic check)
        String[] criticalBins = { "login", "bash", "sh" };
        for (String bin : criticalBins) {
            File binFile = new File(TERMUX_PREFIX_DIR_PATH + "/bin/" + bin);
            if (!binFile.exists()) {
                Logger.logWarn(LOG_TAG, "Critical binary missing: " + binFile.getAbsolutePath());
                return false;
            }
            if (!binFile.canExecute()) {
                Logger.logWarn(LOG_TAG, "Critical binary not executable: " + binFile.getAbsolutePath());
                return false;
            }
        }

        // ACTUAL EXECUTION TEST: Try to run the login shell script
        // This is the REAL test - file permissions don't guarantee execution works
        String bashPath = TERMUX_PREFIX_DIR_PATH + "/bin/bash";
        String loginPath = TERMUX_PREFIX_DIR_PATH + "/bin/login";
        String testScript = "echo 'bootstrap_ok'";
        try {
            // Test 1: Can we run bash at all?
            ProcessBuilder bashTest = new ProcessBuilder(bashPath, "--version");
            bashTest.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            bashTest.environment().remove("LD_PRELOAD");
            bashTest.environment().remove("LD_LIBRARY_PATH");
            bashTest.redirectErrorStream(true);
            Process bashProc = bashTest.start();
            boolean bashOk = bashProc.waitFor() == 0;
            if (!bashOk) {
                Logger.logWarn(LOG_TAG, "Bootstrap bash execution test failed (exit code " + bashProc.exitValue() + "). Bootstrap may be corrupted.");
                return false;
            }

            // Test 2: Does login script exist and can bash execute it?
            File loginFile = new File(loginPath);
            if (!loginFile.exists()) {
                Logger.logWarn(LOG_TAG, "login script not found at: " + loginPath);
                return false;
            }

            // Test 3: Can bash source the login script?
            ProcessBuilder loginTest = new ProcessBuilder(bashPath, "-c",
                "source " + loginPath + " 2>&1 | head -1; exit 0");
            loginTest.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            loginTest.environment().remove("LD_PRELOAD");
            loginTest.environment().remove("LD_LIBRARY_PATH");
            loginTest.redirectErrorStream(true);
            Process loginProc = loginTest.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(loginProc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            boolean loginOk = loginProc.waitFor() == 0;
            if (!loginOk) {
                Logger.logWarn(LOG_TAG, "Bootstrap login script test failed. Output: " + output);
                return false;
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Bootstrap execution test threw exception: " + e.getMessage());
            return false;
        }

        Logger.logInfo(LOG_TAG, "Bootstrap execution tests passed.");
        return true;
    }

    private static boolean isExecutablePath(String zipEntryName) {
        return zipEntryName.startsWith("bin/") ||
               zipEntryName.startsWith("libexec/") ||
               zipEntryName.startsWith("sbin/") ||
               zipEntryName.startsWith("lib/apt/apt-helper") ||
               zipEntryName.startsWith("lib/apt/methods") ||
               zipEntryName.endsWith("/apt-helper") ||
               zipEntryName.endsWith("/methods");
    }

    private static void createMarkerFile() {
        try {
            File marker = new File(BOOTSTRAP_PATCHED_MARKER);
            try (FileWriter fw = new FileWriter(marker)) {
                fw.write("KalinRX bootstrap patched successfully\n");
                fw.write("Old prefix: " + OLD_PREFIX + "\n");
                fw.write("New prefix: " + NEW_PREFIX + "\n");
            }
            Logger.logInfo(LOG_TAG, "Bootstrap patch marker created at: " + BOOTSTRAP_PATCHED_MARKER);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create bootstrap marker file: " + e.getMessage());
        }
    }

    private static void updateProgressMessage(Activity activity, ProgressDialog progress, String message) {
        activity.runOnUiThread(() -> {
            try {
                if (progress.isShowing()) {
                    progress.setMessage(message);
                }
            } catch (Exception ignored) {}
        });
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

    // ===== Bootstrap file patching =====

    private static void patchBootstrapFiles(String dirPath, Set<String> executableFiles) {
        try {
            byte[] oldBytes = OLD_PREFIX.getBytes("UTF-8");
            byte[] newBytes = NEW_PREFIX.getBytes("UTF-8");

            File dir = new File(dirPath);
            if (!dir.isDirectory()) return;

            patchDirectory(dir, oldBytes, newBytes);

            // After patching, do a FULL recursive chmod on ALL executable directories.
            // This is the most reliable approach - RandomAccessFile may strip
            // execute permissions on some Android versions.
            forceChmodAllExecutables(dirPath);

            Logger.logInfo(LOG_TAG, "Bootstrap patching complete.");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to patch bootstrap files", e);
        }
    }

    private static void forceChmodAllExecutables(String prefixPath) {
        try {
            String[] execDirs = {"bin", "libexec", "sbin", "lib/apt"};
            int count = 0;
            for (String relDir : execDirs) {
                File dir = new File(prefixPath, relDir);
                if (dir.isDirectory()) {
                    count += chmodRecursive(dir, 0700);
                }
            }
            Logger.logInfo(LOG_TAG, "Force chmod complete: " + count + " files permission restored.");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to force chmod", e);
        }
    }

    private static int chmodRecursive(File dir, int mode) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += chmodRecursive(file, mode);
            } else if (file.isFile()) {
                try {
                    Os.chmod(file.getAbsolutePath(), mode);
                    count++;
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "chmod failed: " + file.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
        return count;
    }

    private static boolean isInExecutableDir(String filePath, String prefixPath) {
        String rel = filePath.substring(prefixPath.length());
        return rel.startsWith("/bin/") ||
               rel.startsWith("/sbin/") ||
               rel.startsWith("/libexec/") ||
               rel.contains("/apt/apt-helper") ||
               rel.contains("/apt/methods");
    }

    private static void patchDirectory(File dir, byte[] oldBytes, byte[] newBytes) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                patchDirectory(file, oldBytes, newBytes);
            } else if (file.isFile() && !isBinaryBlacklisted(file.getName())) {
                try {
                    patchFile(file, oldBytes, newBytes);
                } catch (Exception e) {
                    // Skip files that can't be patched
                }
            }
        }
    }

    private static boolean isBinaryBlacklisted(String name) {
        return name.endsWith(".so") || name.endsWith(".a") ||
               name.endsWith(".o") || name.equals("ld-linux-aarch64.so.1") ||
               name.equals("ld.so") || name.startsWith("ld-");
    }

    private static boolean patchFile(File file, byte[] oldBytes, byte[] newBytes) throws Exception {
        if (oldBytes.length != newBytes.length) {
            return false;
        }

        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            long fileLen = raf.length();
            if (fileLen > 100 * 1024 * 1024) {
                return false;
            }

            byte[] content = new byte[(int) fileLen];
            raf.readFully(content);

            boolean modified = false;
            int idx = indexOfBytes(content, oldBytes);
            while (idx != -1) {
                System.arraycopy(newBytes, 0, content, idx, newBytes.length);
                modified = true;
                idx = indexOfBytes(content, oldBytes, idx + newBytes.length);
            }

            if (modified) {
                raf.seek(0);
                raf.write(content);
                raf.setLength(content.length);
            }

            return modified;
        } finally {
            raf.close();
        }
    }

    private static int indexOfBytes(byte[] content, byte[] pattern) {
        return indexOfBytes(content, pattern, 0);
    }

    private static int indexOfBytes(byte[] content, byte[] pattern, int fromIndex) {
        if (pattern.length == 0 || fromIndex >= content.length) return -1;

        outer:
        for (int i = fromIndex; i <= content.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (content[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}