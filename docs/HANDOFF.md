# KAtorch 手机 NPU 生图 — 交接文件

> 写给下一个接手的人（或未来的我）。
> **目录结构与电脑/手机同步规则见 [PROJECT_LAYOUT.md](PROJECT_LAYOUT.md)。**
> 源码在电脑，运行在手机；.dla blob 是产物，只在手机上。
> 最后更新：2026-11-15 00:20

---

## 一、这个项目是什么

在 **Android 手机（MediaTek MT6897 / Dimensity 8300，MDLA 5.0）** 上用 NPU 跑 Anima 文生图模型，
并把 ComfyUI 装进 Termux，做成点开就能用的手机应用。

**当前状态（2026-11-15）**：已模块化。整条管线拆成 12 个标准 ComfyUI 节点，
源码在 `F:\KAtorch\katorch_nodes\`，部署脚本 `deploy.ps1`，详见第六章。

---

## 二、硬件与环境

| 项 | 值 |
|---|---|
| 手机 | OB-A98 / Android 15 |
| SoC | MT6897（Dimensity 8300） |
| NPU | MediaTek MDLA 5.0 / APUSys |
| 内存 | 15.8 GB |
| 存储 | 466 GB（约 222 GB 可用） |
| Root | Magisk（`/data/adb/service.d/` 可用） |
| Termux | 0.118.3（侧载，uid **10171**） |
| Python | 3.14.10 |
| torch | 2.15.0（**CPU only，没有 CUDA**） |
| 屏幕 | 物理 1440×3200 @ 560dpi → **已 override 为 1080×2400 @ 400dpi，锁横屏** |

### 关键路径

```
/data/data/com.termux/files/home/
├── ComfyUI/                     # ComfyUI 0.45.0（手机端主体）
│   ├── custom_nodes/
│   │   ├── katorch_npu/         # ★ 我写的 NPU 节点（目前唯一一个）
│   │   ├── Comfyui-Anima-Wardrobe-V4/   # 画师串生成器（AnimaV4RandomPrompt）
│   │   ├── rgthree-comfy-main/  # Seed/Context（48 节点）
│   │   ├── ComfyUI-GGUF/
│   │   └── npu_hook_node/       # 实验性逐层加速（精度不够，已弃用）
│   ├── models/
│   │   ├── diffusion_models/anima_v1.safetensors   3.8G
│   │   ├── clip/qwen3.safetensors                  1.1G
│   │   ├── vae/qwen_image_vae.safetensors          242M
│   │   └── loras/turbo.safetensors                 142M
│   └── user/default/workflows/  # 所有工作流
├── npt/                         # ★ NPU 管线核心
│   ├── host_new                 # C 写的 NPU 驱动宿主（32KB 可执行）
│   ├── app/
│   │   ├── gen.py               # ★ 采样管线（含两个采样器）
│   │   ├── enc.py               # Qwen3-0.6B 编码器 + llm_adapter
│   │   ├── vae_decoder.py       # VAE 解码
│   │   ├── server.py            # 原简化版 web 服务（8770）
│   │   └── models/              # 编码器用的 tokenizer 等
│   ├── e2eper512/               # fp16 版图（14 块 + pe + fl）
│   ├── e2eper512i/              # 带 FP16 岛的图
│   └── comfy-launch-root.sh     # ComfyUI 的 root 启动入口
└── .shortcuts/                  # Termux:Widget 快捷方式
    ├── comfy-start.sh           # 启动 ComfyUI + 打开浏览器
    └── katorch-start.sh         # 启动原简化版
```

### 编译产物（NPU blob）

```
/data/local/tmp/blobs512m/    # fp16，16 个 blob，3.9GB  ← 当前使用
/data/local/tmp/blobs512q/    # W8A16 量化版，3.7GB      ← 精度不够，未用
/data/local/tmp/q16i/         # 量化图的 g.txt
```

---

## 三、NPU 是怎么工作的（核心机制）

### 3.1 约束：必须预编译

MDLA 是**静态图** NPU。不支持动态 shape、不支持图外执行。所以：

1. 用 PyTorch 跑一遍模型，把每一层的参数和 shape 记下来
2. 写成文本图格式（`g.txt`）
3. 用 `host_new` 编译成 blob（`NeuronModel`)
4. 运行时只能喂数据、取输出

**这意味着分辨率一旦变了，就要重新编译整份图**。目前只编译了 **512×512**（S=1024 token）。

### 3.2 图的切分

整个 DiT 被切成 **16 个 blob**：

```
pe  (patch embed)  →  b00..b13 (14 个双块)  →  fl (final layer)
```

每个 blob 体积约 **277MB**（fp16）。**IOVA 配额只有 ~1.35GB**，所以同时只能常驻 4-5 个。

### 3.3 单步流程

```
x (1,16,64,64)
  ↓ patch embed (pe)
h (1024, 2048)
  ↓ 14 × (block pair)          ← 每块都要 map/unmap
h
  ↓ final layer (fl)
v (1024, 2048)
  ↓ Euler 或 er_sde 更新
