# KAtorch 调用说明

> 给想用 KAtorch 加速自己模型的人（比如 TTS 项目从 NNAPI 迁过来）。
> 最后更新：2026-11-15

---

## 〇、先说清楚 KAtorch 是什么

**KAtorch 不是框架，是一套「把 PyTorch 模型静态编译到 MediaTek MDLA NPU 上跑」的工具链。**

它有四层，你要用哪层取决于你有多大耐心：

```
┌─────────────────────────────────────────┐
│ katorch_npu/   ComfyUI 节点封装（13 个）  │  ← 最省事，但只能跑 DiT/VAE
├─────────────────────────────────────────┤
│ runtime.py     缓存 / 资源管理            │  ← Python 封装，通用
├─────────────────────────────────────────┤
│ 编译器/         PyTorch → 图文本          │  ← torch.fx 自动转换
│   lowering.py                            │
├─────────────────────────────────────────┤
│ host_new        C 运行时（编译+执行）      │  ← 最底层，任何模型都能跑
└─────────────────────────────────────────┘
```

**对 TTS 这种自定义模型，你应该走第 3 层（lowering.py）+ 第 4 层（host_new）。**

---

## 一、硬件与前提

### 支持的芯片

**MediaTek MDLA 5.0 及以上**（APUSys 框架）。

实测已跑通：
- MT6897 / Dimensity 8300（OB-A98，Android 15）

**理论上**同代或更新的 MTK 芯片都行，但**必须实测**——不同芯片的 opcode 支持度不一样。

### 判断你的手机有没有

```sh
# 看有没有 MDLA 设备节点
ls -l /dev/mtk_mdla*  /dev/apusys*  2>/dev/null

# 看有没有 NeuroPilot 库
find /vendor -name "libneuron*" 2>/dev/null
```

**注意：必须 root**。Termux 普通权限拿不到 NPU 设备。

### 编译 host

host 是个 C 程序，要在目标设备上编译（或交叉编译）：

```sh
# 手机上的 Termux 里
pkg install clang
clang -O2 -o host_new host.c -ldl
```

**需要链接的库**（在 `/vendor/lib64/`）：
- `libneuron_adapter.so` —— NeuronModel API
- `libneuron_platform.so` —— 设备管理
- `libdl` —— 动态加载

**host.c 自己用 `dlopen` 加载**，所以编译时不用显式链接。

---

## 二、5 分钟跑通：最小的例子

### 2.1 手写一个最简单的图

新建 `add.txt`（这里用 256×512，编译快，方便验证；换成 1024×2048 也一样能跑）：

```
OPD 0 8 2 256 512 0 0 i
OPD 1 8 2 256 512 0 0 w b.bin
OPD 2 8 2 256 512 0 0 o
OPD 3 4 1 1 0 0 a 0
OP 0 3 0 1 3 1 2
IO 1 0 1 2
END
```

**逐行解释**：

| 行 | 含义 |
|---|---|
| `OPD 0 8 2 256 512 0 0 i` | operand 0：fp16(type=8)，2 维 [256,512]，scale=0，zp=0，`i`=输入 |
| `OPD 1 ... w b.bin` | operand 1：从文件 `b.bin` 读常量 |
| `OPD 2 ... o` | operand 2：输出 |
| `OPD 3 4 1 1 0 0 a 0` | operand 3：int32(type=4)，1 维 [1]，立即数 0（激活码） |
| `OP 0 3 0 1 3 1 2` | 算子：ADD(opcode=0)，3 个输入 [0,1,3]，1 个输出 [2] |
| `IO 1 0 1 2` | 图接口：1 个输入(operand 0)，1 个输出(operand 2) |
| `END` | 结束 |

**造一个 `b.bin`**：

```python
import numpy as np
np.zeros((256, 512), dtype=np.float16).tofile("b.bin")
```

### 2.2 跑起来

host 是**逐行读 JSON 命令**的交互程序。**启动时它会先吐一行 banner**：

