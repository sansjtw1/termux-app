# AnKali - Kali Linux Environment in Termux

## 项目概述

本项目将AnKali移植到Termux环境，更改包名为`com.kalinrx`，实现了在Termux中运行Kali Linux环境的功能。

## 主要修改

### 1. 包名更改
- 原包名: `com.termux`
- 新包名: `com.kalinrx`
- 所有相关模块的namespace都已更新

### 2. 项目结构

```
AnKali/
├── app/                           # 主应用模块
│   ├── src/main/
│   │   ├── java/com/kalinrx/     # Java源代码
│   │   ├── assets/                # 资源文件
│   │   │   ├── kali/              # Kali配置文件
│   │   │   ├── kali_init.sh       # 初始化脚本
│   │   │   └── start_kali.sh      # 启动脚本
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── termux-shared/                 # 共享库
├── terminal-emulator/              # 终端模拟器
├── terminal-view/                  # 终端视图
└── gradle/                        # Gradle配置
```

### 3. Kali环境集成

- 配置文件位于 `app/src/main/assets/kali/`
- 初始化脚本 `kali_init.sh` 自动设置Kali环境
- 支持proot运行Kali无需root

## 构建说明

### 环境要求

- Android SDK (API 24+)
- NDK (用于编译原生代码)
- Gradle 9.2.1+
- Java 8+

### 构建步骤

1. **克隆项目**
   ```bash
   git clone <repository-url>
   cd AnKali
   ```

2. **设置环境变量** (如需要代理)
   ```bash
   export http_proxy="http://proxy:port"
   export https_proxy="http://proxy:port"
   ```

3. **下载Kali镜像** (可选 - 首次运行时会自动下载)
   ```bash
   # 设置镜像URL
   export KALI_IMAGE_URL="https://github.dpik.top/https://github.com/sansjtw1/Ankali-app/releases/download/kali-arm64/kali-arm64.tar.xz"
   
   # 运行Gradle任务下载
   ./gradlew downloadKaliArm64
   ```

4. **构建Debug APK**
   ```bash
   ./gradlew assembleDebug
   ```

5. **构建Release APK**
   ```bash
   ./gradlew assembleRelease
   ```

### 代理问题解决

如果在下载过程中遇到网络问题，可以：

1. **使用国内镜像** - 编辑 `gradle/wrapper/gradle-wrapper.properties`:
   ```properties
   distributionUrl=https\://mirrors.aliyun.com/gradle/gradle-9.2.1-bin.zip
   ```

2. **手动下载Gradle**:
   ```bash
   wget https://services.gradle.org/distributions/gradle-9.2.1-bin.zip
   mv gradle-9.2.1-bin.zip ~/.gradle/wrapper/dists/gradle-9.2.1-bin/
   ```

3. **禁用代理**:
   ```bash
   unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
   ```

## 下载Kali镜像

Kali镜像需要单独下载。下载链接：

- **GitHub (通过代理)**:
  `https://github.dpik.top/https://github.com/sansjtw1/Ankali-app/releases/download/kali-arm64/kali-arm64.tar.xz`

- **直链**:
  `https://github.com/sansjtw1/Ankali-app/releases/download/kali-arm64/kali-arm64.tar.xz`

下载后，将文件放置到:
- 对于APK内置: `app/src/main/assets/kali-arm64.tar.xz`
- 对于运行时下载: 应用首次启动时会自动从上述链接下载

## 应用功能

1. **自动启动Kali环境** - 打开应用后自动进入Kali环境
2. **内置proot支持** - 无需root即可运行
3. **基本工具包** - 包含ls, cp等常用命令
4. **内置包获取** - 大部分包通过内置形式获取

## APK输出位置

构建完成后，APK文件位于:

- Debug: `app/build/outputs/apk/debug/ankali_*.apk`
- Release: `app/build/outputs/apk/release/ankali_*.apk`

## 注意事项

1. **存储空间**: Kali镜像解压后约2GB，确保设备有足够空间
2. **Android版本**: 建议Android 7.0以上
3. **首次运行**: 首次运行时会自动解压/下载Kali镜像
4. **网络问题**: 如遇下载问题，可手动下载镜像后放置到相应位置

## 许可证

本项目基于Termux和AnKali项目，遵循其各自的许可证。