x_next
```

**14 块 × map/unmap ≈ 3.5 秒/步**（占单步 14.6s 的 24%）——这是当前最大的性能瓶颈。

### 3.4 图文本格式

```
OPD <id> <type> <ndims> <dims...> <scale> <zp> [w <path> | a <int>]
OP  <opcode> <n_in> <in_ids...> <n_out> <out_ids...>
IO  <n_in> <ids> <n_out> <ids>
QCH <op_id> <channel_dim> <count> <scales_path>
END
```

类型码：`FLOAT16=8`、`TENSOR_INT32=4`、`QUANT8_SYMM_PER_CHANNEL=11`、`QUANT16_SYMM=7`、INT32 标量 `1`

常用 opcode：
```
ADD=0  CONCAT=2  CONV=3  DEQUANTIZE=6  FC=9  LOGISTIC=14  MAXPOOL=17
MUL=18  RELU=19  RESHAPE=22  SOFTMAX=25  TANH=28  DIV=30  MEAN=31
SUB=36  TRANSPOSE=37  ABS=38  CAST=45  EXP=49  LOG=60  MAXIMUM=65
MINIMUM=66  NEG=67  QUANTIZE=72  REDUCE_MAX=77  REDUCE_SUM=80
RSQRT=83  SIN=85  SQRT=88  BATCH_MATMUL=102
```

---

## 四、踩过的坑（血泪清单，改代码前必读）

### 4.1 MDLA 的坏算子

**INT16 下的归约全部有问题**：

| 算子 | 误差 |
|---|---|
| `MEAN` | 117% |
| `REDUCE_MAX` | 63% |
| `REDUCE_SUM` | **200%（连符号都反了）** |

同样的图在 fp16 下完全精确。**所以量化路线必须绕开这些算子**。

绕法：
- `sum(x)` → `FC(x, ones(1,N))`（实测 0.0000% 误差）
- `max(x)` → 只能留在 FP16 岛里

### 4.2 精度切换的硬限制

- **`CAST(45)` 不支持跨 INT16↔FP16**，会编译失败
- 但 **`DEQUANTIZE(6)`（INT16→FP16）和 `QUANTIZE(72)`（FP16→INT16）可以**
- 实测 `INT16→DQ→MUL→Q→INT16` 误差 0.0184%

**所以混合精度的正确姿势**：用 DQ/Q 做边界，把需要高精度的子图包成一个「FP16 岛」。

### 4.3 其他坑

- **FC 必须 4 个输入**：`(input, weight, bias, activation_code)`。3 输入的会 UNMAPPABLE
- **activation_code 必须是 type 4**（TENSOR_INT32），写成 type 1 会**静默直通**（输出==第一个输入）
- **只有 FC 的第 2 个输入可以是 INT8 per-channel**，其他常量必须是 INT16
- **`LOGISTIC`/`SOFTMAX` 的输出 scale 必须是 `1/32768`**
- **`SHAPE_ONLY`（RESHAPE/TRANSPOSE）要求输入输出共享 scale**
- **编译命中缓存时无法导出**（`getCompiledNetworkSize` 返回 -7），必须冷编译
- **同一个进程里重复编译会 `-514 RESOURCE_EXHAUSTED`**，要重启宿主

### 4.4 IOVA 是全局配额

```
dma_buf_map_attachment -12  → IOVA 满
```

约 **1.35GB** 总量。用 `pop` 可以回收。每块 277MB → 最多 4-5 块常驻。

---

## 五、已完成的成果

### 5.1 能用的东西

| 功能 | 状态 | 耗时 |
|---|---|---|
| ComfyUI 原版界面 | ✅ | — |
| NPU 出图（512×512，8 步） | ✅ | **154~181 秒** |
| 画师串（AnimaV4RandomPrompt） | ✅ | — |
| 双采样器（euler / er_sde） | ✅ | — |
| 双调度器（beta / linear） | ✅ | — |
| 出图自动进相册 | ✅ | — |
| 全屏按钮 | ✅ | — |
| Termux:Widget 一键启动 | ✅ | — |

### 5.2 量化路线的结论（**别再做了**）

**决定性实验**（`phone_fc_speed.py`）：

```
FC 1024x2048 @ 2048x2048
fp16   : 8.6 ms
W8A16  : 7.6 ms
加速比 : 1.14x
```

**MDLA 的 INT8 只有 1.14 倍加速，不是理论上的 2 倍。**

而为了绕开坏掉的 INT16 归约，每块图要多 36 个算子（DQ/Q 岛边界 + FC 替代归约），
这点开销把 14% 的收益吃掉还倒亏（17.3s/步 vs fp16 的 14.6s/步）。

**结论：W8A16 在这颗芯片上净收益为负，不要再投入。**

### 5.3 时间线（简）

| 时间 | 做了什么 |
|---|---|
| 11-12 | ComfyUI 装进 Termux，模型就位 |
| 11-13 | `npu_hook_node` 实验（逐层 NPU，**失败**：每层误差 22~78%） |
| 11-14 早 | fp16 全链路打通，117 秒/张 |
| 11-14 中 | INT8 全流程打通（精度 1.577%/0.911%/2.334%），但**硬件加速只有 1.14x，放弃** |
| 11-14 晚 | ComfyUI 原版界面 + NPU 节点（154 秒） |
| 11-14 深夜 | 显示优化（分辨率/帧率/全屏）+ 画风对齐（调度器）+ 双采样器 |

---

## 六、模块化节点（✅ 已完成 2026-11-15）

> 6.1 之后的几节是当初的设计推演，保留作背景。
> **实际做出来的东西在 `F:\KAtorch\katorch_nodes\`**，完整说明见那里的 `README.md`。

### 6.0 成果

整条管线拆成 **12 个标准 ComfyUI 节点**，装在手机
`~/ComfyUI/custom_nodes/katorch_npu/`（一个 Python 包，15 个 .py，自包含）：

| 节点 | 类型流向 | 说明 |
|---|---|---|
| `KAtorchUNETLoader` | → `KAT_MODEL` | 查资产清单，绑定 blob 集 + 分辨率 |
| `KAtorchCLIPLoader` | → `KAT_CLIP` | Qwen3-0.6B + Anima 的 LLMAdapter |
| `KAtorchCLIPTextEncode` | `KAT_CLIP` → `KAT_COND` | prompt → ctx（512×1024 fp16） |
| `KAtorchVAELoader` | → `KAT_VAE` | Wan2.1 VAE（CPU） |
| `KAtorchEmptyLatentImage` | → `LATENT` | 标准 latent dict |
| `KAtorchKSampler` | → `LATENT` | ★ 核心：NPU 逐块采样，支持 3 采样器 × 6 调度器 × cfg × denoise |
| `KAtorchVAEDecode` | `LATENT` → `IMAGE` | 输出标准 IMAGE，能直连 SaveImage |
| `KAtorchVAEEncode` | `IMAGE` → `LATENT` | 图生图 |
| `KAtorchLoraLoader` | `KAT_MODEL` → `KAT_MODEL` | 预合并 LoRA 组合（静态图限制） |
| `KAtorchModelInfo` | → 文本 | 显示资产详情 |
| `KAtorchFreeMemory` | — | 释放 NPU 宿主（约 4GB 常驻） |
| `KAtorchNPUGenerate` | → `IMAGE` | 旧黑盒节点，保留向后兼容 |

对照 6.1 列的那七个 ❌，现在：

- ✅ 能换编码器（`KAtorchCLIPLoader` 换权重）
- ✅ 能换 VAE（`KAtorchVAELoader`）
- ✅ 能插 LoRA（受静态图限制，走预合并查表，选没编进去的会提示）
- ✅ 能拆开调试（每个节点单独跑、单独看耗时）
- ✅ 能复用中间产物（`KAtorchKSampler` 输出标准 LATENT）
- ✅ 能接图生图（`KAtorchVAEEncode` + `denoise<1`）
- ✅ 和 ComfyUI 生态打通（IMAGE / LATENT 都是标准类型）

### 6.0.1 资产层（地基）

`~/npt/assets/manifest.json`：扫描 blob 目录 + 校准文件，列出每个「模型 × 分辨率」组合。
`~/npt/assets/overrides.json` 是手工修正表，**只有标了 `"verified": true` 的才会出现在节点下拉框里**。

原因：`/data/local/tmp` 下躺着 5 个 `blobs*` 目录，其中 `blobs` 是实验残留
（pe 输出 256 token、fl 输出 1024 token，几份图拼在一起，跑第一个块就崩），
`blobs512q` / `blobs512mix` 的 pe/fl 是指向别处的符号链接。界面上看不出区别，
选错了就是一张废图，所以默认只暴露验证过的那一份。

换分辨率（6.4 ①）的流程：编新 blob → 丢进 `/data/local/tmp/blobs<边长>/` →
在 overrides.json 里登记 width/height/calib + `verified: true` → 重启 ComfyUI。

### 6.0.2 实测（MT6897）

| 项目 | 数值 |
|---|---|
| DiT 单步（14 块 map/run/unmap） | **11.0 s** |
| 8 步采样 | **90 s** |
| VAE 解码 512×512（CPU） | **36 s** |
| ComfyUI 端到端（首次，含加载） | **151 s** |
| cfg=1.6 双分支（6 步） | **197 s** |
| NPU 宿主 bload（16 块 3.9GB） | 1 s（OS 缓存命中） |

### 6.0.3 等价性（拆完没走样，逐位验证）

| 环节 | 对比 | 结果 |
|---|---|---|
| DiT 单步 | `gen.py::Pipeline._stack` | `max|Δ| = 0` |
| 8 步采样 euler+beta | `Pipeline.generate` | latent 逐位相同 |
| VAE 解码 | `Pipeline.decode` | 像素差 0 |

端到端（ComfyUI 实跑）：模块化文生图 151s ✅、cfg=1.6 197s ✅、旧黑盒节点 156s ✅、
VAE 往返 PSNR 46.15dB ✅、图生图 denoise=0.55 106s ✅。

### 6.0.3.1 图生图的两个坑

ComfyUI 的 Wan VAE 走视频路径，`VAE.encode()` 返回的 latent **带一维 T**（`[1,16,1,64,64]`），
而且**没做 mean/std 归一化**（归一化在 sampling 侧）。我们 DiT 工作在归一化空间，
所以 `KAtorchVAEEncode` 里要自己压掉 T 维并做 `(z-mean)/std`。
漏掉归一化这一步，图会过曝过饱和（实测 PSNR 从 46dB 掉到 16.9dB）。

### 6.0.4 这次踩的三个坑

1. **校准文件选错**：`~/npt/gen/` 下有 calib4~calib8 六份，`per_block` 的 post_scale 各不相同，
   按字母序会选中 calib4，画质直接崩。现在默认优先 calib8.json，其余靠 overrides 显式指定。
2. **备份目录被当插件加载**：ComfyUI 会加载 `custom_nodes/` 下每个含 `__init__.py` 的子目录，
   `katorch_npu.bak-*` 留在原地会重复注册节点、旧备份还会覆盖新节点。备份改放 `~/npt/node_backups/`。
3. **`pkill -f "main.py --cpu"` 会杀掉自己**：adb shell 那条命令行本身就含这个字符串。
   重启改成走设备脚本 `~/npt/ka_restart.sh`（cmdline 里不带该模式）。

---

### 6.1 当初的问题

只有一个 `KAtorchNPUGenerate` 节点，它把**整条管线**（编码 → 14 块采样 → VAE）打包成一个黑盒：

```
prompt ──► [KAtorch NPU 生图] ──► IMAGE
```

**这不算模块化**，因为：

- ❌ 不能只换编码器（比如换成别的 CLIP）
- ❌ 不能换 VAE
- ❌ 不能插 LoRA
- ❌ 不能拆开调试
- ❌ 不能复用中间产物（比如拿了 latent 去做别的）
- ❌ 没法接 ControlNet / 图生图 / 区域控制
- ❌ 和 ComfyUI 生态完全隔离

### 6.2 目标：拆成标准 ComfyUI 节点

对标 ComfyUI 原生的节点接口：

```
[UNETLoader]     ──MODEL────────────────────┐
[CLIPLoader]     ──CLIP──┐                  │
[CLIPTextEncode] ──COND──┤                  ▼
[CLIPTextEncode] ──COND──┼────────────► [KSampler] ──LATENT──► [VAEDecode] ──► [SaveImage]
[EmptyLatentImage] ─LATENT┘                  ▲
[VAELoader]      ──VAE───────────────────────┴──────────────────────────────┘
```

**核心难点**：NPU 是静态图，而 ComfyUI 是动态执行。

### 6.3 建议的架构：**分阶段 NPU 编译器**

不要试图在运行时编译（太慢，一分钟以上），改成**离线预编译 + 运行时查表**：

#### 阶段 A：资产层（离线）

```
KAtorchAssetManager
  ├─ 扫描 models/ 目录，建立 checkpoint 索引
  ├─ 对每个 (模型, 分辨率) 组合，预编译成 blob
  └─ 存成 manifest.json: {model_hash: {512x512: [blob路径列表...]}}