```json
{"ok":true,"devices":["mtk-gpu","mtk-dsp","mtk-mdla"],"ready":true}
```

**这行一定要先读掉**，否则后面所有命令都会错位。banner 里还告诉你了这台机器有哪些加速器可用。

用 Python 驱动：

```python
import json, subprocess, time
import numpy as np

HOST = "/data/data/com.termux/files/home/npt/host_new"

p = subprocess.Popen([HOST], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.DEVNULL, text=True, bufsize=1)
p.stdout.readline()          # ★ 吃掉 banner，必须

def cmd(s):
    p.stdin.write(s + "\n")
    p.stdin.flush()
    return p.stdout.readline().strip()

# 1. 加载图
print(cmd('{"cmd":"build","path":"/tmp/add.txt"}'))
# → {"ok":true,"ops":1,"slot":0}

# 2. 编译（第一次慢，之后走缓存）
print(cmd('{"cmd":"compile"}'))
# → {"ok":true,"rc":0}

# 3. 执行
spec = "1 0 /tmp/a.bin 1 /tmp/out.bin"
t0 = time.time()
print(cmd('{"cmd":"run","spec":"%s"}' % spec))
print("耗时 %.1f ms" % ((time.time() - t0) * 1000))

# 4. 退出
p.stdin.write('{"cmd":"quit"}\n'); p.stdin.flush()
p.wait()
```

**实测输出**（本机已逐位验证）：

```
build  : {"ok":true,"ops":1,"slot":0}
compile: {"ok":true,"rc":0}
run    : {"ok":true,"rc":0}
和输入的差: 0.0      ← a + 0 = a，逐位精确
```

**`run` 的 spec 格式**：

```
<n_in> <slot> <path> <slot> <path> ... <n_out> <path> [<path> ...]
```

- `n_in`：输入个数
- 然后每组 `<输入槽号> <文件路径>`
- `n_out`：输出个数
- 然后 `n_out` 个输出文件路径

---

## 三、host 协议完整参考

### 3.1 命令表

| 命令 | 参数 | 作用 | 返回 |
|---|---|---|---|
| `ping` | — | 心跳 | `{"ok":true,"pong":1}` |
| `build` | `path` | 加载图文本 | `{"ok":true,"ops":N,"slot":N}` |
| `compile` | — | 编译成可执行 | `{"ok":true,"rc":0}` |
| `run` | `spec` | 执行一张图 | `{"ok":true,"rc":0}` |
| `chain` | `spec` | **多图串联**（内存直传，省文件 I/O） | `{"ok":true,"rc":0}` |
| `create` | — | 手动建一个空 model | `{"ok":true,"rc":0}` |
| `export` | `path` | 导出编译好的 blob | `{"ok":true,"bytes":N}` |
| `restore` | `path` | 从 blob 恢复（跳过 build+compile） | — |
| `use` | `slot` | 切换模型槽 | — |
| `pop` | — | 释放当前槽（回收 IOVA） | `{"ok":true,"freed_slot":N,...}` |
| `bload` | `list` | 批量加载 blob 列表文件 | `{"ok":true,"n":N}` |
| `map` | `idx` | 映射第 idx 个 blob | `{"ok":true,"nin":N,"nout":N}` |
| `unmap` | — | 解除映射 | — |
| `cachedir` | `path` | 设编译缓存目录 | — |
| `cachekey` | `v` | 设缓存 key | — |
| `relax` | `0/1` | fp32 是否降级 fp16 | — |
| `nodev` | `0/1` | 是否不指定设备 | — |
| `poolclear` | — | 清空内存池 | — |
| `ops` | — | 列出已注册算子 | — |
| `dump` | `dir` | 导出中间张量 | — |
| `quit` | — | 退出 | — |

### 3.2 两条执行路径

**路径 A：build + compile（开发时用）**

```json
{"cmd":"build","path":"/path/to/g.txt"}
{"cmd":"compile"}
{"cmd":"run","spec":"..."}
```

- 每次都要解析图文本、编译
- **编译很慢**（一层 DiT 要 1.8 秒，大图几分钟）

