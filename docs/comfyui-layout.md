# ComfyUI 目录结构（手机上）

> 「模型放哪、LoRA 放哪、插件装哪」——这篇讲清楚。
> 和你 PC 上的 ComfyUI 是**完全一样**的目录约定，因为这是上游 ComfyUI 自己的规范。

---

## 一次性看懂全貌

```
/data/data/com.termux/files/home/ComfyUI/
├── models/                  ← ★ 模型库（26 个分类子目录）
│   ├── checkpoints/         完整底模（SD1.5/SDXL 那种整包）
│   ├── diffusion_models/    ★ DiT 类底模（Anima 放这里）
│   ├── unet/                只含 UNet 的拆分权重
│   ├── vae/                 ★ VAE（qwen_image_vae 放这里）
│   ├── clip/                ★ 文本编码器（qwen3 放这里）
│   ├── text_encoders/       CLIP 的另一种叫法（T5 等）
│   ├── clip_vision/         视觉编码器（做 IP-Adapter 用）
│   ├── loras/               ★ LoRA（turbo 放这里）
│   ├── controlnet/          ControlNet
│   ├── embeddings/          文本反演 embedding
│   ├── upscale_models/      放大模型（ESRGAN 等）
│   ├── style_models/        风格模型
│   ├── model_patches/       LoRA 类补丁
│   ├── hypernetworks/       Hypernetwork
│   ├── gligen/              GLIGEN
│   ├── photomaker/          PhotoMaker
│   ├── audio_encoders/      音频编码器
│   ├── configs/             模型配置文件
│   └── ...（共 26 个，见下方完整清单）
│
├── custom_nodes/            ← ★ 插件 / 节点库
│   ├── katorch_npu/         KAtorch 的 NPU 节点（可选）
│   ├── Comfyui-Anima-Wardrobe-V4/   画师串生成器
│   ├── rgthree-comfy-main/          Seed / Context 等实用节点
│   ├── ComfyUI-GGUF/                GGUF 量化模型支持
│   └── <插件名>.disabled/           把目录改名加 .disabled 就禁用
│
├── user/
│   └── default/
│       └── workflows/       ← ★ 工作流（.json），界面上按名字列出
│
├── input/                   ← 输入图（LoadImage 选图从这里读）
├── output/                  ← 输出图（SaveImage 默认写这里）
├── temp/                    ← 临时文件
│
├── comfy/                   ← 核心库（不要动）
├── comfy_extras/            ← 官方扩展节点（不要动）
├── api_server/              ← 内置 API 服务器
├── web/                     ← 前端构建产物
└── ...（其余是 ComfyUI 自己的代码，不要动）
```

**打 ★ 的五个是你会天天碰的**：`models/diffusion_models`、`models/clip`、`models/vae`、
`models/loras`、`custom_nodes`。

---

## 一、模型放哪

### 最常放错的三个

| 模型类型 | 放哪 | 常见误放 |
|---|---|---|
| **DiT 类底模**（Anima / Flux / SD3） | `models/diffusion_models/` | ❌ 放 `checkpoints/`（那是 SD1.5/SDXL 整包的地方） |
| **文本编码器**（Qwen3 / T5 / CLIP-L） | `models/clip/` 或 `models/text_encoders/` | ❌ 放 `models/loras/` |
| **VAE** | `models/vae/` | ❌ 和底模混在一起 |

**判断方法**：看文件大小。
- 3～12 GB → 多半是 `diffusion_models` 或 `checkpoints`
- 1～5 GB 且叫 `qwen3` / `t5xxl` / `clip_l` → `clip`
- 100～500 MB → `vae` 或 `loras`

### Anima 这套要放的具体位置

```
ComfyUI/models/
├── diffusion_models/anima-base-v1.0.safetensors    3.9 GB
├── clip/qwen_3_06b_base.safetensors                1.1 GB
├── vae/qwen_image_vae.safetensors                  242 MB
└── loras/anima-turbo-lora-v0.2.safetensors         142 MB
```

放完之后，节点下拉框里就会自动出现这些名字（ComfyUI 扫描目录生成）。

**放错了会怎样**：对应的 Loader 节点下拉框里**压根不显示这个文件**。
不是加载失败，是**看不见**——因为下拉框只列对应目录里的文件。

### models/ 全部 26 个分类（ComfyUI 4.x 标准）

```
audio_encoders       background_removal   checkpoints          clip
clip_vision          configs              controlnet           detection
diffusers            diffusion_models     embeddings           frame_interpolation
geometry_estimation  gligen               hypernetworks        latent_upscale_models
loras                model_patches        optical_flow         photomaker
style_models         text_encoders        unet                 upscale_models
vae                  vae_approx
```

**没有某个目录不代表出错**——ComfyUI 只用你实际放东西的那几个。

---

## 二、LoRA 放哪

```
ComfyUI/models/loras/
```

**支持子目录分类**（推荐按风格/画师分）：

```
models/loras/
├── anima/
│   ├── anima-turbo-lora-v0.2.safetensors
│   └── style_necomi.safetensors
├── character/
│   └── 汐见萤_moss_v5.safetensors
└── quality/
    └── anima-highres-aesthetic-boost.safetensors
```

节点里会显示成 `anima/anima-turbo-lora-v0.2.safetensors`（带路径）。

> **⚠️ KAtorch NPU 的限制**：NPU 是静态图，**LoRA 必须在编译前合并进权重**，
> 不能在运行时动态加载。所以 NPU 工作流里选 LoRA 只在「已预编译进 blob」的组合下有效。
> 纯 CPU 版没这个限制，正常用就行。