```

**关键**：编译一次，永久复用。用户选 512×512 就直接命中。

#### 阶段 B：节点层（在线，薄）

每个节点只做「数据转换」，真正的重活交给底层：

```python
class KAtorchUNETLoader:
    """输出一个 KAT_MODEL 对象，内含用哪个 blob 集合"""
    RETURN_TYPES = ("KAT_MODEL",)
    def load(self, unet_name, resolution):
        # 查 manifest，拿到 blob 列表
        return (KAtorchModel(unet_name, blobs, resolution),)

class KAtorchCLIPLoader:
    """输出 KAT_CLIP，内含编码器句柄"""
    RETURN_TYPES = ("KAT_CLIP",)

class KAtorchCLIPTextEncode:
    """prompt → conditioning（调用 enc.py）"""
    RETURN_TYPES = ("KAT_COND",)

class KAtorchKSampler:
    """★ 核心：把 ComfyUI 的 KSampler 接口映射到 NPU pipeline"""
    RETURN_TYPES = ("KAT_LATENT",)
    def sample(self, model, positive, negative, latent, seed, steps,
               cfg, sampler_name, scheduler, denoise):
        # 组装 VMC 会话，逐块 map/run/unmap
        # sampler_name / scheduler 直接映射到 gen.py 里的 SAMPLERS / SIGS
        return (latent_out,)