**路径 B：bload + map（生产用）**

```json
{"cmd":"bload","list":"/path/to/blobs.txt"}
{"cmd":"map","idx":0}
{"cmd":"run","spec":"..."}
```

- `blobs.txt` 里每行一个 blob 路径
- 直接内存映射，**毫秒级**
- **这是部署时的正确姿势**

### 3.3 错误码

| 返回码 | 含义 | 处理 |
|---|---|---|
| `-101` | 图文件打不开 | 检查路径 |
| `-102` | M_create 失败 | 设备没初始化 |
| `-104~-106` | 常量文件读取失败 | 检查 `w` 指向的文件 |
| `-200-rc` | `M_addOp` 失败（OPD 定义） | 看下面的 `-314` |
| `-300-rc` | `M_setVal` 失败（常量） | 检查常量大小和类型 |
| `-310-rc` | 立即数设置失败 | 检查 `a` 的写法 |
| `-400-rc` | `M_addOpn` 失败（OP 定义） | 多为算子不支持 |
| **`-314`** | **`M_addOp` 返回 114** | **常量总量超 IOVA 配额** |
| `-507` | 编译失败 | 算子不支持或 shape 不合法 |
| `-516` | **UNMAPPABLE** | 图里有 MDLA 不支持的算子组合 |
| `-599` | 没有编译好的模型 | 先 `compile` |
| `-601~-603` | 输入文件问题 | 检查 spec 路径 |

---

## 四、图格式规范

### 4.1 语法

```
OPD <id> <type> <nd> <dim1> ... <dim_nd> <scale> <zp> [<tail>]
OP  <opcode> <n_in> <in_id...> <n_out> <out_id...>
IO  <n_in> <in_id...> <n_out> <out_id...>
END
```

### 4.2 类型码

| 值 | 类型 | 用途 |
|---|---|---|
| `0` | FLOAT32 标量 | 少用 |
| `1` | **INT32 标量** | CONV 的 pad/stride/act 参数、nd=0 标量 |
| `3` | TENSOR_FLOAT32 | 会被 `relax 1` 降成 fp16 |
| `4` | **TENSOR_INT32** | 激活码、shape 数组、索引 |
| `8` | **TENSOR_FLOAT16** | **主力类型** |

### 4.3 尾部标记（tail）

| 写法 | 含义 |
|---|---|
| `i` | 图输入 |
| `o` | 图输出（中间结果也行） |
| `w <path>` | 从文件读常量权重 |
| `a <int>` | 立即数（配合 type 1 或 4） |

### 4.4 opcode 表（实测可用）

| code | 名称 | 说明 |
|---|---|---|
| 0 | ADD | 3 输入（含激活码） |
| 1 | AVGPOOL | |
| 2 | CONCAT | **MDLA 对 H/W 维支持差**，用前先测 |
| 3 | CONV_2D | 参数多，见下 |
| 9 | FC | **4 输入**：input, weight, bias, act |
| 14 | LOGISTIC | 输出 scale 必须 `1/32768` |
| 17 | MAXPOOL | |
| 18 | MUL | 3 输入（含激活码） |
| 19 | RELU | |
| 22 | RESHAPE | **编译期消除，零成本** |
| 25 | SOFTMAX | 输出 scale 必须 `1/32768` |
| 28 | TANH | |
| 30 | DIV | |
| 31 | MEAN | ⚠️ **INT16 下误差 117%** |
| 36 | SUB | |
| 37 | TRANSPOSE | |
| 38 | ABS | |
| 45 | CAST | ⚠️ **不支持跨 INT16↔FP16** |
| 49 | EXP | |
| 60 | LOG | |
| 64 | LOG_SOFTMAX | |
| 65 | MAXIMUM | |
| 66 | MINIMUM | |
| 67 | NEG | |
| 77 | REDUCE_MAX | ⚠️ **INT16 下误差 63%** |
| 80 | REDUCE_SUM | ⚠️ **INT16 下误差 200%** |
| 83 | RSQRT | |
| 85 | SIN | |
| 88 | SQRT | |
| 102 | BATCH_MATMUL | 4 输入 |

