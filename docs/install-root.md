# 完整版安装（root + NPU 加速）

> 出图 **96 秒/张**（512×512，8 步）。需要 root、Magisk、MTK MDLA 芯片。
>
> **先跑过 [纯 CPU 版](install-cpu.md) 再来看这里** —— 确认 ComfyUI 本身在你机器上能跑，
> 再上 NPU 这层，不然出问题时分不清是哪一层的锅。

---

## 前提检查（不满足就别往下走）

### 1. 芯片必须是 MediaTek MDLA

```sh
su -c "ls /dev/mtk_mdla* /dev/apusys* 2>/dev/null"
```

**应该看到 `/dev/mtk_mdla0` 之类的设备节点**。没有就是芯片不支持，到此为止。

### 2. 必须有 NeuroPilot 库

```sh
su -c "find /vendor -name 'libneuron*' 2>/dev/null"
```

应该看到 `libneuron_adapter.so`、`libneuron_platform.so`。

### 3. root 可用

```sh
su -c "id"
# 应该输出 uid=0(root)
```

**Magisk 的 su 策略必须允许 Termux**：

```sh
# 检查
su -c "cat /data/adb/magisk.db 2>/dev/null | grep termux"

# 如果被拒绝，用 Magisk 管理器里手动授权，或者：
su -c "magisk --sqlite \"UPDATE policies SET policy=2 WHERE uid=\$(stat -c %u /data/data/com.termux);\""
```

`policy=2` 是允许，`policy=1` 是拒绝。

### 4. 实测过这类芯片吗

目前只在 **MT6897 / Dimensity 8300** 上验证过。
同代或更新的 MTK 芯片理论上可用，但**opcode 支持度可能不同**，要自己试。

---

## 第 1 步：装 Termux（和纯 CPU 版一样）

```sh
pkg update && pkg upgrade -y
pkg install -y python git cmake ninja clang rust binutils
```

### ★ 关键：Termux 的 Python 不能直接跑 ComfyUI

**不加 `LD_PRELOAD` 会报**：

```
dlopen failed: cannot locate symbol "PyExc_Exception"
```

原因：`tokenizers` 这个 Rust 扩展在链接时找不到 Python 的符号表。

**解法**（启动脚本里已经带了）：

```sh
export LD_PRELOAD=/data/data/com.termux/files/usr/lib/libpython3.14.so
```

版本号跟着你的 Python 走（`python --version` 看）。

---

## 第 2 步：装 ComfyUI + PyTorch

和 [纯 CPU 版](install-cpu.md) 第 3~5 步完全一样。**先确保纯 CPU 能出图**。

---

## 第 3 步：装 KAtorch（NPU 加速层）

KAtorch 是另一套 PyTorch→MDLA 编译器，**不在本仓库**。

```sh
cd ~
git clone <KAtorch 仓库地址> KAtorch
cd KAtorch
```

按它自己的 README 装好。核心是两样东西：

| 文件 | 作用 |
|---|---|
| `~/npt/host_new` | NPU 宿主进程（C 程序，编译产物） |
| `~/npt/blobs512*/` | 编译好的 DiT blob（一个模型 3.8 GB） |

**blob 必须用你自己手机上的 Neuron 编译器生成**（电脑上生成不了），
一个模型大约编 40 分钟。

### 装到 ComfyUI

```sh
# 节点包放进 custom_nodes
cp -r <KAtorch>/katorch_nodes/katorch_npu ~/ComfyUI/custom_nodes/

# 重启 ComfyUI，看到 "KAtorch 节点 v2.0.0 已加载（13 个节点）" 就对了
```

### ⚠️ 这 13 个节点不是全都跑过

装完别以为所有选项都能用。实测状态：

