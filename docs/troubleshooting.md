# 排错

按症状查。每条都是真实踩过的坑。

---

## ComfyUI 起不来

### `AssertionError: Torch not compiled with CUDA enabled`

**没加 `--cpu`**。

```sh
python main.py --cpu --listen 127.0.0.1 --port 8188
```

---

### `dlopen failed: cannot locate symbol "PyExc_Exception"`

**没设 `LD_PRELOAD`**。`tokenizers` 这个 Rust 扩展找不到 Python 符号表。

```sh
# 版本号要跟你的 Python 一致
ls /data/data/com.termux/files/usr/lib/libpython3*.so
export LD_PRELOAD=/data/data/com.termux/files/usr/lib/libpython3.14.so
```

**注意**：每次新开 shell 都要重设，所以写进启动脚本里最省事。

---

### `Port 8188 is already in use` 但明明没在跑

上次连接留下的 **TIME_WAIT** 占了端口。等 60 秒，或者换个端口：

```sh
python main.py --cpu --port 8189
```

---

### 启动时卡在 `Installing matplotlib` 之类

某个自定义节点（实测是 **Comfyroll**）在 import 时自动 pip install，把启动拖死。

```sh
# 禁掉它
mv ~/ComfyUI/custom_nodes/ComfyUI_Comfyroll_CustomNodes-main{,.disabled}
```

**改 `pyproject.toml` 没用**，它靠的是 `__init__.py` 里的运行时逻辑。

---

## 点了 app 图标没反应

### 症状：图标点下去，转一圈又回到桌面

**根因：启动脚本用端口判断「后端已运行」，被 TIME_WAIT 骗了。**

```sh
# ❌ 错
netstat -an | grep 8188

# ✅ 对
pgrep -f "main.py --cpu"
```

脚本以为后端活着就直接返回，实际上根本没起。

---

### 症状：app 卡在「正在启动 ComfyUI…」

后端没起来。手动验证：

```sh
su -c "setsid sh ~/npt/comfy-launch-root.sh > ~/npt/comfy.log 2>&1 < /dev/null &"
sleep 20
tail -30 ~/npt/comfy.log
```

---

### 症状：`su -c "ls /data/data/com.termux"` 在 app 里返回 No such file

**Magisk 的 su 沿用调用者的 mount namespace**，而 app 的 namespace 里没有 Termux 的挂载点。

```java
// 接管 init 的全局 namespace
Runtime.getRuntime().exec(new String[]{"su", "-t", "1", "-c", cmd});
```

新版 Magisk 没有 `--mount-master` 了，只有 `-t, --target PID`。

---

## 出图问题

### 出图特别慢（几分钟以上）

**确认 `--cpu` 加了**，然后确认有没有走 NPU：

```sh
pgrep -f host_new    # 有输出说明 NPU 在做
```

如果走的纯 CPU，40~60 分钟是正常的。

---

### 出图是黑的 / 全噪点 / 过曝

**VAE 的 latent 归一化不对**。

ComfyUI 的 Wan VAE `encode()` 返回带 T 维且**未归一化**的 latent。
必须压掉 T 维并做：

```python
z = (z - LAT_MEAN) / LAT_STD
```

否则图像过曝过饱和（PSNR 16.9 vs 46.2 的天壤之别）。

---

### 画风和平板/电脑上不一样

**调度器或采样器不一致**。

| 参数 | PC 上的 ComfyUI | 常见错误默认 |
|---|---|---|
| 调度器 | `beta` | `linear` |
| 采样器 | `er_sde` | `euler` |

两种 sigma 序列完全不同：

```
beta(0.6,0.6): [0.9428, 0.8243, 0.6706, 0.5, 0.3294, 0.1757, 0.0572, 0.0]
linear+shift3: [1.0,    0.9545, 0.9,    0.8333, 0.75, 0.6429, 0.5,   0.3, 0.0]
```

检查工作流里 KSampler 的这两个参数。

---

## NPU 相关问题

### `-516 UNMAPPABLE`

编译失败，最常见。可能原因：

1. **算子组合 MDLA 不支持**
2. **INT16 下用了坏算子**（MEAN / REDUCE_SUM / REDUCE_MAX）
3. **输入输出 scale 不一致**
4. **归约算子少给了参数**（见下）

**排查方法：二分法**。图文本是纯文本，删掉一半算子再编，看哪一半能过。