### 4.5 特殊算子的参数

**FC（9）：必须 4 个输入**

```
OP 9 4 <input> <weight> <bias> <activation_code> 1 <output>
```

- `activation_code` **必须是 type 4**
- **写成 type 1 会静默直通**（输出 == 第一个输入），不报错
- 只有 FC 的**第 2 个输入**可以是 INT8 per-channel，其他常量必须 INT16

**CONV（3）：参数都是 nd=0 的 INT32 标量**

```
OP 3 7 <in> <w> <b> <pad> <stride> <act> <dilation> 1 <out>
```

pad/stride 必须是 `scalar0()`（nd=0），用 type 1。

**RESHAPE（22）：第二个输入是 shape 文件**

```
OP 22 2 <in> <shape_const> 1 <out>
```

`shape_const` 是一个 **type 4 的 int32 数组文件**，内容就是目标 shape：

```python
np.array([1, 1024, 2048], np.int32).tofile("s.bin")
```

`OPD` 里声明成 `OPD <id> 4 1 3 0 0 w s.bin`（nd=1，dims=[3]，3 个 int32）。

### 4.6 输入输出格式

```
IO <n_in> <id...> <n_out> <id...>
```

**放在所有 OPD 之后、OP 之前**（顺序其实无所谓，但这是惯例）。

---

## 五、用 lowering.py 从 PyTorch 建图

### 5.1 基本用法

```python
import sys
sys.path.insert(0, "/data/data/com.termux/files/home/npt/编译器")
from lowering import Lowering

import torch
import torch.nn as nn

class MyModel(nn.Module):
    def __init__(self):
        super().__init__()
        self.fc1 = nn.Linear(256, 512)
        self.fc2 = nn.Linear(512, 256)
    def forward(self, x):
        return self.fc2(torch.relu(self.fc1(x)))

m = MyModel().eval()

low = Lowering("/tmp/mymodel")
x = low.input((1, 256))              # 声明输入形状
low.lower_module(m, [x])             # 自动 symbolic_trace + 遍历
low.validate()                       # 校验 shape

open("/tmp/mymodel/g.txt", "w").write(low.graph_text())
```

### 5.2 它会自动做什么

`lower_module` 内部：

```python
gm = fx.symbolic_trace(mod)          # PyTorch → FX 图
ret = self._walk(gm, inputs)         # 逐节点转换成 NPU 算子
self.output(ret)                      # 标记输出
```

**支持的 PyTorch 操作**（`_node` 里处理）：
- `operator.add/sub/mul/div/truediv`
- `torch.relu` / `torch.nn.functional.relu`
- `torch.nn.functional.silu`
- `torch.tanh` / `torch.sigmoid` / `torch.exp` / `torch.log` / `torch.sqrt`
- `torch.softmax` / `torch.mean` / `torch.sum`
- `torch.matmul` / `torch.bmm`
- `torch.transpose` / `.permute()` / `.reshape()` / `.view()`
- `torch.nn.Linear` / `torch.nn.Conv2d` / `torch.nn.RMSNorm`（部分）

**不支持的会抛 `Unsupported` 异常**，你需要在模型里改写。

### 5.3 手动算子（精细控制时用）

```python
low = Lowering("/tmp/out")
x = low.input((1024, 2048))
w = low.const(torch.randn(2048, 2048))     # 常量权重
b = low.const(torch.zeros(2048))
y = low.linear(x, my_linear_module)        # 直接生成 FC
z = low.rmsnorm(y, my_norm_module)         # 直接生成 RMSNorm 序列
out = low.op(18, [z, x, low.act0()])       # 手动 MUL
low.output(out)
```

**可用的高阶方法**：