class KAtorchVAEDecode:
    RETURN_TYPES = ("IMAGE",)
```

#### 阶段 C：会话层（可选，进阶）

多个 KSampler 串联时，blob 可以**复用不卸载**，省掉 map/unmap 开销
（现在每步 3.5 秒浪费在这上面）。

### 6.4 必须解决的技术问题

按难度排序：

**① 分辨率/模型变化 → 需要重新编译**

- 现状：只编了 `anima_v1 @ 512×512`
- 要做：一个后台编译服务，用户选新分辨率时自动编译（进度条 + 缓存）
- 编译耗时：单块约 2-3 分钟 × 16 块 ≈ 40 分钟（可后台跑）

**② LoRA 动态加载**

- 现状：turbo LoRA 已经**合并进权重**（508 pairs, rank 32）
- 要做：LoRA 合并必须在编译前完成 → 每换一个 LoRA 就要重新编译 16 块
- **折中方案**：只支持「预合并」的几个 LoRA 组合，走 manifest 查表

**③ CFG > 1 的双分支**

- 现状：cfg=1 单分支，省一半算力
- 要做：cfg>1 需要跑两遍 DiT（positive / negative），耗时翻倍
- **注意**：图里只有一条 DiT 路径，要么跑两次，要么编译两份图交替

**④ 内存**

- 目前 512×512 用 5.5GB，够用
- 800×1424 会加载 7.9GB 模型 + 大激活 → **实测把手机拖死过**（用户长按重启）
- 要做：分辨率上限保护，超过就拒绝或走 CPU

**⑤ map/unmap 开销**

- 现在每步 3.5 秒耗在这
- 优化方向：多块合并成一张图（IOVA 够的话 4 块），或常驻不卸载

### 6.5 建议的推进顺序

1. **先做 `KAtorchUNETLoader` + `KAtorchKL` 的 asset manifest 机制**
   —— 这是所有模块化的地基，没有它后面的都白搭
2. **`KAtorchCLIPTextEncode`** —— 最简单，独立于 NPU，直接调 `enc.py`
3. **`KAtorchVAEDecode`** —— 也是纯 Python，独立
4. **`KAtorchKSampler`** —— 最核心也最难，前面三个都好之后再做
5. **`KAtorchEmptyLatent`** —— 简单
6. 最后把这些串成一个和 ComfyUI 原生工作流**长得一样**的工作流

---

## 六·五、安卓壳 App（2026-11-15）

手机上现在装了一个真正的 app：**ComfyUI**（不是浏览器书签）。

| 项 | 值 |
|---|---|
| 源码 | `F:\KAtorch\katorch_app\` |
| 产物 | `F:\KAtorch\katorch_app\KAtorch.apk`（17KB，文件名沿用旧名，app 名是 ComfyUI） |
| 构建 | `powershell -File F:\KAtorch\katorch_app\build.ps1 -Install`（aapt2→javac→d8→zipalign→apksigner，不用 Gradle） |
| 依赖 | Android SDK 在 `D:\AndroidSdk`（platforms;android-35 + build-tools;35.0.0）+ JDK 21 |
| 包名 | `com.katorch.npu`（内部标识；Magisk 已预授权 uid 10184） |

**行为**：点图标 → root 起后端 → 全屏加载 8188；上拉任务列表**划掉 → 真的关后端**（实测 14.8GB→7.7GB）；
home 切后台不关。

**三个坑**（详见 `katorch_app/README.md`）：

1. **划掉时 `Activity.onDestroy` 不调用**，只有 `onStop(isFinishing=false)`。
   关后端的逻辑必须放 `Service.onTaskRemoved()` + manifest 的 `android:stopWithTask="false"`。
2. **app 的 su 看不到 Termux 目录**（`No such file or directory`，即使 uid=0 + magisk 域）——
   Magisk 的 su 沿用调用者的 mount namespace。必须 `su -t 1 -c ...` 接管 init 的全局 namespace。
3. **关后端的命令别依赖设备上的脚本**，内联 `pkill` 最稳（app 侧读不到 `~/npt/ka_stop.sh`）。

**应用名与图标**：名字是 `ComfyUI`，图标是 ComfyUI 官方 logo——从手机上
`site-packages/comfyui_frontend_package/static/assets/images/comfy-logo-single.svg` 提取，
转成 Android VectorDrawable（深色圆角方块 `#211927` + 荧光黄 C `#F2FF59`）。
**KAtorch 是底层自制手机 PyTorch/MDLA 栈的名字，不是 app 名**；节点仍叫 `KAtorch*`，因为它们是
KAtorch 这套栈提供的节点。

