# comfyui-android

**在 Android 手机上把 ComfyUI 跑成一个正常 app** —— 点图标打开，划掉就真关掉，全屏无浏览器边框。

> 这不是「把 ComfyUI 塞进手机浏览器」。它解决的是浏览器方案那两个绕不过去的问题：
> **页面关了进程还在占内存**、**老内核浏览器跑不动 ComfyUI 的重前端**。

---

## 它长什么样

```
点 app 图标
   ↓
WebView 全屏加载（无地址栏、无状态栏）
   ↓
ComfyUI 原生节点编辑器，能拖能连能跑
   ↓
上拉任务列表划掉 → python + NPU 宿主进程全部退出，内存归还
```

---

## 两条路线，挑一条

| | **A. 完整版**（root + NPU 加速） | **B. 纯 CPU 版**（无 root） |
|---|---|---|
| 需要 root | ✅ 必须 | ❌ 不需要 |
| 需要 Magisk | ✅ | ❌ |
| 出图速度（512×512/8步） | **96 秒** | 约 40~60 分钟 |
| 安卓壳 app | ✅ 完整生命周期管理 | ✅ 同样可用 |
| 适用 | MTK MDLA 芯片 + 愿意 root | 任何 Android 手机，只想先跑起来看看 |

**先跑 B 试试手感，觉得有戏再上 A。** 详细步骤见：

- [docs/install-cpu.md](docs/install-cpu.md) —— 纯 CPU 无 root（**从这里开始最省事**）
- [docs/install-root.md](docs/install-root.md) —— 完整版（NPU 加速 + 安卓壳）

---

## 目录结构

```
comfyui-android/
├── app/                    安卓壳（WebView + 生命周期 + 自启）
│   ├── java/               MainActivity / StopService
│   ├── res/                图标（ComfyUI 官方 logo 转 VectorDrawable）
│   ├── build.ps1           构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
│   └── README.md           壳的实现细节与三个坑
├── scripts/                手机侧脚本
│   ├── comfy-launch-root.sh    后端启动（含 LD_PRELOAD 等关键环境变量）
│   ├── comfy-shortcut.sh       Termux:Widget 快捷方式
│   ├── comfy_add_sw.py         给前端补 PWA 支持（service worker）
│   ├── comfy_add_fullscreen.py 注入全屏按钮（浏览器没法全屏时的兜底）
│   └── comfy_pwa_check.py      验证 PWA 是否生效
├── workflows/              示例工作流（API 格式）
├── tools/                  辅助工具
└── docs/                   安装、排错、性能与踩坑记录
```

---

## 关键技术点（想改代码的先看这）

### 1. 后端必须带两个环境变量

```sh
export LD_PRELOAD=/data/data/com.termux/files/usr/lib/libpython3.14.so
python main.py --cpu --listen 0.0.0.0 --port 8188
```

- **不加 `LD_PRELOAD`** → `tokenizers` 报 `dlopen failed: cannot locate symbol "PyExc_Exception"`
- **不加 `--cpu`** → `AssertionError: Torch not compiled with CUDA enabled`

脚本里已经带好了，见 `scripts/comfy-launch-root.sh`。

### 2. 关后端必须用 `Service.onTaskRemoved()`

`Activity.onDestroy()` 在「上拉划掉」时**根本不调用**（实测只有 `onStop isFinishing=false`）。
所以清理逻辑放在 Service 里，并且 manifest 要写 `android:stopWithTask="false"`，
否则 Service 会跟着任务一起被杀，回调都收不到。

### 3. app 的 root 看不到 Termux 目录

`su -c "ls /data/data/com.termux"` 在 app 里返回 `No such file or directory`，
**即使 uid=0**。原因是 Magisk 的 su 沿用调用者的 mount namespace。

解法是接管 init 的 namespace：

```java
Runtime.getRuntime().exec(new String[]{"su", "-t", "1", "-c", cmd});
```

新版 Magisk 已经没有 `--mount-master`，只有 `-t, --target PID`。

### 4. 判断「后端已在运行」不能用端口

```sh
# ❌ 错：上次连接留下的 TIME_WAIT 会被 grep 命中，脚本以为后端活着
netstat | grep 8188

# ✅ 对：查进程
pgrep -f "main.py --cpu"
```

这个坑导致「点图标没反应」排查了很久。

### 5. 帧率：DPR 是元凶

1440×3200 屏幕 + DPR 2.5 → 拖动画布 3~4 fps。
降到 DPR 1.0 并关掉 litegraph 的阴影/边框 → **25 fps**。

再往上就是重写 litegraph 渲染层了，投入产出不划算，**25fps 是接受值**。

---

## 实测数据（Android 15 / MT6897 / Dimensity 8300）

| 项 | 值 |
|---|---|
| 出图（512×512，8 步，稳定态） | **96 秒** |
| 其中采样 | 95 秒（98.9%） |
| VAE 解码 | 1.3 秒 |
| 冷启动首张 | 107 秒（多出的是模型装载） |
| 画布帧率 | 25 fps |
| 内存占用 | 约 5 GB（可划掉释放） |

**性能已经压到硬件极限**，证据和所有被否决的优化方向见 [docs/HANDOFF.md](docs/HANDOFF.md) 六·八章。
简单说：MDLA 稳态带宽只有 8.7 GB/s，74 GB/步的数据搬运已经跑到理论值的 79%。

---

## 已知限制

- **只支持 512×512**。换分辨率要重编 16 块 NPU blob（约 40 分钟），静态图 NPU 的硬约束。
- **画布 25fps**。litegraph 是单线程 JS 渲染，除非重写渲染层。
- **横屏键盘占屏严重**。试过 4 种输入法方案都没解决，详见 HANDOFF「试了但没成的」。
- **NPU 加速部分不在本仓库**。那是 [KAtorch](https://github.com/) 的工作（另一套 PyTorch→MDLA 编译器），
  本仓库只负责「把 ComfyUI 变成手机 app」这一层，通过标准 ComfyUI 节点接口对接。

---

## 和 KAtorch 的关系

```
┌────────────────────────────────┐
│ comfyui-android  ← 本仓库       │  app 壳 / 启动 / 前端优化 / 部署
├────────────────────────────────┤
│ ComfyUI 本体（上游）            │  节点编辑器、执行引擎
├────────────────────────────────┤
│ KAtorch（另一仓库）             │  NPU 加速节点（可选）
└────────────────────────────────┘
```

**不用 KAtorch 也能用本仓库**（走纯 CPU 路线）。用了才有 96 秒的出图速度。

---

## 贡献

欢迎提 issue——尤其是**在其他芯片/机型上的实测数据**。目前只在 MT6897 上验证过，
其他 MTK 芯片（同样是 MDLA 5.0）理论上可用但没测。

如果你在别的机器上跑通了，欢迎贴一份 `docs/install-root.md` 里的实测表。

---

## License

[Apache-2.0](LICENSE)

---

## 致谢

- [ComfyUI](https://github.com/comfyanonymous/ComfyUI) —— 整个前端和后端引擎
- [Termux](https://termux.dev/) —— 让 Android 上跑 Python 成为可能
- Magisk —— root 方案
