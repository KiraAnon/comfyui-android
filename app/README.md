# ComfyUI 安卓壳（APK）

把手机上的 ComfyUI 包成一个**正常的 app**：点图标打开、上拉任务列表里看得见、**划掉就真的关掉**。

> 应用名是 **ComfyUI**，图标是 ComfyUI 官方 logo（从手机上前端包
> `assets/images/comfy-logo-single.svg` 提取，转成 Android VectorDrawable）。
> KAtorch 是底层那套自制手机 PyTorch/MDLA 栈的名字，不是这个 app 的名字。

## 为什么做这个

之前用 Via 浏览器开 `127.0.0.1:8188`，两个问题：

1. **Via 是老内核**，跑 ComfyUI 这种重前端 SPA 各种错位、弹窗；
2. **浏览器页面没有生命周期** —— 关掉页面 ComfyUI 进程还在后台占 5GB，得手动去杀。

这个壳用**系统 WebView**（手机上实测是 Chrome 124 内核）代替浏览器，并且把 app 的生命周期和后端进程绑在一起。

## 行为

| 动作 | 发生什么 |
|---|---|
| 点图标 | root 执行 `ka_app_start.sh`：后端没跑就拉起，跑着就直接连 |
| 加载中 | 显示「正在启动 ComfyUI …」，轮询 8188 端口，通了就全屏加载 |
| home 切后台 | 什么都不做，后端继续跑 |
| **上拉任务列表划掉** | `StopService.onTaskRemoved()` → root 执行 `pkill` 关掉 python + host_new，释放约 5GB |
| 按返回键 | 同样退出并关后端 |

## 实测（Android 15 / MT6897）

```
打开 app           → python 27471 起来，8188 监听，WebView 全屏加载
划掉卡片           → 两个进程全部消失，内存 14.8GB → 7.7GB
```

## 三个坑（都踩过）

### 1. 划掉时 `Activity.onDestroy` 根本不调用

实测日志只有 `onStop isFinishing=false`，之后进程直接被系统回收。
所以关后端的逻辑必须放在 **`Service.onTaskRemoved()`**，并且 manifest 里要写
`android:stopWithTask="false"`，否则 Service 会跟着任务一起被杀，收不到回调。

### 2. app 的 root 看不到 Termux 目录

`su -c "ls /data/data/com.termux"` 在 app 里返回 `No such file or directory`，
即使 `uid=0` + `u:r:magisk:s0`。

原因：**Magisk 的 su 默认沿用调用者的 mount namespace**，而 app 的 namespace 里
没有 Termux 的挂载点。解法是接管 init 的全局 namespace：

```java
Runtime.getRuntime().exec(new String[]{"su", "-t", "1", "-c", cmd});
```

（新版 Magisk 没有 `--mount-master` 了，`su --help` 里只有 `-t, --target PID`。）

### 3. `StopService` 里关后端的命令不要依赖设备上的脚本文件

`sh /data/data/com.termux/.../ka_stop.sh` 在 app 侧会失败（同第 2 条）。
关后端的命令**内联**在 Java 里最稳（`pkill` 是 toybox 自带的，不依赖任何文件）。
启动后端仍然要访问 Termux 目录，所以那边必须带 `-t 1`。

## 构建

```powershell
powershell -ExecutionPolicy Bypass -File F:\KAtorch\katorch_app\build.ps1 -Install
```

不用 Gradle，直接 `aapt2 → javac → d8 → zipalign → apksigner` 手工流水线，
产物 `KAtorch.apk` 只有 17KB。

依赖（已装在 `D:\AndroidSdk`）：
- `platforms;android-35`
- `build-tools;35.0.0`
- JDK 21（`C:\Program Files\Java\jdk-21.0.10`）

## 配置

| 项 | 值 |
|---|---|
| 应用名 | **ComfyUI** |
| 图标 | ComfyUI 官方 logo（`res/drawable/ic_launcher_foreground.xml`，深色圆角块 + 荧光黄 C） |
| 包名 | `com.katorch.npu`（内部标识，界面不可见；改它要重装并重新授权 root） |
| 启动 Activity | `.MainActivity` |
| 前端地址 | `http://127.0.0.1:8188` |
| 启动脚本 | `/data/data/com.termux/files/home/npt/ka_app_start.sh` |
| 停止逻辑 | 内联在 `StopService`，不依赖文件 |

**root 授权**：Magisk 里 `com.katorch.npu`（uid 10184）已经预授权（policy=2），
不会每次弹窗。撤销：`magisk --sqlite "DELETE FROM policies WHERE uid=10184"`。

## 性能与输入（2026-11-15 调优）

### 拖动卡顿：画布按 devicePixelRatio 渲染

ComfyUI 的 litegraph 每次拖动都全量重绘 canvas，实际像素尺寸 = 逻辑尺寸 × `devicePixelRatio`。
这台手机逻辑 1080×2400、DPR 2.5，等于每帧重绘 ~2700×6000，直接掉到个位数帧率。

