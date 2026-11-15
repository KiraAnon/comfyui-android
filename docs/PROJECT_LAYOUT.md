# 项目结构（开发机 ↔ 手机）

手机是运行环境，开发机是编辑环境。**开发机上的仓库目录是源，手机是部署目标。**

> 本文档描述开发时的目录对应关系。如果你只是使用者，看
> [install-cpu.md](install-cpu.md) 或 [install-root.md](install-root.md) 就够了。
>
> 下面的路径来自**完整的 KAtorch 开发仓库**。本仓库（`comfyui-android`）只包含
> 其中「ComfyUI 手机化」相关的部分；NPU 加速部分在 KAtorch 仓库。

## 目录对照

| 开发机 | 手机 | 说明 |
|---|---|---|
| `katorch_nodes/katorch_npu/` | `~/ComfyUI/custom_nodes/katorch_npu/` | **ComfyUI 节点包**。改这里，然后 `deploy.ps1` 推上去 |
| `pcw/` | `~/npt/*.py` 的一部分 | **编译/调试图生成脚本**（DiT、VAE 的分块 lowering） |
| `mobile-baseline/npt/` | `~/npt/` | 手机侧**运行时**脚本的镜像（VAE、LoRA 合并、启动脚本） |
| `katorch_app/` | 装成 APK | Android 壳（WebView + 自启 + 备份） |
| `tools/` | — | 开发机侧工具（adb 助手、提交器） |
| `算子清单/` | — | MDLA 算子实测表 |
| `HANDOFF.md` | — | 主文档 |

## 哪些是"源"，哪些是"产物"

| 类型 | 位置 | 能否重新生成 |
|---|---|---|
| 节点包源码 | `katorch_nodes\katorch_npu\*.py` | **是源**，不要手改手机上的 |
| 图生成脚本 | `pcw\*.py` | **是源** |
| 运行时脚本 | `mobile-baseline\npt\*.py / *.sh` | **是源** |
| `.dla` blob | 手机 `~/npt/blobs512m/`、`~/npt/vae_npu512/` | ❌ 产物，**不在电脑上**，丢了要重编 |
| 工作流 JSON | `katorch_nodes\workflows\` | 是源 |
| APK | `katorch_app\KAtorch.apk` | 产物，`build.ps1` 生成 |

> ⚠️ `.dla` 体积大（DiT 3.6GB / VAE 50MB）且必须用手机的 Neuron 编译器生成，**电脑上不保存**。
> 重编命令见 HANDOFF 第六、六·七章。

## 两份 `vae_decoder.py` 别搞混

| 文件 | 用途 | 实现差异 |
|---|---|---|
| `katorch_npu\vae_decoder.py` | ComfyUI **运行时 CPU 回退** | `Resample.forward` 用 `x.shape`（torch 前向正常） |
| `mobile-baseline\npt\vae_decoder.py` | **生成 NPU 图**用 | `Resample.forward` 用构造期常量（`x.shape` 会被 lowering 拒绝） |

两份都不能删：前者保证非 512 分辨率能出图，后者才能编出 NPU 图。

## 同步

两个工具，分工不同：

| 工具 | 用途 |
|---|---|
| `tools\sync.ps1` | **增量同步**（按 md5 只传变了的），不自动重启 |
| `katorch_nodes\deploy.ps1` | **完整部署**：推送全部 `.py` + 清 `__pycache__` + 重启后端 |

```powershell
# 看差异（不改文件）
powershell -NoProfile -ExecutionPolicy Bypass -File F:\KAtorch\tools\sync.ps1 diff

# 电脑 → 手机
powershell -NoProfile -ExecutionPolicy Bypass -File F:\KAtorch\tools\sync.ps1 push

# 手机 → 电脑（在手机上直接改过东西之后）
powershell -NoProfile -ExecutionPolicy Bypass -File F:\KAtorch\tools\sync.ps1 pull

# 完整部署（含重启）
powershell -NoProfile -ExecutionPolicy Bypass -File F:\KAtorch\katorch_nodes\deploy.ps1

# 重装 APK
powershell -NoProfile -ExecutionPolicy Bypass -File F:\KAtorch\katorch_app\build.ps1 -Install
```

同步完要重启后端才生效：

```sh
adb shell "su -t 1 -c 'sh /data/data/com.termux/files/home/npt/ka_restart.sh'"
```

> `sync.ps1` 只对比两边都存在的文件（不会把手机上那堆 `t_*.py` 测试脚本当成"缺失"）。
> 当前基线：45 个文件，0 差异。
## 手机上的关键路径

```
~/ComfyUI/custom_nodes/katorch_npu/     节点包（部署目标）
~/npt/                                  运行时脚本 + 资产清单
~/npt/assets/manifest.json              资产清单（overrides.json 手工修正）
~/npt/blobs512m/                         DiT blob（anima_v1，turbo LoRA 1.0）
~/npt/blobs512miao/                      DiT blob（miaomiaoHarem，turbo LoRA 0.6）
~/npt/vae_npu512/                        VAE 4 段 blob（s1..s4.dla，共 50MB）
~/npt/host_new                           NPU 宿主进程
~/npt/ka_restart.sh                     重启后端（**必须走这个**，直接 pkill 会杀掉 adb shell 自己）
~/ComfyUI/models/vae/qwen_image_vae.safetensors
/data/local/tmp/                        临时文件（子进程会读这里）
```