| 分类 | 已验证 | 未验证 |
|---|---|---|
| **节点**（13 个） | 9 个（加载 / 编码 / 采样 / VAE 全链路） | **4 个**：`KAtorchLoraLoader` / `KAtorchModelInfo` / `KAtorchFreeMemory` / `KAtorchClearCondCache` |
| **采样器**（3 个） | `er_sde` / `euler` | **`heun`** |
| **调度器**（6 个） | `beta` / `linear` | **4 个**：`normal` / `karras` / `sgm_uniform` / `simple` |
| **分辨率** | **512×512** | 其他全部（要重编 16 块 blob） |
| **功能** | 文生图 / 图生图 | LoRA（预合并）/ `cfg > 1` 未系统验证 |

**完整表格 + 硬件前提**见
[katorch/katorch_nodes/README.md 的「验证状态」章节](https://github.com/KiraAnon/katorch/blob/main/katorch_nodes/README.md)。

**建议的上手顺序**：

1. 先用默认的 `er_sde` + `beta` + 512×512 跑通（这是唯一充分验证的组合）
2. 再试 `euler` + `linear`（也验证过）
3. **其余选项自己试之前，先做好可能出不了图的心理准备**——它们代码在，但从没跑过

**换底模**要重新编译 blob，见 [HANDOFF.md](HANDOFF.md) 第六·六章。

---

## 第 4 步：启动后端（root）

**必须用 root 跑**。Termux 普通权限下加载 GB 级 blob 会被 cgroup 杀掉（`BrokenPipeError`）。

用本仓库的启动脚本：

```sh
cp scripts/comfy-launch-root.sh ~/npt/
chmod +x ~/npt/comfy-launch-root.sh

su -c "setsid sh ~/npt/comfy-launch-root.sh > ~/npt/comfy.log 2>&1 < /dev/null &"
```

脚本做了这些事：

1. 设 `LD_PRELOAD`（修 tokenizers）
2. 设 `PREFIX` / `PATH` / `HOME` / `TMPDIR`
3. **用 `pgrep -f "main.py --cpu"` 判断是否已在运行**（不能用端口判断，见下）
4. `setsid` 脱离当前会话
5. 起 ComfyUI

### ★ 为什么不能用端口判断「已运行」

```sh
# ❌ 这个判断是错的
netstat | grep 8188
```

上次连接留下的 **TIME_WAIT** 状态也会被 grep 命中，脚本以为后端活着直接返回，
**实际上根本没起**。这个坑导致「点图标没反应」排查了很久。

正确做法：

```sh
pgrep -f "main.py --cpu"
```

---

## 第 5 步：验证

```sh
# 8188 在监听？
grep '1FFC' /proc/net/tcp | grep ' 0A '
# '1FFC' 是 8188 的十六进制，' 0A ' 是 LISTEN 状态

# 进程在？
pgrep -f "main.py --cpu"
pgrep -f host_new

# API 通？
curl -s http://127.0.0.1:8188/system_stats
```

然后在浏览器打开 `http://127.0.0.1:8188`，加载 `workflows/modular_ui.json` 试一张。

---

## 第 6 步：装安卓壳

```powershell
# 在电脑上（需要 adb + 构建工具）
powershell -File app\build.ps1 -Install
```

**构建前必须准备签名密钥**（仓库里没有，这是故意的）：

```powershell
keytool -genkeypair -v -keystore katorch.jks -keyalg RSA -keysize 2048 `
  -validity 10000 -alias katorch -storepass <你的密码> -keypass <你的密码> `
  -dname "CN=YourName, OU=Dev, O=Personal, L=City, ST=State, C=CN"
```

详细说明见 [app/README.md](../app/README.md)。

**壳做了什么**：

| 动作 | 效果 |
|---|---|
| 点图标 | root 拉起后端（没跑就起，跑着就连） |
| 加载中 | 显示进度，轮询 8188 |
| 上拉划掉 | `Service.onTaskRemoved()` → 杀掉 python + host_new，释放约 5 GB |
| 按返回 | 同样退出并关后端 |

**三个必看的坑**（都在 app/README.md）：

1. 划掉时 `Activity.onDestroy` 不调用，必须用 `Service.onTaskRemoved()`
2. app 的 root 看不到 Termux 目录，要 `su -t 1 -c`
3. 关后端的命令要内联在 Java 里，不能依赖设备上的脚本文件

---

## 第 7 步：前端优化

### 补 PWA 支持

ComfyUI 自带 manifest 但**缺 service worker**，所以 Chrome 不认它是可安装应用：

```sh
python scripts/comfy_add_sw.py
```

会写入 `sw.js` 并在 `index.html` 注入注册代码。
之后 Chrome 可以「安装应用」→ 真正的全屏独立窗口。

### 注入全屏按钮

如果你的浏览器没有全屏功能（**Via 就没有，翻遍设置也没有**），
或者 `settings put global policy_control "immersive.full=<包名>"` 无效（Android 11+ 已弃用），
用这个兜底：

```sh
python scripts/comfy_add_fullscreen.py
```

会在页面右侧注入一个蓝色圆形 ⛶ 按钮，点一下调 `requestFullscreen()`，
状态栏 + 地址栏一起消失。

> **注意**：这两个脚本改的是 ComfyUI 前端包里的文件，**ComfyUI 升级会被覆盖**，需要重跑。

### 帧率：DPR 是元凶

1440×3200 屏 + DPR 2.5 → 拖动画布 **3~4 fps**。

```sh
# 降分辨率 + 密度
wm size 1080x2400
wm density 400

# 锁横屏
settings put system user_rotation 1
```

配合 ComfyUI 设置里关掉 Lite Graph 的阴影/边框 → **25 fps**。

**还原**：

```sh
wm size reset
wm density reset
settings put system user_rotation 0
settings put system accelerometer_rotation 1
```

---

## 第 8 步：开机恢复（重启后要做的事）

**ComfyUI 不会开机自启**（这是有意的，避免开机就吃掉 5 GB）。

每次重启后：

```sh
# 1. 如果用过 hosts 绑定（见 troubleshooting），要重新挂载
su -c "mount -o bind /data/local/tmp/hosts_katorch /system/etc/hosts"

# 2. 启动后端
su -c "setsid sh ~/npt/comfy-launch-root.sh > ~/npt/comfy.log 2>&1 < /dev/null &"
```

或者装个 root 级别的 service（`/data/adb/service.d/`），但**记得加内存保护**。

---

## 实测性能

**Android 15 / MT6897 / Dimensity 8300 / 16 GB**

| 环节 | 耗时 |
|---|---|
| 采样（8 步） | 95 秒 |
| VAE 解码（NPU） | 1.3 秒 |
| 文本编码 ×2 | 5.5 秒（第二次走 ComfyUI 缓存，0 秒） |
| **稳定态端到端** | **96 秒** |
| 冷启动首张 | 107 秒 |
| 画布帧率 | 25 fps |
| 内存占用 | 约 5 GB |

**性能已经到硬件极限了**。所有优化方向都试过并量化过，结论见
[HANDOFF.md 六·八章](HANDOFF.md#六八采样优化穷尽结论2026-11-15)。简要：

```
单块搬运 5303 MB × 14 块 = 74 GB / 步
MDLA 实测稳态带宽 8.7 GB/s
→ 理论下限 8.5 秒/步，实测 10.8 秒（已跑到 79%）
```

---

## 换分辨率 / 换底模

**不支持运行时换**。NPU 是静态图，换分辨率或换模型都要**重新编译 blob**（约 40 分钟）。

流程（详见 HANDOFF 第六·六章）：

1. 用 KAtorch 的编译脚本生成新分辨率的图
2. 编出 16 块 blob（`~/npt/blobs<新名字>/`）
3. 在 `~/npt/assets/manifest.json` 里注册
4. 重载 ComfyUI

**已知能编的**：512×512（唯一实测过的）
**不要试 800×1424** —— 会加载 7.9 GB 模型 + 大激活，实测**把手机拖到强制重启**。

---

## 下一步

- 出问题 → [troubleshooting.md](troubleshooting.md)
- 想接自己的模型到 NPU → [KAtorch 调用说明](KAtorch调用说明.md)
- 想改前端 → [HANDOFF.md](HANDOFF.md) 六·五章（安卓壳）/ 六·七章（VAE）