### 壳的性能与输入调优（2026-11-15）

**拖动帧率 3～4 → 25**：根因是 ComfyUI 的 canvas 按 `devicePixelRatio` 渲染，这台手机 DPR 2.5，
每帧要重绘约 2700×6000。做法是拦截主文档往 `<head>` 注入
`Object.defineProperty(window,'devicePixelRatio',{get:()=>1.0})`，再关掉 litegraph 的
`render_shadows / render_canvas_border / render_connections_border / render_curvature / show_info`。

**键盘乱弹**：WebView 见到 input 拿到焦点就弹软键盘，ComfyUI 的工作流库搜索框会 autofocus。
解法是包一层 `HTMLElement.prototype.focus`，只有「800ms 内有触摸」且「触摸点落在该输入框矩形内」
才放行（用户原生点击不走 prototype，不会被误拦），外加 manifest 的 `stateAlwaysHidden`。

**刷新按钮**：画布底部中央注入半透明 ⟳，点击 `location.reload()`。位置避开右侧居中的全屏按钮。

---

## 六·六、换底模（2026-11-15，已跑通 miaomiaoHarem_anima12）

### 流程（实测 ~4 分钟出全套 blob）

```sh
# 1. 推模型（3.89GB，94 秒）
adb push miaomiaoHarem_anima12.safetensors /data/local/tmp/

# 2. 生成 16 张图（97 秒）
KA_ANIMA=<新模型> KA_MERGE_LORA=<turbo.safetensors> KA_LORA_STR=0.6 \
KA_DST=$HOME/npt/e2eper512miao KA_SCRATCH=/data/local/tmp/gscratchmiao \
python ~/npt/g512_param.py

# 3. 编译成 blob（49 秒！）
KA_DST=$HOME/npt/e2eper512miao KA_BLOBDIR=/data/local/tmp/blobs512miao \
python ~/npt/e512_param.py

# 4. 在 ~/npt/assets/overrides.json 登记 + 重启 ComfyUI
```

脚本：`F:\KAtorch\pcw\g512_param.py`、`e512_param.py`（原 g512m.py / e512m.py 的参数化版）。

### 三个必须知道的坑

1. **权重 key 前缀不同**：`anima_v1` 是 `net.blocks.0...`，`miaomiaoHarem_anima12` 是裸
   `blocks.0...`。张量数量（685）和形状完全一致，只是前缀差异。
   已在 `anima_block.py` 加 `_pick()` 前缀兼容，`katorch_npu/st.py` 也加了同样处理。
