#!/bin/bash
# Proot Bootstrap 配置脚本
# 用于在 bootstrap 中添加 proot 支持和路径映射

set -e

BOOTSTRAP_DIR="${1:-.}"
ORIGINAL_PREFIX="/data/data/com.termux/files/usr"
NEW_PREFIX="/data/data/com.readboy.termux/files/usr"

echo "=========================================="
echo "Proot Bootstrap Configuration"
echo "=========================================="
echo "Bootstrap directory: ${BOOTSTRAP_DIR}"
echo "Original prefix: ${ORIGINAL_PREFIX}"
echo "New prefix: ${NEW_PREFIX}"
echo "=========================================="

# 检查 bootstrap 目录
if [ ! -d "${BOOTSTRAP_DIR}" ]; then
  echo "Error: Bootstrap directory not found: ${BOOTSTRAP_DIR}"
  exit 1
fi

# 创建必要的目录
mkdir -p "${BOOTSTRAP_DIR}/etc"
mkdir -p "${BOOTSTRAP_DIR}/usr/bin"

# 1. 创建 .bashrc 配置
echo "Creating .bashrc with proot configuration..."
cat > "${BOOTSTRAP_DIR}/etc/bash.bashrc" << 'BASHRC_EOF'
# Proot 配置 - 自动路径映射
# 此配置允许使用原始包名的软件包

if [ -z "$PROOT_ACTIVE" ]; then
    export PROOT_ACTIVE=1
    export ORIGINAL_PREFIX="/data/data/com.termux/files/usr"
    export NEW_PREFIX="/data/data/com.readboy.termux/files/usr"

    # Proot 包装函数 - 使用 proot 运行命令并绑定路径
    proot_wrap() {
        if command -v proot >/dev/null 2>&1; then
            proot -b "${NEW_PREFIX}:${ORIGINAL_PREFIX}" "$@"
        else
            # 如果 proot 不可用，直接运行命令
            "$@"
        fi
    }

    # 常用命令的别名 - 自动使用 proot 包装
    alias apt='proot_wrap apt'
    alias apt-get='proot_wrap apt-get'
    alias dpkg='proot_wrap dpkg'
    alias apt-cache='proot_wrap apt-cache'
    alias apt-key='proot_wrap apt-key'
fi

# 设置 PATH
export PATH="/data/data/com.readboy.termux/files/usr/bin:/data/data/com.readboy.termux/files/usr/sbin:$PATH"

# 设置其他环境变量
export HOME="/data/data/com.readboy.termux/files/home"
export TERMUX_PREFIX="/data/data/com.readboy.termux/files/usr"
BASHRC_EOF

# 2. 创建 .profile 配置
echo "Creating .profile with proot configuration..."
cat > "${BOOTSTRAP_DIR}/etc/profile" << 'PROFILE_EOF'
# Proot 配置 - 系统级配置

if [ -z "$PROOT_ACTIVE" ]; then
    export PROOT_ACTIVE=1
    export ORIGINAL_PREFIX="/data/data/com.termux/files/usr"
    export NEW_PREFIX="/data/data/com.readboy.termux/files/usr"
fi

# 设置环境变量
export PREFIX="/data/data/com.readboy.termux/files/usr"
export TERMUX_PREFIX="/data/data/com.readboy.termux/files/usr"
export HOME="/data/data/com.readboy.termux/files/home"
export LD_LIBRARY_PATH="/data/data/com.readboy.termux/files/usr/lib:$LD_LIBRARY_PATH"

# 添加到 PATH
if [ -d "/data/data/com.readboy.termux/files/usr/bin" ]; then
    PATH="/data/data/com.readboy.termux/files/usr/bin:/data/data/com.readboy.termux/files/usr/sbin:$PATH"
fi

if [ -d "/data/data/com.readboy.termux/files/usr/games" ]; then
    PATH="$PATH:/data/data/com.readboy.termux/files/usr/games"
fi

export PATH
PROFILE_EOF

