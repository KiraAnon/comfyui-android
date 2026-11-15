# 项目结构

> 这篇讲**两个层次**的东西：
> 1. 手机上 ComfyUI 的目录结构（模型/LoRA/插件工作流放哪）→ 见 [comfyui-layout.md](comfyui-layout.md)
> 2. **本仓库**（`comfyui-android`）的目录结构，以及它和 KAtorch 仓库的分工

---

## 一、手机上 ComfyUI 的目录结构

**这个不在本仓库里**（那是运行时环境，不是源码）。完整说明见
**[comfyui-layout.md](comfyui-layout.md)**，这里只给个速查：

```
~/ComfyUI/
├── models/                  模型库（26 个分类）
│   ├── diffusion_models/    ★ DiT 底模（Anima 放这）
│   ├── clip/                ★ 文本编码器
│   ├── vae/                 ★ VAE
│   ├── loras/               ★ LoRA（支持子目录分类）
│   └── ...
├── custom_nodes/            ★ 插件 / 节点库
├── user/default/workflows/  ★ 工作流（.json）
├── input/                   输入图
└── output/                  输出图
```

**目录约定和 PC 上的 ComfyUI 完全一样**——都是上游项目的规范。

---

## 二、本仓库（comfyui-android）的结构

```
comfyui-android/
├── README.md                    主文档：两条路线（CPU / NPU）
├── LICENSE                      Apache-2.0
├── .gitignore / .gitattributes  忽略规则 + 换行符锁定
│
├── app/                         ★ 安卓壳（Android WebView app）
│   ├── AndroidManifest.xml
│   ├── build.ps1                构建脚本（aapt2 → javac → d8 → zipalign → apksigner）
│   ├── README.md                壳的实现细节 + 三个坑
│   ├── java/com/katorch/npu/
│   │   ├── MainActivity.java    WebView + JS 桥 + 生命周期
│   │   └── StopService.java     划掉时真正杀后端
│   ├── res/                     图标 / 颜色 / 字符串
│   ├── assets-src/              ComfyUI 官方 logo 源文件
│   └── js/                      ★ 注入脚本（改注入逻辑改这里）
│       ├── SAVE_JS.js           保存图片到相册（劫持 blob 下载）
│       ├── PERF_JS.js           视觉优化（关阴影/边框）
│       ├── DPR_JS.js            降 devicePixelRatio（帧率关键）
│       ├── BACKUP_JS.js         工作流自动备份到 localStorage
│       ├── TOOLS_JS.js          工具栏注入（重载按钮等）
│       └── KB_JS.js             键盘相关
│
├── scripts/                     ★ 手机侧脚本
│   ├── comfy-launch-root.sh     后端启动（LD_PRELOAD / --cpu / 进程判断）
│   ├── comfy-shortcut.sh        Termux:Widget 快捷方式
│   ├── comfy-start.sh           简单启动
│   ├── comfy_add_sw.py          补 PWA 支持（service worker）
│   ├── comfy_add_fullscreen.py  注入全屏按钮
│   └── comfy_pwa_check.py       验证 PWA 生效
│
├── workflows/                   示例工作流（**API 格式**）
│   ├── modular_api.json         模块化文生图（推荐从这个开始）
│   ├── modular_ui.json          同上，UI 格式（能在界面打开编辑）
│   ├── cfg_api.json             cfg > 1 带负向提示词
│   ├── img2img_api.json         图生图
│   ├── roundtrip_api.json       VAE 编码→解码往返测试
│   ├── blackbox_api.json        旧黑盒节点
│   └── miao_api.json            miaomiaoHarem 底模
│
├── tools/                       开发机侧工具
│   ├── comfy_submit.py          通过 API 提交工作流
│   ├── ka.ps1                   adb + root 助手（Ka-Sh / Ka-Pull / Ka-Push ...）
│   ├── patch_save.py            一次性打补丁脚本（留作参考）
│   └── push_to_github.py        一键建仓库 + 推送
│
└── docs/
    ├── comfyui-layout.md        ★ 手机上模型/LoRA/插件/工作流放哪
    ├── install-cpu.md           纯 CPU 安装（无 root）
    ├── install-root.md          完整版安装（root + NPU）
    ├── troubleshooting.md       按症状查的排错
    ├── PROJECT_LAYOUT.md        本文档
    ├── HANDOFF.md               开发全过程记录（历史，含很多踩坑细节）
    ├── KAtorch调用说明.md        给想用 KAtorch 加速自己模型的人
    └── 更新-*.md                 阶段性更新报告
```

**打 ★ 的是核心资产。**

---

## 三、跨仓库对照：哪些东西在别的仓库

**这是最容易困惑的地方。** 本项目实际是两个仓库：

| 仓库 | 地址 | 负责什么 |
|---|---|---|
| **comfyui-android** | https://github.com/KiraAnon/comfyui-android | 安卓壳、启动脚本、前端优化、部署文档 |
| **katorch** | https://github.com/KiraAnon/katorch | NPU 编译器、13 个 ComfyUI 节点、模型库 |

### 目录归属对照

