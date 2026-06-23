package com.termux.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Kali 环境管理器
 * 负责解压和启动 Kali Linux 环境
 */
public class KaliManager {
    private static final String TAG = "KaliManager";
    private static final String KALI_ROOTFS_ASSET = "kali-arm64.tar.xz";
    private static final String KALI_INSTALL_DIR = "kali-arm64";
    
    private final Context context;
    private final File filesDir;
    
    public KaliManager(Context context) {
        this.context = context;
        this.filesDir = context.getFilesDir();
    }
    
    /**
     * 检查 Kali 环境是否已安装
     */
    public boolean isKaliInstalled() {
        File kaliDir = new File(filesDir, KALI_INSTALL_DIR);
        return kaliDir.exists() && kaliDir.isDirectory() && 
               new File(kaliDir, "bin/bash").exists();
    }
    
    /**
     * 获取 Kali 安装目录
     */
    public File getKaliInstallDir() {
        return new File(filesDir, KALI_INSTALL_DIR);
    }
    
    /**
     * 初始化 Kali 环境，包括提取 proot 二进制文件
     */
    public boolean initializeEnvironment() {
        try {
            // 复制 proot 到 usr/bin/proot
            File prootDest = new File(filesDir, "usr/bin/proot");
            if (!prootDest.exists()) {
                prootDest.getParentFile().mkdirs();
                copyAssetToFile("proot/proot", prootDest);
                prootDest.setExecutable(true, false);
                Log.i(TAG, "Copied proot to " + prootDest.getAbsolutePath());
            }
            
            // 复制 loader 到 usr/libexec/proot/loader
            File loaderDest = new File(filesDir, "usr/libexec/proot/loader");
            if (!loaderDest.exists()) {
                loaderDest.getParentFile().mkdirs();
                copyAssetToFile("proot/loader", loaderDest);
                loaderDest.setExecutable(true, false);
                Log.i(TAG, "Copied loader to " + loaderDest.getAbsolutePath());
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize environment", e);
            return false;
        }
    }
    
    /**
     * 解压 Kali rootfs
     * 注意：这个方法需要在后台线程执行
     */
    public boolean extractKaliRootfs() {
        if (isKaliInstalled()) {
            Log.i(TAG, "Kali already installed");
            return true;
        }
        
        Log.i(TAG, "Starting Kali rootfs extraction...");
        File kaliDir = getKaliInstallDir();
        
        try {
            // 创建安装目录
            if (!kaliDir.mkdirs()) {
                Log.e(TAG, "Failed to create Kali directory");
                return false;
            }
            
            // 从 assets 复制 tar.xz 文件到临时目录
            File tempTar = new File(filesDir, "kali-rootfs.tar.xz");
            copyAssetToFile(KALI_ROOTFS_ASSET, tempTar);
            
            // 使用系统命令解压
            String[] cmd = {
                "tar", "-xJf", tempTar.getAbsolutePath(), 
                "-C", kaliDir.getAbsolutePath()
            };
            
            Process process = Runtime.getRuntime().exec(cmd);
            int exitCode = process.waitFor();
            
            // 删除临时文件
            tempTar.delete();
            
            if (exitCode == 0) {
                Log.i(TAG, "Kali rootfs extracted successfully");
                // 设置执行权限
                setExecutablePermissions(kaliDir);
                return true;
            } else {
                Log.e(TAG, "Extraction failed with exit code: " + exitCode);
                return false;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract Kali rootfs", e);
            return false;
        }
    }
    
    /**
     * 从 assets 复制文件到目标文件
     */
    private void copyAssetToFile(String assetName, File destFile) throws Exception {
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
    
    /**
     * 设置 Kali 目录中二进制文件的执行权限
     */
    private void setExecutablePermissions(File kaliDir) {
        try {
            // 设置 bin 目录下的文件可执行
            File binDir = new File(kaliDir, "bin");
            if (binDir.exists()) {
                File[] files = binDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.setExecutable(true, false);
                    }
                }
            }
            
            // 设置 usr/bin 目录
            File usrBinDir = new File(kaliDir, "usr/bin");
            if (usrBinDir.exists()) {
                File[] files = usrBinDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.setExecutable(true, false);
                    }
                }
            }
            
            // 设置 kali-config 中的脚本
            File kaliConfigDir = new File(kaliDir, ".kali-config");
            if (kaliConfigDir.exists()) {
                File kaliRun = new File(kaliConfigDir, "kali-run");
                if (kaliRun.exists()) {
                    kaliRun.setExecutable(true, false);
                }
                File kaliConf = new File(kaliConfigDir, "kali_conf");
                if (kaliConf.exists()) {
                    kaliConf.setExecutable(true, false);
                }
                File kaliConfCn = new File(kaliConfigDir, "kali_conf_cn");
                if (kaliConfCn.exists()) {
                    kaliConfCn.setExecutable(true, false);
                }
            }
            
            Log.i(TAG, "Executable permissions set successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set executable permissions", e);
        }
    }
    
    /**
     * 获取启动 Kali 环境的命令
     */
    public String[] getKaliStartCommand() {
        File kaliDir = getKaliInstallDir();
        File prootBin = new File(filesDir, "usr/bin/proot");
        
        // 构建 PRoot 命令
        return new String[]{
            prootBin.getAbsolutePath(),
            "--link2symlink",
            "-0",
            "-r", kaliDir.getAbsolutePath(),
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=en_US.UTF-8",
            "/bin/bash", "--login",
            "-c", "/.kali-config/kali-run"
        };
    }
}
