package com.kalinrx.app;

import android.content.Context;
import android.util.Log;

import com.kalinrx.shared.logger.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * KaliEnvironment - Handles Kali Linux environment initialization and startup
 */
public class KaliEnvironment {
    
    private static final String TAG = "KaliEnvironment";
    private static final String KALI_DIR = "kali-arm64";
    private static final String KALI_TAR_XZ = "kali-arm64.tar.xz";
    private static final String START_SCRIPT = "start_kali.sh";
    private static final String KALI_RUN = ".kali-config/kali-run";
    private static final String KALI_RUN_C = ".kali-config/kali-run.c";
    
    /**
     * Initialize the Kali environment
     */
    public static boolean initialize(Context context) {
        String dataDir = context.getFilesDir().getAbsolutePath();
        String kaliDir = dataDir + File.separator + KALI_DIR;
        
        Logger.logInfo(TAG, "Initializing Kali environment at: " + kaliDir);
        
        File kaliDirectory = new File(kaliDir);
        
        // Check if Kali directory already exists
        if (kaliDirectory.exists() && kaliDirectory.isDirectory()) {
            Logger.logInfo(TAG, "Kali directory already exists");
            return true;
        }
        
        // Check if compressed tarball exists
        File tarball = new File(dataDir, KALI_TAR_XZ);
        if (tarball.exists()) {
            Logger.logInfo(TAG, "Found compressed Kali image, extracting...");
            return extractKali(context, tarball, kaliDirectory);
        }
        
        // Check if we have the compressed file in assets
        return extractFromAssets(context, kaliDirectory);
    }
    
    /**
     * Extract Kali from assets
     */
    private static boolean extractFromAssets(Context context, File kaliDir) {
        try {
            // Create kali directory
            if (!kaliDir.exists()) {
                kaliDir.mkdirs();
            }
            
            // Extract .kali-config from assets
            String[] configFiles = {
                ".kali-config",
                START_SCRIPT,
                "kali.c"
            };
            
            for (String file : configFiles) {
                extractAsset(context, file, kaliDir.getParent());
            }
            
            Logger.logInfo(TAG, "Kali configuration extracted successfully");
            return true;
        } catch (Exception e) {
            Logger.logError(TAG, "Failed to extract Kali from assets: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Extract a single asset file
     */
    private static void extractAsset(Context context, String assetPath, String destDir) throws IOException {
        File outFile = new File(destDir, assetPath);
        
        if (outFile.exists()) {
            Logger.logDebug(TAG, "File already exists: " + outFile.getAbsolutePath());
            return;
        }
        
        // Create parent directories
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        
        try (InputStream is = context.getAssets().open("kali/" + assetPath);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            
            byte[] buffer = new byte[8192];
            int count;
            while ((count = is.read(buffer)) != -1) {
                fos.write(buffer, 0, count);
            }
            
            fos.flush();
            Logger.logDebug(TAG, "Extracted: " + outFile.getAbsolutePath());
        } catch (IOException e) {
            Logger.logDebug(TAG, "Asset not found or already exists: " + assetPath);
        }
    }
    
    /**
     * Extract compressed Kali image
     */
    private static boolean extractKali(Context context, File tarball, File kaliDir) {
        // Implementation would use command-line tar or built-in extraction
        Logger.logInfo(TAG, "Extracting Kali from compressed file...");
        
        try {
            Runtime.getRuntime().exec(new String[]{
                "sh", "-c",
                "mkdir -p " + kaliDir.getAbsolutePath() +
                " && cd " + kaliDir.getAbsolutePath() +
                " && tar -xf " + tarball.getAbsolutePath()
            }).waitFor();
            
            // Remove tarball after extraction
            tarball.delete();
            
            return true;
        } catch (Exception e) {
            Logger.logError(TAG, "Failed to extract Kali: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the path to the kali-run executable
     */
    public static String getKaliRunPath(Context context) {
        return context.getFilesDir().getAbsolutePath() + File.separator + KALI_DIR + File.separator + KALI_RUN;
    }
    
    /**
     * Get the path to the start script
     */
    public static String getStartScriptPath(Context context) {
        return context.getFilesDir().getAbsolutePath() + File.separator + START_SCRIPT;
    }
    
    /**
     * Check if Kali environment is ready
     */
    public static boolean isReady(Context context) {
        File kaliDir = new File(context.getFilesDir(), KALI_DIR);
        File kaliRun = new File(kaliDir, KALI_RUN);
        return kaliDir.exists() && kaliRun.exists();
    }
    
    /**
     * Get the default shell command to start Kali
     */
    public static String getStartCommand(Context context) {
        String kaliDir = context.getFilesDir().getAbsolutePath() + File.separator + KALI_DIR;
        return "cd " + kaliDir + " && sh .profile && cd . && exec bash";
    }
}