2. **文本编码器要跟着换**：微调底模连 `llm_adapter` 和 `t_embedder` 都改了（实测 md5 全不同）。
   所以 `KAtorchCLIPTextEncode` 加了可选 `model` 输入，连上就用该 checkpoint 的 llm_adapter。
   `enc.py` 也兼容了无 `net.` 前缀的 key。
3. **编译时间不是 HANDOFF 里写的 40 分钟**：16 块实测 **49 秒**（3.2-3.5 秒/块）。
   之前"单块 2-3 分钟"应该是冷启动或别的分辨率；blob 大小与旧版逐字节不同，确认编的是新权重。

### 两种底模并存

`overrides.json` 里两个都标了 verified，节点下拉框可以随时切换：

| 资产 id | 底模 | LoRA |
|---|---|---|
| `blobs512m` | anima_v1 | turbo 1.0 |
| `blobs512miao` | miaomiaoHarem_anima12 | turbo **0.6**（对齐官方 8 步工作流） |

实测新底模出图 186 秒/张（8 步 er_sde+beta），画风明显不同：更柔和写实、没有签名水印。

---


## 六·七 VAE 解码搬到 NPU（✅ 已完成，512×512）

**结果**：512×512 解码 **37s → 1.3s（28 倍）**，端到端出图 151s → 116s。
精度：对齐 torch CPU 版，fp16 全程，**8bit 像素差最大 2 个色阶，>2 的像素占 0.00%**，肉眼无差别。

### 4 段划分（latent 64×64 → 图 512×512）

| 段 | 内容 | 输入 | 输出 | 算子 | blob |
|---|---|---|---|---|---|
| 1 | **conv2(1×1)** + conv1 + middle(Res,Attn,Res) + 3×Res | (1,64,64,16) | (1,64,64,384) | 209 | 28 MB |
| — | **CPU 上采样 ×2** | (1,64,64,384) | (1,128,128,384) | — | — |
| 2 | resample conv(384→192) + 3×Res(192→384) | (1,128,128,384) | (1,128,128,384) | 103 | 16 MB |
| — | **CPU 上采样 ×2** | | (1,256,256,384) | — | — |
| 3 | resample conv(384→192) + 3×Res | (1,256,256,384) | (1,256,256,192) | 98 | 5 MB |
| — | **CPU 上采样 ×2** | | (1,512,512,192) | — | — |
| 4 | resample conv(192→96) + 3×Res + head | (1,512,512,192) | (1,512,512,3) | 113 | 1 MB |

段间耗时：s1 0.17s / s2 0.23s / s3 0.34s / s4 0.42s，CPU 上采样各 0.04~0.07s。

### 踩过的三个坑（都很隐蔽）

1. **`conv2` 是整条链路的第一层，容易漏**
   Wan VAE 的入口是 `conv1(conv2(z))`，其中 `conv2` 是 **16→16 的 1×1 卷积**，权重键在**顶层**
   `conv2.weight`（不在 `decoder.` 前缀下），形状 `(16,16,1,1,1)`。
   漏掉它 → 输出 std 从 0.2068 变成 0.4449，误差 178%，但图**看起来还是像个人**，很容易误判成"fp16 精度问题"。

2. **Resample（nearest 2× 上采样）在 NPU 上做不了**
   实现是 reshape + 5 维 CONCAT，而 **MDLA 的 CONCAT 只支持沿最后一维**，
   构造出的图带 5 维张量 + 第 3 维 concat → 编译直接 `rc=-516`。
   实测 hw=8/16/24/32/48/64 **全部失败**（不是尺寸问题，是写法就不被接受）。
   → 上采样挪到 CPU（`np.repeat` 两次，几 MB 内存拷贝，0.04~0.07s），NPU 只跑卷积。

3. **`x.shape` 解包会让 lowering 报 `getitem`**
   `vae_decoder.py` 的 `Resample.forward` 里 `n, h, w, c = x.shape`，fx 会把它 trace 成
   `getitem(args=(getattr_1, 0))`，lowering 直接拒绝。改成构造期常量：
   ```python
   n, h, w, c = 1, self.H // 2, self.W // 2, self.C
   ```
   注意 `WanVaeDecoder` 构造 Resample 时传的是**输出**尺寸（`H0 * 2**(i+1)`），所以输入要 `// 2`。

### 文件

| 路径 | 作用 |
|---|---|
| `F:\KAtorch\pcw\vae_npu_512b.py` | 生成 4 段图 + 编译 + 导出 blob（`KA_SEGS=1,2,3,4`） |
| `F:\KAtorch\pcw\vae_npu_run.py` | 链式跑 4 段、计时、和 torch 对比 |
| `F:\KAtorch\pcw\vae_seg_cmp.py` | 逐段精度对比（定位是哪一段崩的） |
| `F:\KAtorch\pcw\vae_diag.py` | 把段拆成单算子编译，定位不支持的 op |
| `katorch_npu/vae_npu.py` | 运行时封装：常驻 host、bload 一次、`decode()` |
| 手机 `~/npt/vae_npu512/s{1..4}.dla` | 4 个 blob（共 50 MB） |

### 接线

`runtime.vae_decode()` 里加了 `_vae_npu_decode()` 分支：

- 分辨率不是 512×512 → 直接走 CPU
- blob / host 缺失、host 异常 → 记日志后**静默回退 CPU**，不会让出图失败
- `KA_VAE_NPU=0` 环境变量可强制关掉
- 首次调用时 `bload` 只要 **0.07s**，host 进程常驻，后续 decode 稳定 1.30~1.35s

