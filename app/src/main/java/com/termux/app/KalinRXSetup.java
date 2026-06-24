package com.termux.app;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class KalinRXSetup {

    private static final String LOG_TAG = "KalinRXSetup";

    private static final String KALI_ROOTFS_URL =
        "https://github.com/sansjtw1/Ankali-app/releases/download/kali-arm64/kali-arm64.tar.xz";

    private static final String FILES_DIR = TermuxConstants.TERMUX_FILES_DIR_PATH;
    private static final String PREFIX_DIR = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    private static final String HOME_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH;
    private static final String KALI_DIR = FILES_DIR + "/kali-arm64";
    private static final String TAR_XZ_FILE = FILES_DIR + "/kali-arm64.tar.xz";
    private static final String PROOT_BIN = FILES_DIR + "/bin/proot";
    private static final String LOADER_BIN = FILES_DIR + "/bin/loader";

    public static boolean isKaliInstalled() {
        return new File(KALI_DIR).exists() && new File(KALI_DIR + "/.kali-config/kali-run").exists();
    }

    public static boolean isTarDownloaded() {
        return new File(TAR_XZ_FILE).exists();
    }

    public static void setupKaliEnvironment(Context context) {
        Logger.logInfo(LOG_TAG, "Setting up KalinRX Kali Linux environment...");

        try {
            new File(FILES_DIR + "/bin").mkdirs();
            new File(FILES_DIR + "/tmp").mkdirs();

            extractProotBinaries(context);
            extractKaliConfigAssets(context);

            File tarFile = new File(TAR_XZ_FILE);
            File kaliDir = new File(KALI_DIR);

            if (!kaliDir.exists() || !new File(KALI_DIR + "/.kali-config/kali-run").exists()) {
                if (!tarFile.exists()) {
                    Logger.logInfo(LOG_TAG, "Kali rootfs not found. Downloading to local storage: " + TAR_XZ_FILE);
                    downloadKaliRootfs(tarFile);
                }

                if (tarFile.exists() && tarFile.length() > 0) {
                    Logger.logInfo(LOG_TAG, "Extracting Kali rootfs from local storage...");
                    extractKaliRootfs(tarFile);
                }
            }

            Logger.logInfo(LOG_TAG, "KalinRX Kali Linux environment setup complete.");

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set up Kali environment", e);
        }
    }

    public static void createKaliAutoLaunch(Context context) {
        try {
            new File(HOME_DIR).mkdirs();

            File bashrcFile = new File(HOME_DIR, ".bashrc");
            String bashrcContent =
                "PROOT=\"" + PROOT_BIN + "\"\n" +
                "KALI_DIR=\"" + KALI_DIR + "\"\n" +
                "\n" +
                "kalinrx_launch() {\n" +
                "    if [ ! -f \"$PROOT\" ]; then\n" +
                "        return 1\n" +
                "    fi\n" +
                "    if [ ! -d \"$KALI_DIR\" ] || [ ! -f \"$KALI_DIR/.kali-config/kali-run\" ]; then\n" +
                "        echo \"[KalinRX] Kali rootfs not ready, running regular shell.\"\n" +
                "        return 1\n" +
                "    fi\n" +
                "    echo \"[KalinRX] Starting Kali Linux Environment...\"\n" +
                "    exec \"$PROOT\" --link2symlink -0 \\\n" +
                "        -r \"$KALI_DIR\" \\\n" +
                "        -b /dev -b /proc -b /sys \\\n" +
                "        -b \"$KALI_DIR/home:/dev/shm\" \\\n" +
                "        -w /root \\\n" +
                "        /usr/bin/env -i \\\n" +
                "        HOME=/root \\\n" +
                "        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
                "        TERM=xterm-256color \\\n" +
                "        LANG=en_US.UTF-8 \\\n" +
                "        /.kali-config/kali-run\n" +
                "}\n" +
                "\n" +
                "kalinrx_launch\n";

            try (FileWriter fw = new FileWriter(bashrcFile)) {
                fw.write(bashrcContent);
            }
            bashrcFile.setExecutable(true);
            Logger.logInfo(LOG_TAG, "Created .bashrc for Kali auto-launch at: " + bashrcFile.getAbsolutePath());

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create .bashrc", e);
        }
    }

    private static void extractKaliConfigAssets(Context context) {
        try {
            String[] configFiles = {
                "kali-arm64/.kali-config/kali-run",
                "kali-arm64/.kali-config/Ankali.yaml",
                "kali-arm64/.kali-config/linux/ankali",
                "kali-arm64/root/.zshrc"
            };

            for (String assetPath : configFiles) {
                File destFile = new File(FILES_DIR, assetPath);
                if (!destFile.exists()) {
                    destFile.getParentFile().mkdirs();
                    try (InputStream is = context.getAssets().open(assetPath);
                         FileOutputStream fos = new FileOutputStream(destFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }
                    if (assetPath.endsWith("kali-run") || assetPath.endsWith("ankali")) {
                        destFile.setExecutable(true);
                    }
                }
            }
            Logger.logInfo(LOG_TAG, "Kali config files extracted from assets.");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to extract Kali config assets", e);
        }
    }

    private static void extractProotBinaries(Context context) {
        try {
            File prootDest = new File(PROOT_BIN);
            if (!prootDest.exists()) {
                File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
                File prootLib = new File(nativeLibDir, "libproot.so");
                File loaderLib = new File(nativeLibDir, "libloader.so");
                File loader32Lib = new File(nativeLibDir, "libloader32.so");

                if (prootLib.exists()) {
                    copyFile(prootLib, prootDest);
                    prootDest.setExecutable(true);
                    Logger.logInfo(LOG_TAG, "Extracted proot binary.");
                }

                File loaderDest = new File(LOADER_BIN);
                if (loaderLib.exists() && !loaderDest.exists()) {
                    copyFile(loaderLib, loaderDest);
                    loaderDest.setExecutable(true);
                }

                File loader32Dest = new File(FILES_DIR + "/bin/loader32");
                if (loader32Lib.exists() && !loader32Dest.exists()) {
                    copyFile(loader32Lib, loader32Dest);
                    loader32Dest.setExecutable(true);
                }
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to extract proot binaries", e);
        }
    }

    private static void downloadKaliRootfs(File destFile) throws Exception {
        URL url = new URL(KALI_ROOTFS_URL);
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(300000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "KalinRX/1.0");

            int responseCode = connection.getResponseCode();
            Logger.logInfo(LOG_TAG, "Download response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                String newUrl = connection.getHeaderField("Location");
                Logger.logInfo(LOG_TAG, "Redirecting to: " + newUrl);
                connection.disconnect();
                connection = (HttpURLConnection) new URL(newUrl).openConnection(java.net.Proxy.NO_PROXY);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(300000);
                connection.setRequestProperty("User-Agent", "KalinRX/1.0");
            }

            long totalSize = connection.getContentLengthLong();
            Logger.logInfo(LOG_TAG, "Downloading Kali rootfs to local storage (" + (totalSize / 1024 / 1024) + " MB)...");

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (downloaded % (1024 * 1024 * 50) == 0) {
                        Logger.logInfo(LOG_TAG, "Downloaded: " + (downloaded / 1024 / 1024) + " MB / " + (totalSize / 1024 / 1024) + " MB");
                    }
                }
            }

            Logger.logInfo(LOG_TAG, "Kali rootfs downloaded to local storage: " + destFile.getAbsolutePath());

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void extractKaliRootfs(File tarFile) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            PREFIX_DIR + "/bin/bash", "-c",
            "cd " + FILES_DIR + " && " +
            PREFIX_DIR + "/bin/xz -d -c " + TAR_XZ_FILE + " | " +
            PREFIX_DIR + "/bin/tar -xvf - > /dev/null 2>&1 && " +
            "chmod 777 -R " + KALI_DIR + "/.kali-config"
        );

        pb.environment().put("HOME", HOME_DIR);
        pb.environment().put("PATH", PREFIX_DIR + "/bin:" + PREFIX_DIR + "/bin/applets");
        pb.environment().put("LD_LIBRARY_PATH", PREFIX_DIR + "/lib");
        pb.directory(new File(FILES_DIR));

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            Logger.logError(LOG_TAG, "Failed to extract Kali rootfs. Exit code: " + exitCode);
        } else {
            Logger.logInfo(LOG_TAG, "Kali rootfs extracted successfully to: " + KALI_DIR);
        }
    }

    private static void copyFile(File source, File dest) throws Exception {
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