| 方法 | 作用 |
|---|---|
| `linear(x, mod)` | 生成 FC（自动处理 4 输入） |
| `rmsnorm(x, mod)` | 生成完整 RMSNorm |
| `rmsnorm_seq(x, weight)` | 同上，直接给权重 |
| `silu(x)` | SiLU（sigmoid × x） |
| `conv2d(...)` | 生成 CONV_2D |
| `transpose(x, d0, d1)` | 转置 |
| `reshape(x, shape)` | reshape |
| `matmul(a, b)` | 矩阵乘（自动选 FC 或 BATCH_MATMUL） |
| `softmax5(x, axis)` | softmax |
| `scalar(v)` | int32 标量（1 维） |
| `scalar0(v)` | int32 标量（0 维，CONV 参数用） |
| `const(arr)` | fp16 常量 |
| `const_i32(arr)` | int32 常量 |

---

## 六、TTS 场景特别提示

### 6.1 TTS 模型的特点

跟 DiT 完全不同：

| | DiT | TTS（VITS/FastSpeech 类） |
|---|---|---|
| 输入 | latent [1,16,64,64] | phoneme 序列 [1, T] |
| 层结构 | 28 层 Transformer | Conv + LSTM/Transformer + Conv |
| 输出 | latent | 音频波形 [1, T×hop] |
| 关键算子 | BATCH_MATMUL, FC | **CONV_1D**, CONV_TRANSPOSE, LSTM |

### 6.2 你大概会撞上的问题

**① CONV_1D 没有直接支持**

MDLA 只有 `CONV_2D`（opcode 3）。**必须把 1D 卷积改写成 2D**：

```python
# 原始
nn.Conv1d(256, 256, kernel_size=5, padding=2)

# 改成（H=1 的 2D）
nn.Conv2d(256, 256, kernel_size=(1, 5), padding=(0, 2))
# 输入从 [B, C, T] reshape 成 [B, C, 1, T]
```

**这是必须做的改造**，否则 lowering 直接抛异常。

**② 动态长度是死穴**

MDLA 是**静态图**，序列长度 T 必须在编译时固定。

TTS 的文本长度是变的 → **你只能：**
- 固定几档长度（比如 64/128/256/512），每档编一份图
- 或者 padding 到最近的一档

**这是最大的架构限制**，要提前设计。

**③ CONV_TRANSPOSE（上采样）**

语音合成里常用。MDLA 的支持情况**没测过**，需要你自己验证。

**如果不行**，替代方案：
- 用 `RESIZE_BILINEAR`（如果有）
- 或者用**相位方式**：把 transposed conv 拆成 4 个 stride-1 卷积 + 重排（这种方式在手机 NPU 上很常见）

**④ LSTM**

MDLA **大概率不支持**。如果你的 TTS 用 LSTM，得改成：
- Transformer
- 或者 Conv1D + GLU（Conformer 风格）

### 6.3 建议的迁移路径

**不要一次全迁**。按这个顺序：

```
第 1 步：把模型里最重的一块（通常是 decoder）单独拎出来
        用 lowering.py 试转，看有没有 Unsupported

第 2 步：把它改成 CONV_2D 形式（1D → 2D）
        在 PC 上用 torch 验证数学等价

第 3 步：编图 + 上机跑单块
        和 torch 结果逐位对比（误差应该 < 1%）

第 4 步：再迁第二块，用 chain 串联

第 5 步：最后迁 vocoder（通常是 HiFi-GAN 类，全卷积，最适合 NPU）
```

### 6.4 从 NNAPI 迁过来要注意什么

**NNAPI 和 MDLA 的关键差异**：

| | NNAPI | MDLA（KAtorch） |
|---|---|---|
| 抽象层 | 高（自动分图、自动回退 CPU） | 低（你自己分、没有回退） |
| 动态 shape | 部分支持 | **完全不支持** |
| 子图划分 | 自动 | **手动**（每张图一个 blob） |
| 算子融合 | 运行时决定 | **编译期决定，你控制不了** |
| 精度 | 自动混合 | **你要自己选 fp16/INT16** |
| 错误信息 | 还行 | **基本只有错误码** |

**最容易踩的坑**：NNAPI 会自动把不支持的算子丢给 CPU，**MDLA 不会**——一个算子不支持，整张图编不出来。