---

## 三、插件 / 节点库放哪

```
ComfyUI/custom_nodes/
```

**装一个插件**：

```sh
cd ~/ComfyUI/custom_nodes
git clone <插件的仓库地址>
# 如果插件有依赖
pip install -r <插件目录>/requirements.txt
# 重启 ComfyUI
```

**禁用而不删除**：目录名加 `.disabled` 后缀

```sh
mv rgthree-comfy-main rgthree-comfy-main.disabled
```

ComfyUI 启动时跳过所有以 `.disabled` 结尾的目录。

> **这条很重要**：某些插件会在 import 时自动 `pip install`，把启动拖到几分钟。
> 实测 **Comfyroll** 就干过这事（改 `pyproject.toml` 都没用，因为它是在 `__init__.py` 里跑的）。
> 不想用时直接改名 `.disabled`。

### 当前装的插件（这台机器）

```
katorch_npu                        NPU 加速节点（可选，需 KAtorch）
Comfyui-Anima-Wardrobe-V4          画师串生成器（AnimaV4RandomPrompt）
rgthree-comfy-main                 Seed / Context 等实用节点（48 个）
ComfyUI-GGUF                       GGUF 量化模型支持
ComfyUI-Custom-Scripts-main        界面增强
npu_hook_node                      实验性，已弃用
ComfyUI_Comfyroll_CustomNodes-main.disabled   已禁用（启动时会 pip install）
```

### 装插件的三个坑

1. **备份目录不能放在 `custom_nodes/` 里**
   ComfyUI 会把该目录下**每个含 `__init__.py` 的子目录**都当插件加载。
   备份留在原地 → 重复注册节点，旧版还会覆盖新版。
   **备份放到外面**，比如 `~/npt/node_backups/`。

2. **插件之间会抢同一个节点名**
   两个插件注册同名节点，后加载的覆盖先加载的。
   症状：节点看起来在，但行为是另一个插件的。

3. **aarch64 上很多插件装不上**
   依赖里带 `torch` 编译扩展的（比如 `flash-attn`）基本没戏。
   **装之前先看它的 `requirements.txt` 有没有需要编译的东西**。

---

## 四、工作流放哪

```
ComfyUI/user/default/workflows/
```

放进去的 `.json` 会在界面左侧「工作流」面板里按文件名列出。

**从 PC 拷工作流到手机**：

```sh
# 在 PC 上
adb push my_workflow.json /data/local/tmp/
# 在手机上
su -c "cp /data/local/tmp/my_workflow.json ~/ComfyUI/user/default/workflows/"
```

> **注意工作流的两种格式**：
> - **UI 格式**（界面上「保存」得到的）：包含节点位置、颜色等全部信息
> - **API 格式**（「导出 (API)」得到）：只有执行逻辑，没有界面信息
>
> 本仓库 `workflows/` 里的都是 **API 格式**（给 `comfy_submit.py` 用的）。
> 想在界面上打开编辑，要用 UI 格式——可以在 ComfyUI 里重新拖一遍，或者用 API 格式提交后
> 在界面「加载」里转。

---

## 五、输入输出

| 目录 | 用途 |
|---|---|
| `input/` | `LoadImage` 等节点选图时从这里读 |
| `output/` | `SaveImage` 默认写这里 |
| `temp/` | 中间文件，可以随时清 |

**手机上的输出图在**：

```
~/ComfyUI/output/
```

**想让图直接进相册**：装本仓库的 [`app/`](../app/README.md) 安卓壳，
它把「下载」按钮接到 MediaStore，点一下就存到 `相册/ComfyUI/`。
纯浏览器方案做不到这点（WebView 收不到 blob 下载）。

**清理磁盘**：

```sh
# 看占用
du -sh ~/ComfyUI/output ~/ComfyUI/temp ~/ComfyUI/models

# 清输出（小心，删了就没了）
rm -rf ~/ComfyUI/output/*
```

---

## 六、和 PC 上的区别

**目录结构完全一样**，因为都是上游 ComfyUI 的规范。区别只有：

| | PC | 手机 |
|---|---|---|
| 路径 | `C:\ComfyUI\` 或 Linux 任意位置 | 固定在 `~/ComfyUI/`（Termux home 下） |
| 模型 | 可以放十几个 GB 不心疼 | **存储紧张，建议只留正在用的** |
| 插件 | 随便装 | aarch64 上很多装不上，装之前先看依赖 |
| 启动 | 双击 / 命令行 | 见 [install-cpu.md](install-cpu.md) 或 [install-root.md](install-root.md) |
| 出图速度 | 秒级 | 96 秒（NPU） / 40~60 分钟（纯 CPU） |

**换机器 = 整个 `~/ComfyUI/` 目录拷过去就行**（Termux 的路径是固定的）。

---

## 七、快速检查清单

装完想知道自己搞对没有：

```sh
# 1. 模型都在该在的地方？
ls -lh ~/ComfyUI/models/diffusion_models/ ~/ComfyUI/models/clip/ \
       ~/ComfyUI/models/vae/ ~/ComfyUI/models/loras/

# 2. 插件加载了没？（看启动日志里有几个节点包）
grep -E "Import times|IMPORT FAILED" ~/npt/comfy.log | tail -20

# 3. 工作流有几个？
ls ~/ComfyUI/user/default/workflows/

# 4. 磁盘还剩多少？
df -h ~/ComfyUI
```

**第 2 条特别有用**：`IMPORT FAILED` 出现在哪个插件后面，就是哪个插件装坏了。