**做法**：在 `shouldInterceptRequest` 里拦下主文档，往 `<head>` 最前面注入一段
`Object.defineProperty(window,'devicePixelRatio',{get:()=>1.0})`。

| 设置 | 拖动帧率 |
|---|---|
| 原始（DPR 2.5） | 3～4 |
| DPR 1.25 | 明显好转 |
| **DPR 1.0 + 关 litegraph 阴影/边框/弧度/info** | **25** |

另外在 `onPageFinished` 里关掉 `render_shadows / render_canvas_border /
render_connections_border / render_curvature / show_info` —— 这几项每帧都在画，视觉收益很低。

代价是字会稍微糊一点（1.0 等于不做超采样）。嫌糊可以把注入里的 `lim` 调到 1.25。

### 键盘自己弹出来

ComfyUI 有些面板（工作流库/搜索框）渲染出来就 autofocus，WebView 见到 input 拿到焦点就
弹软键盘。

**做法**（两层）：

1. manifest 加 `android:windowSoftInputMode="adjustResize|stateAlwaysHidden"` —— 只管住打开时；
2. 注入焦点守卫：包一层 `HTMLElement.prototype.focus`，只有「最近 800ms 内有触摸」**且**
   「触摸点落在该输入框矩形内」才放行，否则吞掉。

用户点击输入框时的原生聚焦不走 prototype，所以不会被误拦。

### 刷新按钮

原来 app 里没有任何重载入口，页面卡住只能划掉重开。现在画布底部中央挂了一个半透明的 ⟳
（注入在 `onPageFinished`），点一下 `location.reload()`。

位置刻意放在**底部中央**：右侧垂直居中是之前注入的全屏按钮 ⛶，右上角是 ComfyUI 自己的按钮区。

### 关掉再开，改过的提示词没了

**根因**：ComfyUI 1.59 的 `Comfy.Workflow.AutoSave` **默认就是 `off`**（前端源码里
`defaultValue: 'off'`），所以编辑内容一直没落盘。而 `Comfy.Workflow.Persist` 只管刷新恢复，
不管写文件。

而且这个版本的前端是 Vue 重构版，`app.workflowManager` 根本不存在，也没法走它的保存通道。

**做法**：壳自己备份。

- 每 4 秒把 `app.graphToPrompt().workflow` 写进 localStorage（只在内容变化时写）
- 下次冷启动、图加载出来之后，把备份盖回去（超过 3 天的备份自动丢弃）

注意 `graphToPrompt()` / `loadGraphData()` 在这个版本是 **async** 的，必须 `await`，
否则拿到的是 Promise，`r.workflow` 永远是 undefined（踩过）。

实测日志：

```
[KA] g2p=workflow,output nodes=0
[KA] restore: bak=4713 ts=1794679818871
[KA] restored backup age=19s
```

**副作用**：app 打开时永远回到「上次关掉时的样子」，不会回到文件里的版本。
想回到文件版本就在界面里重新打开一次那个工作流。

### 键盘拦截的边界（踩过两次，别再犯）

为了不让工作流库的搜索框「打开就 autofocus 弹键盘」，拦截了 `HTMLElement.prototype.focus`。
**但第一版把 `<textarea>` 一起拦了** —— ComfyUI 的节点文本框（litegraph）正是动态创建的
`textarea`，靠脚本调 `focus()` 才能编辑，结果用户点节点文本框再也弹不出键盘。

最终规则：

| 元素 | 处理 |
|---|---|
| `<input>`（搜索框、名字输入框） | 800ms 内没触摸 或 触摸点不在框内 → 拦截 |
| `<textarea>`（节点的 prompt 文本框） | **一律放行**，绝不拦 |
| `contenteditable` | 一律放行 |

另外 `windowSoftInputMode` 用了 `adjustResize|stateAlwaysHidden`，只影响 Activity 启动那一刻，
不要指望它管运行时的键盘。

### 迷你键盘那次的尝试（已停用）

试过在页面里自绘一个 4 行迷你键盘 + RIME WASM 中文引擎（`jsh_rime`，词典 8.2MB），
键盘能显示、能上屏、能出候选词，**但没能可靠地压住系统软键盘**（`readonly` hack 对
litegraph 动态创建的 textarea 时序上抢不过），最后整条撤掉。

代码留在 `rime/keyboard.js`（现在是个空实现）。要重做的话，卡点只有一个：
**在 focus 发生之前让 WebView 不弹软键盘**，其余部分都是通的。

## 已知限制

- 后端被人为关掉后，app 不会自己重连（要重开 app）。
- 加载的是 ComfyUI 上次的工作流，不是自动打开「KAtorch模块化」，需要手动点一下。
- WebView 会额外吃 1～2GB 内存（ComfyUI 前端本身就很重）。
- 只在 Android 15 上验证过；`-t 1` 依赖 Magisk，其它 root 方案可能要改。