**所以迁移时的核心工作是**：找出你模型里哪些算子 MDLA 不支持，然后**手工改写**。

### 6.5 用 chain 减少文件 I/O

如果你的 TTS 分成几块，**别用多次 `run`**（每次都要读写文件）。

用 `chain`：

```json
{"cmd":"chain","spec":"..."}
```

**格式和 `run` 一样**，但中间结果**直接在内存传递**，不落盘。

**收益**：如果是 5 块的模型，能省掉 4 次「写 + 读」，每次涉及几十 MB。

---

## 七、血泪清单（一定要看）

### 7.1 这些算子**都有**，别被吓到

先澄清一个容易误解的点：**MEAN / SQRT / RSQRT / EXP 全都有，而且 fp16 下精度很好。**
DiT 的图里就在用它们（MEAN 26 个、RSQRT 20 个、EXP 4 个）。

**实测精度（fp16，输入 4096 个随机数）**：

| 算子 | opcode | 中位相对误差 | 最大相对误差 | 判定 |
|---|---|---|---|---|
| `SQRT` | 88 | 0.017% | 0.072% | ✅ 精确 |
| `RSQRT` | 83 | 0.022% | 0.085% | ✅ 精确 |
| `EXP` | 49 | 0.017% | 0.089% | ✅ 精确 |
| `REDUCE_MAX` | 77 | 0.018% | 0.045% | ✅ 精确 |
| `MEAN` | 31 | 0.029% | 见下 | ✅ 可用 |

**MEAN 要特别说明**：它的"最大相对误差"能到 4~35%，但**这是假象**——
出现在「这一行的均值本身接近 0」的地方，相对误差被放大了。

实测绝对误差：

```
最大绝对误差  0.000080
中位绝对误差  0.000011
```

**误差最大的那行**：真值 `+0.000447`，得到 `+0.000429`，绝对差 `0.000018`。
真值本身就接近 0，所以相对误差算出来 4%。

**结论：MEAN 完全可用**，只要你不拿它去做「必须精确到相对 1% 的接近零的量」。

### 7.2 真正的问题只在 INT16 量化下

下面这些是**只有走到 INT16 量化路线才出现**的问题（fp16 完全没事）：

| 算子 | INT16 下误差 |
|---|---|
| `MEAN` | **117%** |
| `REDUCE_MAX` | **63%** |
| `REDUCE_SUM` | **200%（连符号都反了）** |

**同样的图在 fp16 下是精确的。**

**所以：如果你不打算做 INT16 量化，这一段可以直接跳过。**

**而且我实测过，INT8/INT16 量化在这颗芯片上净收益为负**（FC 只快 1.14x），
**所以大概率你根本不需要碰量化**。

**万不得已要量化时的绕法**：
- `sum(x)` → `FC(x, ones(1,N))`（实测误差 0.0000%）
- `max(x)` → 只能留在 FP16 岛里

### 7.3 MEAN / REDUCE 的正确写法（**3 个输入**）

这是最容易写错的地方。归约算子不是 2 个输入，是 **3 个**：

```
OP 31 3 <input> <axis_const> <act_code> 1 <output>
```

- `input`：数据
- `axis_const`：**type 4**，`a -1`（沿最后一维）或具体轴号
- `act_code`：**type 4**，`a 0`

**完整例子**（[512,256] → 每行求均值 → [512,1]）：

```
OPD 0 8 2 512 256 0 0 i
OPD 1 4 1 1 0 0 a -1
OPD 2 4 1 1 0 0 a 0
OPD 3 8 2 512 1 0 0 o
OP 31 3 0 1 2 1 3
IO 1 0 1 3
END
```

**少给一个输入会直接 `-516 UNMAPPABLE`**，而且错误信息不会告诉你是少了参数。

同理 `REDUCE_SUM`(80) / `REDUCE_MAX`(77) 也是 3 输入。

### 7.4 精度切换的硬限制