| 你可能在找的东西 | 在哪个仓库 | 对应路径 |
|---|---|---|
| **安卓壳源码** | ✅ 本仓库 | `app/` |
| **启动脚本** | ✅ 本仓库 | `scripts/comfy-launch-root.sh` |
| **前端注入脚本** | ✅ 本仓库 | `app/js/` |
| **示例工作流** | ✅ 本仓库 | `workflows/` |
| **安装文档** | ✅ 本仓库 | `docs/install-*.md` |
| **NPU 节点包**（13 个） | ❌ 在 katorch | `katorch_nodes/katorch_npu/` |
| **NPU 宿主**（host.c） | ❌ 在 katorch | `编译器/host.c` |
| **图编译器**（lowering.py） | ❌ 在 katorch | `编译器/lowering.py` |
| **DiT 建模脚本** | ❌ 在 katorch | `KAdit/` |
| **文本编码器建模** | ❌ 在 katorch | `KAllm/` |
| **VAE 建模** | ❌ 在 katorch | `KAvae/` |
| **图生成/调试脚本** | ❌ 在 katorch | `pcw/` |
| **手机运行时镜像** | ❌ 在 katorch | `mobile-baseline/npt/` |
| **MDLA 算子实测表** | ❌ 在 katorch | `算子清单/` |
| **部署脚本**（deploy.ps1） | ❌ 在 katorch | `katorch_nodes/deploy.ps1` |
| **增量同步**（sync.ps1） | ❌ 在 katorch | `tools/sync.ps1` |

**简单记法**：

- **「让 ComfyUI 变成手机 app」的部分 → 本仓库**
- **「让出图从 40 分钟变成 96 秒」的部分 → katorch 仓库**

### 两个仓库怎么配合

```
你想做的事                          该看哪
─────────────────────────────────────────────────
装个 ComfyUI 在手机上看效果         docs/install-cpu.md
让出图变快（有 root）               docs/install-root.md + katorch 仓库
把 ComfyUI 包成独立 app             app/README.md
模型/LoRA/插件放哪                  docs/comfyui-layout.md
工作流怎么提交                       workflows/ + tools/comfy_submit.py
出图报错                            docs/troubleshooting.md
想用 KAtorch 加速自己的模型          docs/KAtorch调用说明.md
```

### 开发机上的完整布局（供参考）

如果你克隆了两个仓库（或者只有 katorch 那个完整的开发仓库），结构大致是：

```
<开发机>/
├── comfyui-android/       ← 本仓库
│   └── ...
└── katorch/               ← 另一个仓库
    ├── 编译器/            host.c / lowering.py / shape_infer.py
    ├── katorch_nodes/     katorch_npu/（13 个节点）+ workflows/ + deploy.ps1
    ├── KAdit/ KAllm/ KAvae/ KAcustom/      模型建模
    ├── pcw/               图生成脚本
    ├── mobile-baseline/   手机运行时镜像
    ├── 算子清单/          MDLA 算子实测表
    └── docs/              文档
```

**手机上的对应关系**（部署后）：

```
~/ComfyUI/custom_nodes/katorch_npu/   ← katorch/katorch_nodes/katorch_npu/ 部署到这儿
~/npt/                                ← katorch/mobile-baseline/npt/ 的镜像
~/npt/assets/manifest.json             资产清单（blob 注册表）
~/npt/blobs512m/                       DiT blob（anima_v1）
~/npt/vae_npu512/                      VAE 4 段 blob
~/npt/host_new                         NPU 宿主可执行
~/npt/ka_restart.sh                    重启后端（**必须走这个**）
```

> ⚠️ **`.dla` blob 不在任何仓库里**（DiT 3.6 GB / VAE 50 MB，太大），
> 而且必须用**你自己手机上的 Neuron 编译器**生成，电脑上生成不了。
> 重编方法见 [HANDOFF.md](HANDOFF.md) 第六·六章。

---

## 四、哪些是「源」，哪些是「产物」

| 类型 | 位置 | 能否重新生成 |
|---|---|---|
| 安卓壳源码 | `app/java/` | 是源 |
| 注入脚本 | `app/js/` | 是源（但改完要同步到 Java 里的字符串） |
| 构建脚本 | `app/build.ps1` | 是源 |
| **APK** | `app/ComfyUI.apk` | ❌ 产物，`build.ps1` 生成 |
| **keystore** | `app/comfyui.jks` | ❌ 私钥，**绝不能提交** |
| **签名密码** | `app/.kspass` | ❌ **绝不能提交**（已在 .gitignore） |
| 编译中间物 | `app/build/` | ❌ 产物，可随时删 |
| 工作流 | `workflows/` | 是源 |
| `.dla` blob | 手机 `~/npt/blobs*/` | ❌ 产物，40 分钟重编 |

**已 gitignore 的**：`*.apk`、`*.jks`、`*.kspass`、`build/`、`out/`、`*.dla`、`*.safetensors`。

---

## 五、注入脚本和 Java 的关系（改代码必看）

`app/js/*.js` 是**提取出来的副本**，用于 `node --check` 验语法。

**真正的运行时字符串在 `MainActivity.java` 里的 `*_JS` 常量**（Java 多行字符串）。

**改注入逻辑的流程**：

1. 改 `app/js/XXX_JS.js`
2. `node --check app/js/XXX_JS.js` 验语法
3. 把内容同步到 `MainActivity.java` 里对应的 `XXX_JS` 常量
4. `powershell -File app/build.ps1` 重新编译

> **为什么这么麻烦**：Java 字符串拼 JS 极易出错，实测有三个 bug 里两个是拼字符串造成的
> （括号不配对导致整个脚本没执行）。先 `node --check` 再同步，是唯一可靠的办法。
>
> 详见 [更新-20251115-保存图片.md](更新-20251115-保存图片.md) 的三、四条教训。