---

### 归约算子（MEAN/REDUCE_SUM/REDUCE_MAX）报 -516

**它们要 3 个输入，不是 2 个**：

```
OP 31 3 <input> <axis> <act_code> 1 <output>
       ↑              ↑        ↑
       type4, a -1    type4, a 0
```

少一个就是 -516，而且**错误信息不会告诉你是参数少了**。

---

### `-314`

`M_addOp` 返回 114，**常量总量超 IOVA 配额**。

```
IOVA 总量约 1.35 GB，常量（w 文件）必须常驻
```

实测：一层 DiT 的常量 579 MB，两块合并 1158 MB → 直接超限。

**解法：把图切小**，每张图的常量控制在 500 MB 以内。

---

### `-507`

编译失败，算子不支持或 shape 不合法。

先确认单算子能编（用 `tools/` 里的 bench 脚本测），再拼整图。

---

### `-514 RESOURCE_EXHAUSTED`

**同一个进程里重复编译**把资源耗光了。

重启宿主进程：

```sh
pkill -9 host_new
```

---

### 编译能过但结果不对

**最可能：activation_code 写成了 type 1 而不是 type 4**。

这会导致**静默直通**（输出 == 第一个输入），不报任何错。

**验证**：如果输出和输入一模一样，就是这个原因。

---

### 内存爆 / 手机重启

看 dmesg：

```sh
su -c "dmesg -T | grep -iE 'oom|panic' | tail -20"
```

如果看到 `system_is_deadloomld_on_oomory` → **OOM 导致 kernel panic**。

**真实事故**：在 ComfyUI 后端还在跑的时候，又写了个脚本加载一整套模型，
两个进程各吃几 GB，直接崩。

**规则：不要在 ComfyUI 运行时跑外部加载模型的脚本。**

---

## 网络问题

### pip 装包超时

```sh
# 换国内源
pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple

# 或者临时
pip install <包> -i https://pypi.tuna.tsinghua.edu.cn/simple
```

### HuggingFace 下不动

```sh
export HF_ENDPOINT=https://hf-mirror.com
# 如果还是 401，可能是 Xet 协议的问题
export HF_HUB_DISABLE_XET=1
```

### VPN 开了但 Termux 还是解析不了域名

**实测**：某些 VPN 的 fake-ip 模式下，Termux 拿不到 DNS
（`NameResolutionError` / `getaddrinfo` 失败），但 `ping 8.8.8.8` 能通。

**临时解法**：写 hosts 文件并挂载

```sh
# 在 /data/local/tmp/hosts_katorch 里写好 IP 映射
su -c "mount -o bind /data/local/tmp/hosts_katorch /system/etc/hosts"
```

**注意：重启会丢，要重新挂**。

---

## 键盘

### 横屏键盘占了 71% 屏幕

**没解决，已知限制**。试过的方案：

| 尝试 | 结果 |
|---|---|
| 系统 LatinIME | 高度只小 4%，且没中文 |
| Unexpected Keyboard | 高度一样，桌面式键位更别扭 |
| 讯飞单手模式 | 宽度锁死全宽，只能调高度 |
| 自建迷你键盘 + RIME WASM | 键盘能显示能上屏，**但压不住系统软键盘** |

最后一项花时间最多：`readonly` hack 试了，但 ComfyUI 的节点文本框是**动态创建**的，
`readonly` 在时序上抢不过，而且 **litegraph 的 textarea 是创建即 focus**。

---

## 帧率

### 拖动画布只有 3~4 fps

**DPR 太高**。

```sh
wm size 1080x2400     # 从 1440x3200 降下来
wm density 400        # 从 560 降下来
```

配合关掉 Lite Graph 的阴影/边框 → **25 fps**。

**实测**：CrRendererMain 单线程 107%，另外 23 个线程全空闲——**单核瓶颈**，
不是多核能救的。限帧、缩小体积都没用（内存带宽瓶颈，加线程反而更慢）。

---

## 还是不行？

收集这些信息再问：

```sh
# 环境
uname -a
python --version
pip list | head -30

# 后端日志
tail -50 ~/npt/comfy.log

# 进程
pgrep -af "main.py|host_new"

# 内存
grep -E "MemTotal|MemAvailable" /proc/meminfo

# 内核日志（OOM / NPU）
su -c "dmesg -T | tail -30"
```
