# Termux 包名修改总结

## 修改概述

已成功将 Termux 应用的包名从 `com.termux` 更改为 `com.readboy.termux`。

## 修改的文件

### 1. 核心配置文件

#### app/build.gradle
- 修改 `applicationId` 从 `com.termux` 到 `com.readboy.termux`
- 修改 `manifestPlaceholders.TERMUX_PACKAGE_NAME` 从 `com.termux` 到 `com.readboy.termux`

#### app/src/main/AndroidManifest.xml
- 修改 `package` 属性从 `com.termux` 到 `com.readboy.termux`

#### termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
- 修改 `TERMUX_PACKAGE_NAME` 常量从 `com.termux` 到 `com.readboy.termux`

### 2. 资源文件

#### app/src/main/res/values/strings.xml
- 修改 `<!ENTITY TERMUX_PACKAGE_NAME>` 从 `com.termux` 到 `com.readboy.termux`

#### app/src/main/res/xml/shortcuts.xml
- 修改所有 `android:targetPackage` 从 `com.termux` 到 `com.readboy.termux`
- 修改所有 `android:targetClass` 从 `com.termux` 到 `com.readboy.termux`

#### app/src/main/res/xml/root_preferences.xml
- 修改所有 `app:fragment` 属性的类名从 `com.termux` 到 `com.readboy.termux`

#### app/src/main/res/xml/termux_preferences.xml
- 修改所有 `app:fragment` 属性的类名从 `com.termux` 到 `com.readboy.termux`

#### app/src/main/res/xml/termux_api_preferences.xml
- 修改 `app:fragment` 属性的类名从 `com.termux` 到 `com.readboy.termux`

#### app/src/main/res/xml/termux_float_preferences.xml
- 修改 `app:fragment` 属性的类名从 `com.termux` 到 `com.readboy.termux`

#### app/src/main/res/xml/termux_tasker_preferences.xml
- 修改 `app:fragment` 属性的类名从 `com.termux` 到 `com.readboy.termux`

#### app/src/main/res/xml/termux_widget_preferences.xml
- 修改 `app:fragment` 属性的类名从 `com.termux` 到 `com.readboy.termux`

### 3. 新增文件

#### PROOT_SOLUTION.md
- 提供了使用 proot-distro 解决改包后 apt 使用问题的详细说明

## 未修改的文件

### Java 源代码包名
- 所有 Java 源代码的包名（如 `com.termux.app`、`com.termux.shared` 等）保持不变
- 这是根据 TermuxConstants.java 中的注释说明，修改这些包名会破坏很多东西

### GitHub Actions 配置
- `.github/workflows/debug_build.yml` - 无需修改
- `.github/workflows/attach_debug_apks_to_release.yml` - 无需修改
- `.github/workflows/run_tests.yml` - 无需修改
- 其他工作流文件 - 无需修改

这些配置文件不包含硬编码的包名，会自动使用 build.gradle 中的配置。

### 其他模块的 AndroidManifest.xml
- `terminal-emulator/src/main/AndroidManifest.xml` - 保持 `com.termux.terminal`
- `terminal-view/src/main/AndroidManifest.xml` - 保持 `com.termux.view`
- `termux-shared/src/main/AndroidManifest.xml` - 保持 `com.termux.shared`

这些是 Java 包名，用于代码结构，不是应用包名。

## 后续步骤

### 1. 推送到 GitHub

```bash
cd /data/data/com.termux/files/home/object/termux-app
git add .
git commit -m "feat: 更改包名为 com.readboy.termux

- 修改 applicationId 为 com.readboy.termux
- 修改 manifestPlaceholders.TERMUX_PACKAGE_NAME
- 修改 AndroidManifest.xml 中的 package 属性
- 修改 TermuxConstants.java 中的 TERMUX_PACKAGE_NAME
- 修改所有资源文件中的包名引用
- 添加 PROOT_SOLUTION.md 文档说明使用 proot-distro 的解决方案"
git push origin master
```

### 2. 使用 GitHub Actions 构建

推送到 GitHub 后，GitHub Actions 会自动构建 APK。构建完成后，可以从以下位置下载：

- **GitHub Releases**：创建一个新的 release，GitHub Actions 会自动附加 APK
- **GitHub Actions Artifacts**：从 debug_build.yml 工作流的 artifacts 中下载

### 3. 解决 apt 使用问题

由于包名改变，$PREFIX 路径从 `/data/data/com.termux/files/usr` 变为 `/data/data/com.readboy.termux/files/usr`，原始的 bootstrap zip 文件中的二进制文件包含硬编码的路径，会导致 apt 无法正常工作。

推荐使用 **proot-distro** 方案（详见 PROOT_SOLUTION.md）：

```bash
# 在 Termux 中安装 proot-distro
pkg update
pkg install proot-distro

# 安装 Linux 发行版
proot-distro install ubuntu

# 进入 Linux 发行版
proot-distro login ubuntu

# 使用包管理器
apt update
apt install <package_name>
```

## 注意事项

### 1. 应用签名

- 修改包名后，应用会使用新的签名（testkey_untrusted.jks）
- 如果设备上已安装原始 Termux，需要先卸载
- 不能与原始 Termux 应用同时安装（因为 sharedUserId 不同）

### 2. 插件兼容性

- 如果使用原始 Termux 的插件（如 Termux:API），需要重新编译这些插件
- 或者使用 proot-distro 方案，避免依赖原始插件

### 3. 数据迁移

- 原始 Termux 的数据在 `/data/data/com.termux/files`
- 新应用的数据在 `/data/data/com.readboy.termux/files`
- 数据不会自动迁移，需要手动复制或重新安装

### 4. Bootstrap 问题

- 原始的 bootstrap zip 文件可能无法正常工作
- 推荐使用 proot-distro 方案
- 或者重新编译 bootstrap zip 文件（需要 termux-packages 仓库）

## 构建和测试

### 本地构建（可选）

```bash
cd /data/data/com.termux/files/home/object/termux-app
./gradlew assembleDebug
```

构建的 APK 位于：
- `app/build/outputs/apk/debug/termux-app_<version>-<variant>-github-debug_<abi>.apk`

### 安装和测试

```bash
# 安装 APK
adb install app/build/outputs/apk/debug/termux-app_*_universal.apk

# 运行应用并测试基本功能
# 检查应用是否能正常启动
# 检查终端是否能正常工作
# 检查设置是否能正常打开
```

## 参考资料

- [Termux README - Forking](https://github.com/termux/termux-app#forking)
- [TermuxConstants.java Javadoc](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java)
- [Proot-Distro GitHub](https://github.com/termux/proot-distro)
- [Building Bootstrap](https://github.com/termux/termux-packages/wiki/For-maintainers#build-bootstrap-archives)

## 联系方式

如有问题，请通过以下方式联系：
- GitHub Issues: https://github.com/OTFiles/termux-app/issues
- Email: support@termux.dev（原始 Termux 支持）