- **`CAST(45)` 不支持跨 INT16↔FP16**，会编译失败
- 但 **`DEQUANTIZE(6)`（INT16→FP16）和 `QUANTIZE(72)`（FP16→INT16）可以**
- 实测 `INT16→DQ→MUL→Q→INT16` 误差 0.0184%

### 7.5 其他坑

- **FC 必须 4 个输入**，少了会 UNMAPPABLE
- **activation_code 必须是 type 4**，写成 type 1 会**静默直通**（不报错，结果全错）
- **`LOGISTIC`/`SOFTMAX` 的输出 scale 必须是 `1/32768`**
- **`RESHAPE`/`TRANSPOSE`（SHAPE_ONLY）要求输入输出共享 scale**
- **编译命中缓存时无法导出**（`getCompiledNetworkSize` 返回 -7），必须冷编译
- **同一个进程里重复编译会 `-514 RESOURCE_EXHAUSTED`**，要重启宿主

### 7.6 IOVA 是全局配额（**最容易踩**）

```
dma_buf_map_attachment -12  → IOVA 满
```

总量约 **1.35 GB**。常量（`w` 文件）必须常驻。

**实测**：一层 DiT 的常量 579 MB，两块合并就 1158 MB，**直接超限**（`-314`）。

**所以你的 TTS 模型如果很大，必须切片**，每片常量控制在 500 MB 以内。

用 `pop` 可以回收。

### 7.7 性能预期（别抱太大期望）

**实测 MDLA 的稳态带宽只有 8.7 GB/s**（比 LPDDR5 的 50+ GB/s 低一个数量级）。

**这意味着**：如果你的模型是**带宽受限**的（大量逐元素运算、大量中间张量），加速比会很有限。

**实测参考**：

| 算子 | 耗时 | 有效算力 |
|---|---|---|
| MUL（4.2MB） | 1.39 ms | 9 GB/s |
| BATCH_MATMUL（0.27GFLOP） | 3.0 ms | 89 GFLOPS |
| FC（1024×2048×2048） | 8.6 ms | ~1 TFLOPS |
| **INT8 FC（同样规模）** | **7.6 ms** | **只快 1.14x** |

**注意最后一行**：MDLA 的 INT8 加速比只有 **1.14 倍**，不是理论上的 2 倍。
**如果你以为量化能翻倍，会失望的。**

**算力密集的层**（大 matmul）能到 1 TFLOPS，**值得上 NPU**；
**带宽密集的层**（逐元素、norm）反而不如留在 CPU。

---

## 八、一个完整的可运行模板

```python
#!/usr/bin/env python3
"""KAtorch 通用调用模板。改 MODEL_PATH / 输入形状即可用。"""
import json, os, subprocess, time
import numpy as np

HOST = "/data/data/com.termux/files/home/npt/host_new"
WORK = "/data/local/tmp/katorch_run"
os.makedirs(WORK, exist_ok=True)


class KAtorchHost:
    """host_new 的 Python 封装。"""

    def __init__(self, host=HOST):
        self.p = subprocess.Popen([host], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  text=True, bufsize=1)
        self.p.stdout.readline()          # banner

    def cmd(self, s):
        self.p.stdin.write(s + "\n")
        self.p.stdin.flush()
        return self.p.stdout.readline().strip()

    def build(self, graph_path):
        r = self.cmd('{"cmd":"build","path":"%s"}' % graph_path)
        return json.loads(r)

    def compile(self):
        r = self.cmd('{"cmd":"compile"}')
        return json.loads(r)

    def run(self, inputs, outputs):
        """inputs: [(slot, path), ...]  outputs: [path, ...]"""
        spec = "%d %s %d %s" % (
            len(inputs),
            " ".join("%d %s" % (s, p) for s, p in inputs),
            len(outputs),
            " ".join(outputs))
        t0 = time.time()
        r = json.loads(self.cmd('{"cmd":"run","spec":"%s"}' % spec))
        r["elapsed_ms"] = (time.time() - t0) * 1000
        return r

    def close(self):
        try:
            self.p.stdin.write('{"cmd":"quit"}\n')
            self.p.stdin.flush()
            self.p.wait(timeout=30)
        except Exception:
            self.p.kill()


def demo():
    h = KAtorchHost()
    try:
        print("build:", h.build("/tmp/add.txt"))
        print("compile:", h.compile())

        a = np.random.randn(1024, 2048).astype(np.float16)
        a.tofile(WORK + "/a.bin")

        r = h.run([(0, WORK + "/a.bin")], [WORK + "/out.bin"])
        print("run:", r)

        out = np.fromfile(WORK + "/out.bin", dtype=np.float16)
        print("输出 shape:", out.shape, "均值:", out.mean())
    finally:
        h.close()


if __name__ == "__main__":
    demo()
```