### 还能再快吗

- 段间走**文件 I/O**（i*.bin 最大 50 MB），改成共享内存能再省 0.3~0.5s，收益不大
- 4 段是**串行**的，各段有数据依赖，没法并行
- 段4 只有 0.42s，head 前的 conv 是瓶颈；进一步拆段收益很低

### 性能对照（同一张 512×512）

| 环节 | 之前 | 现在 |
|---|---|---|
| DiT 8 步 | 90s | **95s** |
| 文本编码 ×2 | ~25s ❌ 误记 | **5.5s**（实际 3.0 + 2.5） |
| 编码器加载（仅首张） | — | 3.1s |
| NPU 宿主装载（仅首张） | — | 2.75s |
| **VAE 解码** | **37s** | **1.3s** |
| **端到端（稳定态）** | 151s | **96s** |
| **端到端（冷启动首张）** | 151s | **107s** |

> ⚠️ 交接文档里写的「文本编码 ~25 秒」是**误记**。实测日志：
> `文本编码完成 3.0s（257 字符）` / `2.5s（155 字符）`，而且 ComfyUI 自带节点级缓存，
> 同 prompt 第二次直接跳过，**编码根本不是瓶颈**。

---

## 六·八、采样优化穷尽结论（2026-11-15）

**一句话：512×512 / 8 步 / 96 秒，就是这颗芯片这套模型的硬件极限。**

稳定态端到端 96 秒的构成：

```
采样      95 秒   ← 98.9%，每步 11.2~12.4s
VAE        1.3 秒
其它       0.7 秒
```

冷启动首张多出的 14 秒全是**一次性**开销（编码器加载 3.1s + 编码 5.5s + NPU 宿主 2.75s
+ 杂项），第二次就没了。

### 单步 12.2 秒的分解（实测 profile）

```
14 个 blob 的 run_block   10817.9 ms   ← 95.5%，每块 773ms
写下一个块的输入            163.3 ms
读输出+缩放                209.1 ms
fl（final layer）           41.3 ms
patchify                    25.1 ms
pe（patch embed）           23.3 ms
归一化+写盘                 15.4 ms
unpatchify                  11.4 ms
write_cond（时间嵌入）       10.4 ms
读 pe 输出                   4.5 ms
────────────────────────────────────
单步合计                 11328.9 ms
```

**map/unmap 只占 3.3%，patchify/unpatchify 0.3%**——都不是瓶颈。

### 真正的瓶颈：MDLA 的访存带宽

实测稳态带宽：

```
1024×2048 ×32 个 MUL    46.4 ms  → 8.7 GB/s（稳态上限）
4096×4096 ×1            37.2 ms  → 2.7 GB/s（大张量更差）
```

比手机 LPDDR5（50+ GB/s）低一个数量级，**是 MDLA 自己的访存带宽限制**。

算总账：

```
单块搬运 5303 MB × 14 块 = 74 GB / 步
74 GB ÷ 8.7 GB/s = 8.5 秒    ← 纯带宽理论下限
实测每步 10.8 秒              ← 已跑到理论值的 79%
```

### 单算子成本（实测）

| 算子 | 单次耗时 | 说明 |
|---|---|---|
| MUL（4.2MB） | 1.39 ms | 增量法，反推 9.1 GB/s |
| **RESHAPE** | **0** | 编译期消除，16 个只要 4ms |
| BATCH_MATMUL（0.27GFLOP） | 3.0 ms | 仅 89 GFLOPS |
| FC（大） | 8.6 ms | 但整图 FC 只占搬运量 1.1% |
| 每算子固定开销 | ~0.6 ms | 从总账反推 |

### 所有被否决的方向（**别再重走**）

| 方向 | 结论 | 原因 |
|---|---|---|
| INT8 权重 | ❌ | 硬件只快 1.14x（8.6ms → 7.6ms） |
| INT8 全量 | ❌ | 精度崩，每层 22~78% 误差 |
| 消除 RESHAPE（190个/33%） | ❌ | 编译期已消除，**零成本** |
| 减少 MUL/ADD | ⚠️ | 手动融合最多省 3.4%（134MB/3935MB） |
| map/unmap 优化 | ❌ | 只占 3.3% |
| **合并块（14→更少）** | ❌ | 常量 1158MB 逼近 IOVA 1.35GB，build 直接 `-314` |
| cross-attn K/V 复用 | ❌ | 实测只省 **0.65%**（ctx 才 512 token） |
| 算子融合（通用） | ❌ | MDLA 无融合机制，图格式里无 in-place/fused 标记 |
| 文本编码缓存 | ❌ | 编码只要 3s，且 ComfyUI 自带节点缓存 |

**合并块失败的完整证据**：把 b00+b01 拼成一张图（operand 重编号、输入重定向），
图本身合法（2050 operand、无重复、无缺失引用），但 build 返回 `-314`
（`M_addOp`→114）。根因是**常量必须常驻 IOVA**：

```
b00 常量 449 个 / 579 MB
b01 常量 449 个 / 579 MB
合并      898 个 / 1158.5 MB   ← 超 1.35GB 配额
```

**14 块的切分不是为了迁就 map/unmap，而是被 IOVA 逼的。**

### 只剩「换策略」