# 3. 创建 proot 包装脚本
echo "Creating proot-wrapper script..."
cat > "${BOOTSTRAP_DIR}/usr/bin/proot-wrapper" << 'WRAPPER_EOF'
#!/data/data/com.readboy.termux/files/usr/bin/bash
# Proot 包装脚本 - 用于需要访问原始路径的程序

set -e

ORIGINAL_PREFIX="/data/data/com.termux/files/usr"
NEW_PREFIX="/data/data/com.readboy.termux/files/usr"

# 检查 proot 是否可用
if ! command -v proot >/dev/null 2>&1; then
    echo "Warning: proot not found, running without path binding"
    exec "$@"
fi

# 使用 proot 运行命令，绑定新路径到旧路径
# 这样可以访问原始包名的软件包
exec proot -b "${NEW_PREFIX}:${ORIGINAL_PREFIX}" -b "${NEW_PREFIX}/etc:/etc" "$@"
WRAPPER_EOF

chmod +x "${BOOTSTRAP_DIR}/usr/bin/proot-wrapper"

# 4. 创建 apt 配置
echo "Creating apt configuration..."
mkdir -p "${BOOTSTRAP_DIR}/etc/apt/apt.conf.d"
cat > "${BOOTSTRAP_DIR}/etc/apt/apt.conf.d/99proot" << 'APTCONF_EOF'
# Proot apt 配置
# 优化 apt 行为

# 不安装推荐和建议的包
APT::Install-Recommends "false";
APT::Install-Suggests "false";

# 禁用 HTTP 代理（除非明确设置）
Acquire::http::Proxy "false";
Acquire::https::Proxy "false";

# 使用较快的压缩方式
Acquire::CompressionTypes::Order "gz";
Acquire::GzipIndexes "true";

# 保持缓存
APT::Keep-Downloaded-Packages "true";
APTCONF_EOF

# 5. 创建 sources.list
echo "Creating sources.list..."
cat > "${BOOTSTRAP_DIR}/etc/apt/sources.list" << 'SOURCES_EOF'
# Termux 软件源
# 使用 proot 包装的源，支持原始包名的包

deb https://packages-cf.termux.dev/apt/termux-main stable main
deb https://packages-cf.termux.dev/apt/termux-root stable main
deb https://packages-cf.termux.dev/apt/termux-x11 stable main
SOURCES_EOF

# 6. 创建环境配置文件
echo "Creating environment configuration..."
cat > "${BOOTSTRAP_DIR}/etc/environment" << 'ENV_EOF'
# Proot 环境配置
PREFIX=/data/data/com.readboy.termux/files/usr
TERMUX_PREFIX=/data/data/com.readboy.termux/files/usr
HOME=/data/data/com.readboy.termux/files/home
ORIGINAL_PREFIX=/data/data/com.termux/files/usr
NEW_PREFIX=/data/data/com.readboy.termux/files/usr
PROOT_ACTIVE=1
ENV_EOF

# 7. 创建符号链接脚本（用于兼容性）
echo "Creating compatibility symlinks script..."
cat > "${BOOTSTRAP_DIR}/usr/bin/setup-symlinks" << 'SYMLINKS_EOF'
#!/data/data/com.readboy.termux/files/usr/bin/bash
# 创建兼容性符号链接
# 将原始包名路径链接到新包名路径

set -e

ORIGINAL_PREFIX="/data/data/com.termux/files/usr"
NEW_PREFIX="/data/data/com.readboy.termux/files/usr"

echo "Setting up compatibility symlinks..."

# 创建原始包名目录（如果不存在）
mkdir -p "/data/data/com.termux/files"

# 创建符号链接
if [ ! -L "/data/data/com.termux/files/usr" ]; then
    if [ -d "/data/data/com.termux/files/usr" ]; then
        echo "Warning: /data/data/com.termux/files/usr already exists as directory"
    else
        ln -s "${NEW_PREFIX}" "/data/data/com.termux/files/usr"
        echo "Created symlink: /data/data/com.termux/files/usr -> ${NEW_PREFIX}"
    fi
else
    echo "Symlink already exists: /data/data/com.termux/files/usr"
fi

echo "Compatibility symlinks setup completed"
SYMLINKS_EOF