---

## 九、出问题了怎么排查

### 9.1 编译失败（-507 / -516）

**-516 UNMAPPABLE** 是最常见的。原因通常是：

1. **算子组合不支持**（比如某个 norm 的写法 MDLA 认不了）
2. **INT16 用了坏算子**（MEAN/REDUCE_SUM 等）
3. **输入输出 scale 不一致**

**排查方法**：**二分法**。把图切一半，看哪一半能编。

图文本就是纯文本，**直接删行**就能试。

### 9.2 结果不对但能跑

**最可能**：activation_code 写错了类型（type 1 而不是 4），会**静默直通**。

**验证**：输出和第一个输入完全一样 → 就是这个原因。

### 9.3 性能不如预期

**先测带宽**。用一个简单的 MUL 图，测出你这个芯片的实际带宽：

```python
# 用 /data/local/tmp 的 bw.py 脚本（本仓库 tools/bench_bandwidth.py）
```

如果测出来是 8~9 GB/s 级别，说明是 MDLA 的固有特征，**不是你的代码问题**。

### 9.4 反复崩溃/重启

**看 dmesg**：

```sh
su -c "dmesg -T | grep -iE 'oom|mdla|apusys' | tail -20"
```

**如果看到 `system_is_deadloomld_on_oomory`** → 内存耗尽，kernel panic。

**常见原因**：
- 同时跑了两套模型（比如 ComfyUI 后端 + 外部脚本）
- 图的常量太大，加载时爆内存

---

## 十、参考文件位置

| 文件 | 路径 |
|---|---|
| host 源码 | `F:\KAtorch\PC编译\host.c` |
| 编译器 | `F:\KAtorch\编译器\lowering.py` |
| shape 推导 | `F:\KAtorch\编译器\shape_infer.py` |
| DiT 建模参考 | `F:\KAtorch\KAdit\` |
| 现成的图样本 | 手机 `~/npt/e2eper512/b00/g.txt`（一层 DiT，569 算子） |
| Python 封装参考 | `F:\KAtorch\katorch_nodes\katorch_npu\runtime.py` |
| 实测脚本 | `F:\KAtorch\tools\`（bench_*.py / analyze_*.py） |

---

## 十一、给你的建议

**TTS 用 KAtorch，值不值得？**

先问自己三个问题：

1. **你的 TTS 模型有多大？** 如果总共不到 10M 参数，**CPU 可能就够了**，上 NPU 折腾不值。
2. **有没有 LSTM？** 如果有，得先改架构（NPU 基本不支持）。
3. **序列长度固定吗？** 如果不固定，得加 padding 或编多份图。

**如果三个都能接受**，那 KAtorch 能带来实在的收益——实测**全卷积的 vocoder 类模型加速比最好**（Conv 是 NPU 的强项）。

**如果只有一个块值得加速**（比如 vocoder），那就**只迁那一块**，其他留在 CPU。
**不要为了"全上 NPU"而大改架构**——收益不划算。

**最后**：迁移前先用 `tools/bench_*.py` 那套方法**探一下底**：

- 测一个 CONV_2D 的实际耗时
- 测一个 FC 的实际耗时
- 算出你这个模型的理论下限

**如果理论下限都比 CPU 慢，就别迁了。**
