package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Ankali bootstrap (Kali Linux rootfs) if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";
    private static final String KALI_ROOTFS_ASSET = "kali-arm64.tar.xz";
    private static final String PROOT_ASSET = "proot";
    private static final String LOADER_ASSET = "loader";

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

        // Check if Kali rootfs is already installed
        String kaliRootfsPath = activity.getFilesDir() + "/kali-arm64";
        String prootBinPath = activity.getFilesDir() + "/bin/proot";
        
        if (FileUtils.directoryFileExists(kaliRootfsPath, true) && FileUtils.fileExists(prootBinPath, true)) {
            Logger.logInfo(LOG_TAG, "Kali rootfs and proot already installed");
            whenDone.run();
            return;
        }

        // Show progress dialog and install Kali rootfs
        final ProgressDialog progress = ProgressDialog.show(activity, null, 
            activity.getString(R.string.bootstrap_installer_body), true, false);
        
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing Ankali bootstrap (Kali Linux)");
                    
                    Error error;
                    String filesDir = activity.getFilesDir().getAbsolutePath();
                    String binDir = filesDir + "/bin";
                    String tmpDir = filesDir + "/tmp";
                    String kaliDir = filesDir + "/kali-arm64";
                    
                    // Create necessary directories
                    error = FileUtils.createDirectoryFile(binDir);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }
                    
                    error = FileUtils.createDirectoryFile(tmpDir);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }
                    
                    // Extract proot binary
                    Logger.logInfo(LOG_TAG, "Extracting proot binary");
                    error = extractAsset(activity, PROOT_ASSET, binDir + "/proot");
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }
                    
                    // Extract loader binary
                    Logger.logInfo(LOG_TAG, "Extracting loader binary");
                    error = extractAsset(activity, LOADER_ASSET, binDir + "/loader");
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }
                    
                    // Set execute permissions
                    Os.chmod(binDir + "/proot", 0700);
                    Os.chmod(binDir + "/loader", 0700);
                    
                    // Extract Kali rootfs
                    Logger.logInfo(LOG_TAG, "Extracting Kali rootfs (this may take a while)");
                    error = extractKaliRootfs(activity, kaliDir);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }
                    
                    // Create startup script
                    Logger.logInfo(LOG_TAG, "Creating startup script");
                    error = createStartupScript(activity);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }
                    
                    Logger.logInfo(LOG_TAG, "Ankali bootstrap installed successfully");
                    activity.runOnUiThread(whenDone);
                    
                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, 
                        Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed
                        }
                    });
                }
            }
        }.start();
    }
    
    private static Error extractAsset(Context context, String assetName, String destPath) {
        try {
            AssetManager assetManager = context.getAssets();
            InputStream in = assetManager.open(assetName);
            OutputStream out = new FileOutputStream(destPath);
            
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            
            in.close();
            out.flush();
            out.close();
            
            return null;
        } catch (Exception e) {
            return new Error("extractAsset", "Failed to extract asset: " + assetName, e.getMessage());
        }
    }
    
    private static Error extractKaliRootfs(Context context, String destDir) {
        try {
            AssetManager assetManager = context.getAssets();
            InputStream in = assetManager.open(KALI_ROOTFS_ASSET);
            
            // Create destination directory
            FileUtils.createDirectoryFile(destDir);
            
            // Extract tar.xz using system tar command
            String tempFile = context.getFilesDir() + "/tmp/" + KALI_ROOTFS_ASSET;
            OutputStream out = new FileOutputStream(tempFile);
            
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            
            in.close();
            out.flush();
            out.close();
            
            // Extract using tar
            ProcessBuilder pb = new ProcessBuilder("tar", "-xJf", tempFile, "-C", destDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Logger.logInfo(LOG_TAG, "tar: " + line);
            }
            
            int exitCode = process.waitFor();
            
            // Clean up temp file
            FileUtils.deleteFile(tempFile);
            
            if (exitCode != 0) {
                return new Error("extractKaliRootfs", "tar extraction failed with exit code: " + exitCode, "");
            }
            
            return null;
        } catch (Exception e) {
            return new Error("extractKaliRootfs", "Failed to extract Kali rootfs", e.getMessage());
        }
    }
    
    private static Error createStartupScript(Context context) {
        try {
            String filesDir = context.getFilesDir().getAbsolutePath();
            String scriptPath = filesDir + "/home/start-kali.sh";
            
            // Create home directory if it doesn't exist
            FileUtils.createDirectoryFile(filesDir + "/home");
            
            String script = "#!/bin/bash\n" +
                "# Ankali startup script - Auto-enter Kali Linux environment\n" +
                "export PROOT_TMP_DIR=" + filesDir + "/tmp\n" +
                "export PROOT_LOADER=" + filesDir + "/bin/loader\n" +
                "\n" +
                "cd " + filesDir + "\n" +
                "\n" +
                "# Set permissions for kali-config\n" +
                "chmod -R 777 " + filesDir + "/kali-arm64/.kali-config 2>/dev/null\n" +
                "\n" +
                "# Start Kali Linux using proot\n" +
                "exec " + filesDir + "/bin/proot \\\n" +
                "  --link2symlink \\\n" +
                "  -0 \\\n" +
                "  -r " + filesDir + "/kali-arm64 \\\n" +
                "  -b /dev \\\n" +
                "  -b /proc \\\n" +
                "  -b " + filesDir + "/home:/dev/shm \\\n" +
                "  -w /root \\\n" +
                "  /usr/bin/env -i \\\n" +
                "  HOME=/root \\\n" +
                "  PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin \\\n" +
                "  TERM=$TERM \\\n" +
                "  /.kali-config/kali-run\n";
            
            FileOutputStream out = new FileOutputStream(scriptPath);
            out.write(script.getBytes());
            out.close();
            
            Os.chmod(scriptPath, 0700);
            
            // Also create a .bashrc that auto-starts Kali if not already in it
            String bashrcPath = filesDir + "/home/.bashrc";
            String bashrc = "# Auto-start Ankali environment\n" +
                "if [ ! -f /.kali-config/kali-run ]; then\n" +
                "  exec " + filesDir + "/home/start-kali.sh\n" +
                "fi\n";
            
            FileOutputStream bashrcOut = new FileOutputStream(bashrcPath);
            bashrcOut.write(bashrc.getBytes());
            bashrcOut.close();
            
            return null;
        } catch (Exception e) {
            return new Error("createStartupScript", "Failed to create startup script", e.getMessage());
        }
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
                        FileUtils.deleteFile(TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed
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

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

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
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
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
}