chmod +x "${BOOTSTRAP_DIR}/usr/bin/setup-symlinks"

# 8. 创建初始化脚本
echo "Creating initialization script..."
cat > "${BOOTSTRAP_DIR}/etc/profile.d/proot-init.sh" << 'INIT_EOF'
#!/data/data/com.readboy.termux/files/usr/bin/bash
# Proot 初始化脚本
# 在首次启动时运行

# 检查是否已经初始化
if [ -f "/data/data/com.readboy.termux/files/usr/.proot-initialized" ]; then
    exit 0
fi

echo "Initializing proot environment..."

# 设置兼容性符号链接
if [ -x "/data/data/com.readboy.termux/files/usr/bin/setup-symlinks" ]; then
    /data/data/com.readboy.termux/files/usr/bin/setup-symlinks
fi

# 标记为已初始化
touch "/data/data/com.readboy.termux/files/usr/.proot-initialized"

echo "Proot environment initialized successfully"
INIT_EOF

chmod +x "${BOOTSTRAP_DIR}/etc/profile.d/proot-init.sh"

# 9. 创建 README 说明文件
echo "Creating README..."
cat > "${BOOTSTRAP_DIR}/README.PROOT.md" << 'README_EOF'
# Proot Bootstrap 配置说明

此 bootstrap 已配置为使用 proot 进行路径映射，以支持原始包名（com.termux）的软件包。

## 主要特性

1. **路径映射**：自动将 `/data/data/com.readboy.termux/files/usr` 映射到 `/data/data/com.termux/files/usr`
2. **命令包装**：apt、dpkg 等命令自动使用 proot 包装
3. **兼容性**：可以访问和使用原始 Termux 软件源的包

## 使用方法

### 基本使用

```bash
# 更新软件源（自动使用 proot）
apt update

# 安装软件包（自动使用 proot）
apt install <package_name>
```

### 手动使用 proot

```bash
# 使用 proot 包装特定命令
proot-wrapper <command>

# 手动运行 proot
proot -b /data/data/com.readboy.termux/files/usr:/data/data/com.termux/files/usr <command>
```

### 禁用 proot

如果需要禁用 proot 包装，可以取消设置环境变量：

```bash
unset PROOT_ACTIVE
```

## 配置文件

- `/etc/bash.bashrc` - Bash 配置，包含 proot 别名
- `/etc/profile` - 系统配置，设置环境变量
- `/etc/apt/apt.conf.d/99proot` - Apt 配置
- `/etc/apt/sources.list` - 软件源配置
- `/usr/bin/proot-wrapper` - Proot 包装脚本
- `/usr/bin/setup-symlinks` - 符号链接设置脚本

## 故障排除

### Proot 不可用

如果 proot 不可用，包装脚本会自动回退到直接运行命令。

### 路径问题

如果遇到路径问题，可以手动运行：

```bash
setup-symlinks
```

### 查看配置

查看当前 proot 配置：

```bash
echo $ORIGINAL_PREFIX
echo $NEW_PREFIX
echo $PROOT_ACTIVE
```

## 更多信息

- Proot 文档：https://proot.gitlab.io/
- Termux Wiki：https://wiki.termux.com/
README_EOF

echo "=========================================="
echo "Proot Bootstrap Configuration Completed"
echo "=========================================="
echo "Modified files:"
echo "  - ${BOOTSTRAP_DIR}/etc/bash.bashrc"
echo "  - ${BOOTSTRAP_DIR}/etc/profile"
echo "  - ${BOOTSTRAP_DIR}/etc/environment"
echo "  - ${BOOTSTRAP_DIR}/etc/apt/apt.conf.d/99proot"
echo "  - ${BOOTSTRAP_DIR}/etc/apt/sources.list"
echo "  - ${BOOTSTRAP_DIR}/etc/profile.d/proot-init.sh"
echo "  - ${BOOTSTRAP_DIR}/usr/bin/proot-wrapper"
echo "  - ${BOOTSTRAP_DIR}/usr/bin/setup-symlinks"
echo "  - ${BOOTSTRAP_DIR}/README.PROOT.md"
echo "=========================================="