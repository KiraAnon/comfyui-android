# 纯 CPU 安装（不需要 root）

> **从这条开始**。整套流程不用 root、不用 Magisk、不用 NPU，任何 Android 手机都能跑。
> 代价是慢——512×512 / 8 步大约 **40~60 分钟**一张。
> 先跑通看看手感，觉得有戏再上 [完整版](install-root.md)（96 秒一张）。

---

## 前提

| 项 | 要求 |
|---|---|
| Android | 7.0+（实测 Android 15） |
| 存储 | **至少 15 GB 空闲**（模型 + Python 环境） |
| 内存 | 8 GB 以上（4 GB 会很吃力） |
| CPU | ARM64（arm64-v8a） |
| 网络 | 能访问 GitHub / HuggingFace（国内可能需要代理） |

---

## 第 1 步：装 Termux

**必须从 F-Droid 或 GitHub Releases 装，不要用 Google Play 版本**（Play 版本过时且不能装某些包）。

- F-Droid：https://f-droid.org/packages/com.termux/
- GitHub：https://github.com/termux/termux-app/releases

装完先做两件事：

```sh
# 换成国内镜像（如果访问官方源慢）
termux-change-repo
# 选 Mirror group → 选清华或中科大

# 更新
pkg update && pkg upgrade -y
```

---

## 第 2 步：装 Python 和基础依赖

```sh
pkg install -y python git cmake ninja clang rust binutils
```

> `rust` 和 `clang` 是为了后面编译某些没有预编译 wheel 的包，**别跳过**，不然会卡在 `tokenizers` 或 `safetensors` 上。

验证：

```sh
python --version      # 应该是 3.11+
```

---

## 第 3 步：装 PyTorch（CPU 版）

```sh
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu
```

**如果这一步失败**，多半是网络问题。可以换清华源：

```sh
pip install torch --index-url https://mirrors.tuna.tsinghua.edu.cn/pytorch-wheels/cpu/
```

验证 torch 装好了：

```sh
python -c "import torch; print(torch.__version__, torch.backends.cpu.get_cpu_capability())"
```

---

## 第 4 步：装 ComfyUI

```sh
cd ~
git clone https://github.com/comfyanonymous/ComfyUI.git
cd ComfyUI
pip install -r requirements.txt
```

**如果 `requirements.txt` 里有包装不上**（常见：`tokenizers`、`blake3`、`sentencepiece`），
先看错误信息：

| 错误 | 原因 | 解法 |
|---|---|---|
| `No matching distribution found` | 没有 arm64 wheel | `pkg install rust` 后用 `pip install --no-binary :all: <包名>` 源码编译 |
| `error: command 'clang' failed` | 缺编译工具链 | 确认 `pkg install clang cmake ninja` 都装了 |
| 编译到一半 OOM | 手机内存不够 | 一次只装一个包，别并行 |

---

## 第 5 步：下载模型

**Anima 模型（推荐，体积小、8 步出图）**：

```sh
cd ~/ComfyUI
mkdir -p models/diffusion_models models/clip models/vae models/loras

# 如果 huggingface 直连慢，先设镜像
export HF_ENDPOINT=https://hf-mirror.com

# 主模型（约 3.9 GB）
pip install -U "huggingface_hub[cli]"
hf download <模型仓库>/anima-base-v1.0.safetensors --local-dir models/diffusion_models/

# 文本编码器（约 1.1 GB）
hf download <模型仓库>/qwen_3_06b_base.safetensors --local-dir models/clip/

# VAE（约 240 MB）
hf download <模型仓库>/qwen_image_vae.safetensors --local-dir models/vae/

# Turbo LoRA（约 140 MB，8 步出图必需）
hf download <模型仓库>/anima-turbo-lora-v0.2.safetensors --local-dir models/loras/
```

> `<模型仓库>` 请替换成实际的 HuggingFace 仓库路径——模型授权在各自发布者手里，
> 本仓库不附带权重。

**模型文件校验**（重要，防止下到一半）：

```sh
ls -lh models/diffusion_models/ models/clip/ models/vae/ models/loras/
# 对照：3.9G / 1.1G / 240M / 140M
```

---

## 第 6 步：启动

```sh
cd ~/ComfyUI
python main.py --cpu --listen 127.0.0.1 --port 8188
```

**关键参数**：

| 参数 | 作用 |
|---|---|
| `--cpu` | **必须**，否则 torch 会找 CUDA 并报错 |
| `--listen 127.0.0.1` | 只在本机监听（纯 CPU 版不用外网访问） |
| `--port 8188` | 默认端口 |

看到 `To see the GUI go to: http://127.0.0.1:8188` 就好了。

**用手机浏览器打开 `http://127.0.0.1:8188`**。

---

## 第 7 步：装工作流

本仓库的 `workflows/` 里有示例。拷到 ComfyUI 的工作流目录：

```sh
mkdir -p ~/ComfyUI/user/default/workflows
# 假设你从电脑传过来的文件在 ~/wf/
cp ~/wf/*.json ~/ComfyUI/user/default/workflows/
```

然后在界面左侧的「工作流」面板里就能看到。

---

## 第 8 步（可选）：装成独立 app

想让 ComfyUI 脱离浏览器、有自己的图标、划掉就真退出？

看 [app/README.md](../app/README.md)。**但要注意**：安卓壳的「自动拉起后端」功能依赖 root
（它用 `su` 启动 python）。纯 CPU 版没有 root 的话：

- **壳能用**（WebView 显示 ComfyUI）
- **自动拉起不能用**，你得先手动在 Termux 里跑 `python main.py`

或者用 **Termux:Widget** 做快捷启动（不需要 root）：

```sh
mkdir -p ~/.shortcuts
cat > ~/.shortcuts/comfy-start.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
cd ~/ComfyUI
python main.py --cpu --listen 127.0.0.1 --port 8188
EOF
chmod +x ~/.shortcuts/comfy-start.sh
```

然后在桌面添加 Termux:Widget 小部件，点一下就能启动。

---

## 优化（纯 CPU 版也能做）

### 降低前端负担

在 ComfyUI 设置里（齿轮图标）：

| 设置项 | 改成 | 为什么 |
|---|---|---|
| `Comfy.EnableTooltips` | 关掉 | 少一层渲染 |
| Lite Graph → Canvas 阴影 | 关 | 实测从 3fps 提到 25fps |

### 调整屏幕

如果手机是 2K/4K 屏，游戏级别的分辨率会让 Canvas 渲染很吃力：

```sh
# 降到 1080p（需要 adb 或 root）
wm size 1080x2400
wm density 400
```

**还原**：

```sh
wm size reset
wm density reset
```

### 减步数

8 步是 turbo LoRA 的甜点。如果只要草稿，可以试 4~6 步，画质会掉但快一半。

---

## 和完整版的差距

| | 纯 CPU | 完整版（NPU） |
|---|---|---|
| 512×512 / 8 步 | 40~60 分钟 | **96 秒** |
| 需要 root | 否 | 是 |
| 需要 NPU | 否 | MTK MDLA |
| 装起来 | 20 分钟 | 1~2 小时（含编译 NPU 图） |

**差 30 倍**。如果你认真要用，值得上完整版。

---

## 出问题了

- ComfyUI 起不来 → 看 [troubleshooting.md](troubleshooting.md)
- 出图特别慢 → 正常，纯 CPU 就这样，确认 `--cpu` 加了
- 内存不够被杀 → 关掉其他 app，或换 512×512 以下分辨率

---

下一步：[完整版安装](install-root.md)（需要 root + MTK 芯片）
