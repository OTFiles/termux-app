# 使用 Proot-Distro 解决改包后的 Apt 使用问题

## 问题说明

由于 Termux 的 bootstrap zip 文件中的二进制文件包含了硬编码的 `$PREFIX` 路径（默认为 `/data/data/com.termux/files/usr`），当包名改为 `com.readboy.termux` 后，路径会变为 `/data/data/com.readboy.termux/files/usr`。

如果直接使用原始的 bootstrap zip 文件，会导致 apt 无法正常工作，因为二进制文件指向错误的路径。

## 解决方案：使用 Proot-Distro

Proot-distro 是一个工具，允许在 Termux 中安装和运行完整的 Linux 发行版（如 Ubuntu、Debian、Alpine 等）。这些发行版有自己的包管理系统，不受 Termux 包名变化的影响。

### 步骤 1：安装 Proot-Distro

在 Termux 中运行以下命令：

```bash
pkg update
pkg install proot-distro
```

### 步骤 2：安装 Linux 发行版

选择一个你喜欢的 Linux 发行版：

```bash
# 安装 Ubuntu
proot-distro install ubuntu

# 或者安装 Debian
proot-distro install debian

# 或者安装 Alpine
proot-distro install alpine
```

### 步骤 3：进入 Linux 发行版

```bash
# 进入 Ubuntu
proot-distro login ubuntu

# 或者进入 Debian
proot-distro login debian
```

### 步骤 4：使用包管理器

在 Linux 发行版中，你可以正常使用其包管理器：

```bash
# 在 Ubuntu/Debian 中
apt update
apt install <package_name>

# 在 Alpine 中
apk update
apk add <package_name>
```

### 步骤 5：创建快捷命令（可选）

为了方便使用，可以创建快捷命令：

```bash
# 在 ~/.bashrc 或 ~/.zshrc 中添加
alias ubuntu='proot-distro login ubuntu'
alias debian='proot-distro login debian'
```

## 优点

1. **无需重新编译 bootstrap**：不需要重新编译 bootstrap zip 文件
2. **无需搭建 apt 服务器**：使用官方的 Linux 发行版软件源
3. **完整的 Linux 环境**：可以使用完整的 Linux 发行版，而不仅仅是 Termux 包
4. **隔离性**：Linux 发行版与 Termux 环境隔离，不会相互影响

## 注意事项

1. **存储空间**：安装 Linux 发行版需要额外的存储空间（通常 500MB - 2GB）
2. **性能**：由于使用 proot 进行模拟，性能可能会有轻微下降
3. **兼容性**：某些需要内核特性的程序可能无法正常运行

## 重要：Proot 路径映射

由于包名从 `com.termux` 改为 `com.readboy.termux`，Termux 的数据目录路径也发生了变化：

- **原始路径**：`/data/data/com.termux/files`
- **新路径**：`/data/data/com.readboy.termux/files`

### Proot-Distro 中的路径映射

Proot-distro 会自动处理路径映射，但如果你需要在 Linux 发行版中访问 Termux 的文件系统，需要注意路径变化：

```bash
# 在 proot-distro 的 Linux 发行版中
# 访问 Termux 的 home 目录
ls /data/data/com.readboy.termux/files/home

# 访问 Termux 的 usr 目录
ls /data/data/com.readboy.termux/files/usr
```

### 使用符号链接方案时的 Proot 配置

如果你选择使用符号链接方案（见下文），需要确保 proot 正确映射路径：

```bash
# 创建符号链接
mkdir -p /data/data/com.termux/files
ln -s /data/data/com.readboy.termux/files/usr /data/data/com.termux/files/usr

# 如果使用 proot，可能需要添加绑定挂载
proot -b /data/data/com.readboy.termux/files:/data/data/com.termux/files
```

### Proot-Termux 的路径配置

如果你使用 proot-termux（不是 proot-distro），需要修改配置文件以使用新的路径：

```bash
# 编辑 proot-termux 配置文件
# 通常位于 ~/.termux/proot-termux/
# 修改其中的路径引用从 /data/data/com.termux 到 /data/data/com.readboy.termux
```

### 检查路径映射

在 Termux 中运行以下命令检查路径是否正确：

```bash
# 检查当前包名的数据目录
ls -la /data/data/com.readboy.termux/files

# 如果存在符号链接，检查链接
ls -la /data/data/com.termux/files
```

## 替代方案：修改 Bootstrap Zip 文件

如果你仍然想使用原始的 Termux 包管理系统，可以尝试以下方法：

### 方法 1：使用符号链接

```bash
# 在 Termux 中运行
mkdir -p /data/data/com.termux/files
ln -s /data/data/com.readboy.termux/files/usr /data/data/com.termux/files/usr
```

### 方法 2：重新编译 Bootstrap（需要 termux-packages 仓库）

如果你有 termux-packages 仓库，可以重新编译 bootstrap：

```bash
# 克隆 termux-packages 仓库
git clone https://github.com/termux/termux-packages
cd termux-packages

# 修改构建脚本以使用新的包名
# 然后重新编译 bootstrap
./build-package.sh bootstrap
```

## 推荐方案

对于大多数用户，**推荐使用 proot-distro 方案**，因为：
- 简单易用，不需要复杂的配置
- 可以使用完整的 Linux 发行版
- 不需要重新编译任何东西

## 参考资料

- [Proot-Distro GitHub](https://github.com/termux/proot-distro)
- [Termux Wiki - Proot](https://wiki.termux.com/wiki/Proot)
- [Termux Packages - Building Bootstrap](https://github.com/termux/termux-packages/wiki/For-maintainers#build-bootstrap-archives)