| 方案 | 预估 | 代价 |
|---|---|---|
| 384×384 | 数据量降到 56%，约 70 秒 | 重编 16 块，构图能力变弱 |
| 6 步采样 | 约 87 秒 | 画质下降 |
| **保持现状** | **96 秒** | 无（当前选择） |

### ⚠️ 血泪教训：不要在后端运行时加载模型

我在排查时写了个 `profile_e2e.py`，在 **ComfyUI 后端还在跑**的情况下又加载了一整套模型，
导致 OOM → 内核 panic → 手机重启。dmesg 原文：

```
[persist.sys.boot.reason.history] = [kernel_panic,system_is_deadloomld_on_oomory...
```

**正确做法**：要么先停掉 ComfyUI 再跑外部脚本，要么把计时逻辑写进节点代码里
（让 ComfyUI 进程内部跑）。后者更安全，本轮就是这么做的。


## 七、常用命令
### 启动 ComfyUI

```sh
su -c "setsid sh ~/npt/comfy-launch-root.sh > ~/npt/comfy.log 2>&1 < /dev/null &"
```

**必须的环境变量**（脚本里已带）：

```sh
export LD_PRELOAD=/data/data/com.termux/files/usr/lib/libpython3.14.so
python main.py --cpu --listen 0.0.0.0 --port 8188
```

- 不加 `LD_PRELOAD` → `tokenizers` 报 `cannot locate symbol "PyExc_Exception"`
- 不加 `--cpu` → `Torch not compiled with CUDA enabled`

### 快捷方式（Termux:Widget）

```
~/.shortcuts/comfy-start.sh     # 启动 ComfyUI + 打开浏览器
~/.shortcuts/katorch-start.sh   # 启动原简化版（8770）
```

### 查看状态

```sh
curl -s http://127.0.0.1:8188/object_info | python3 -c "import json,sys;print(len(json.load(sys.stdin)))"
```

### 常用环境变量

| 变量 | 默认 | 作用 |
|---|---|---|
| `KA_SAMPLER` | `er_sde` | `euler` / `er_sde` |
| `KA_SDE_STAGE` | `3` | er_sde 的阶数 1-3 |
| `KA_S_NOISE` | `1.0` | SDE 噪声强度 |
| `KA_SCHED` | `beta` | `beta` / `linear` |
| `KA_BETA_A` / `KA_BETA_B` | `0.6` | beta 调度参数 |
| `KA_STEPS` | `8` | 采样步数 |
| `KA_ANIMA` | ComfyUI 里的 anima_v1 | DiT 权重路径 |
| `KA_VAE` | ComfyUI 里的 qwen_image_vae | VAE 路径 |

### 源码位置

| 文件 | 主机路径 |
|---|---|
| `gen.py` | `F:\KAtorch\pcw\app\gen.py` |
| `enc.py` | `F:\KAtorch\pcw\app\enc.py` |
| `comfy_npu_node.py` | `F:\KAtorch\pcw\app\comfy_npu_node.py` |
| `host.c`（NPU 驱动） | `F:\KAtorch\PC编译\host.c` |
| 各类脚本 | `F:\KAtorch\pcw\scripts\` |

---

## 八、注意事项

### 8.1 不要在手机上做的事

- ❌ **跑 800×1424 的原生 CPU 工作流** —— 会加载 7.9GB 模型把手机拖死（真实事故）
- ❌ **用 Chrome 开 ComfyUI** —— 比 Via 慢很多，用 Via
- ❌ **忘记开 VPN 就装包** —— 手机没外网时 pip 会失败

### 8.2 会被覆盖的文件

ComfyUI 升级时这两个会丢，需要重跑脚本恢复：

- `site-packages/comfyui_frontend_package/static/sw.js`（PWA 支持）
- `site-packages/comfyui_frontend_package/static/index.html`（全屏按钮 + SW 注册）

脚本：`F:\KAtorch\pcw\app\comfy_add_sw.py` 和 `comfy_add_fullscreen.py`

### 8.3 重启后的恢复

```sh
# 1. hosts（重启会丢 mount）
mount -o bind /data/local/tmp/hosts_katorch /system/etc/hosts

# 2. 启动 ComfyUI
su -c "setsid sh ~/npt/comfy-launch-root.sh > ~/npt/comfy.log 2>&1 &"
```

文件本身不会丢（都在 `/data/data/com.termux/files/home/` 下）。

### 8.4 关于 su

Termux 的 root 权限是**改过 Magisk 策略**的：

```sh
magisk --sqlite "UPDATE policies SET policy=2 WHERE uid=10171;"
```

原值是 `policy=1`（拒绝）。**改回来会破坏 ComfyUI 启动**（它需要 root 跑）。

---

## 九、给下一个人的话

这个项目最大的教训是：**先验证硬件能力边界，再设计架构**。

我花了整整一天做 INT8 量化，全部打通、精度做到 2.3%，最后发现硬件只有 1.14 倍加速——
如果先做那个 `phone_fc_speed.py` 的 20 行测试，一天就省下来了。

同样，`npu_hook_node` 那个逐层加速的想法，在跑第一次就有 22% 误差的时候就该放弃。

**所以做模块化之前，先花半天写个探针，量清楚**：

- 一个 FC 实际多快（已有数据：8.6ms / 1024×2048×2048）
- BATCH_MATMUL 多快
- map/unmap 到底占多少
- 512 / 768 / 1024 各分辨率能不能编出来、能不能跑

**别急着写节点框架。**
