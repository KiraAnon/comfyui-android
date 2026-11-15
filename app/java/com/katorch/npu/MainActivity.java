package com.katorch.npu;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.content.ContentValues;

import android.net.Uri;

import android.os.Build;

import android.os.Environment;

import android.provider.MediaStore;

import java.io.OutputStream;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

/**
 * KAtorch 壳：把 ComfyUI 装成一个正常 app。
 *
 * 生命周期：
 *   打开  → 后端没起就用 root 拉起来 → 等端口通 → 全屏加载 127.0.0.1:8188
 *   划掉  → isFinishing() 为真 → 跑 ka_stop.sh 关掉后端并释放内存
 *   切后台（home）→ 不动，后端继续跑
 *
 * 之所以不做成浏览器书签：Via 那种老内核跑 ComfyUI 会各种错位/弹窗，
 * 这里用的是系统 WebView（Chrome 内核）。
 */
public class MainActivity extends Activity {

    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String START_SH = HOME + "/npt/ka_app_start.sh";
    private static final String STOP_SH = HOME + "/npt/ka_stop.sh";
    private static final String URL = "http://127.0.0.1:8188";
    private static final int PORT = 8188;

    private WebView web;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean closing = false;
    private volatile boolean loaded = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0E0E12"));

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#D8D8E0"));
        status.setTextSize(17f);
        status.setGravity(android.view.Gravity.CENTER);
        status.setText("正在启动 ComfyUI …\n首次启动约 20 秒");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        web.setBackgroundColor(Color.parseColor("#0E0E12"));
        web.setVisibility(View.INVISIBLE);
        // 让画布走独立硬件层：ComfyUI 的 litegraph 每帧全量重绘，软件层会直接掉到个位数帧率
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 保存图片：ComfyUI 的下载按钮是 blob URL，WebView 的 DownloadListener 收不到，
        // 必须走 JS 桥把 base64 递进来（见 KBridge）。这里同时补上 DownloadListener，
        // 让非 blob 的下载（比如以后导出的 zip）也能落到「下载/ComfyUI/」。
        web.addJavascriptInterface(new KBridge(), "KA");
        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                downloadToPublicDir(url, contentDisposition, mimetype);
            }
        });
        web.setWebViewClient(new KAClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                android.util.Log.i("KAWEB", cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });
        WebView.setWebContentsDebuggingEnabled(false);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        // 让 StopService 跑起来：它负责在「划掉任务」时关后端，见 StopService 的注释。
        try {
            startService(new Intent(this, StopService.class));
        } catch (Exception e) {
            android.util.Log.e("KAtorch", "启动 StopService 失败: " + e);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                boot();
            }
        }, "katorch-boot").start();
    }

    // ---------------- 启动 ----------------

    private void boot() {
        if (!portOpen()) {
            setStatus("正在启动 ComfyUI …\n首次启动约 20 秒");
            // 带日志：app 的 su 环境可能看不到 Termux 目录，出问题时靠这个文件排查
            root("(id; echo '--- ls'; ls -l " + START_SH + " 2>&1; echo '--- run'; sh " + START_SH
                    + " 2>&1) > /data/local/tmp/ka_start_log.txt 2>&1");
        } else {
            setStatus("ComfyUI 已在运行，正在连接 …");
        }

        long deadline = System.currentTimeMillis() + 240_000L;
        while (System.currentTimeMillis() < deadline && !closing) {
            if (portOpen()) {
                sleep(1500);              // 端口通不等于服务就绪，多等一下
                if (portOpen() && !closing) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showWeb();
                        }
                    });
                    return;
                }
            }
            sleep(900);
        }
        setStatus("启动超时。\n\n检查 ~/npt/comfy.log\n或手动执行 sh ~/npt/ka_restart.sh");
    }

    private void showWeb() {
        if (closing) return;
        status.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(URL);
        loaded = true;
    }

    private void setStatus(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!loaded) status.setText(text);
            }
        });
    }

    // ---------------- 关闭 ----------------

    @Override
    protected void onDestroy() {
        closing = true;
        boolean finishing = isFinishing();
        android.util.Log.i("KAtorch", "onDestroy isFinishing=" + finishing);
        if (web != null) {
            web.stopLoading();
        }
        // 划掉任务 / 返回键退出 → 把后端一起关掉，释放约 5GB。
        // home 键只走 onStop，不会到这里；系统因内存回收而销毁 Activity 时 isFinishing=false，也不关。
        if (finishing) {
            // 带日志执行，方便排查 su 是否真的跑起来了
            execAsync("(id; echo ---; sh " + STOP_SH + ") > /data/local/tmp/ka_stop_log.txt 2>&1");
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        android.util.Log.i("KAtorch", "onStop isFinishing=" + isFinishing());
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        finish();               // 触发 onDestroy → 关后端
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void immersive() {
        View d = getWindow().getDecorView();        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------- 工具 ----------------

    private boolean portOpen() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 700);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 用 root 执行一条 shell 命令（Magisk 第一次会弹授权）。
     *
     * 必须带 -t 1：Magisk 的 su 默认沿用「调用者」的 mount namespace，而 app 的
     * namespace 里看不到 /data/data/com.termux（实测 ls 直接 No such file or directory，
     * 即使 uid=0 + u:r:magisk:s0）。-t 1 接管 init 的全局 namespace 才能访问 Termux 目录。
     */
    private void root(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-t", "1", "-c", cmd});
            p.waitFor();
        } catch (Exception e) {
            android.util.Log.e("KAtorch", "root 执行失败: " + e);
        }
    }

    /**
     * 异步执行 root 命令，不等待。
     * onDestroy 里必须用这个：Activity 销毁后 app 进程随时可能被回收，
     * 如果在这里 join 子线程，stop 脚本会被连带杀掉。su 进程是独立的，父进程死了它照样跑完。
     */
    private void execAsync(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-t", "1", "-c", cmd});
        } catch (Exception e) {
            android.util.Log.e("KAtorch", "execAsync 失败: " + e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------------- 前端注入 ----------------

    /**
     * 把 devicePixelRatio 压到 1.25。
     *
     * ComfyUI 的画布按 window.devicePixelRatio 决定实际像素尺寸：这台手机逻辑 1080x2400、
     * DPR 2.5，litegraph 每帧要重绘 2700x6000 量级的 canvas，拖一下就是幻灯片。
     * 压到 1.25 渲染量降到 1/4，肉眼几乎看不出糊。
     */
    /**
     * 注入到主文档 <head> 最前面：
     *  1. 把 devicePixelRatio 压到 1.0（画布渲染量的根因，见下）
     *  2. 焦点守卫：ComfyUI 有些面板（工作流库、搜索框）渲染出来就 autofocus，
     *     WebView 见到 input 拿到焦点就弹软键盘 —— 用户根本没点，键盘自己跳出来。
     *     这里记最后一次真实触摸，700ms 内没点过就说明是脚本抢的焦点，直接 blur。
     */
    private static final String DPR_JS =
            "(function(){try{var lim=1.0;"
                    + "if((window.devicePixelRatio||1)>lim){"
                    + "try{Object.defineProperty(window,'devicePixelRatio',{configurable:true,get:function(){return lim;}});}catch(e){}"
                    + "}}catch(e){}"
                    + "try{var lastTouch=0,tx=0,ty=0;"
                    + "var mark=function(e){lastTouch=Date.now();"
                    + "  try{var p=(e.touches&&e.touches[0])||e;tx=p.clientX||0;ty=p.clientY||0;}catch(x){}};"
                    + "document.addEventListener('touchstart',mark,true);"
                    + "document.addEventListener('mousedown',mark,true);"
                    // 只处理 <input>，绝不碰 <textarea>：
                    // litegraph 的节点文本框是动态创建的 textarea，靠脚本调 focus() 才能编辑，
                    // 一旦在这里拦掉，用户点节点就再也弹不出键盘（踩过）。
                    // 要防的只是「工作流库/搜索框这类 input 自己 autofocus 弹键盘」。
                    + "try{var _f=HTMLElement.prototype.focus;"
                    + "HTMLElement.prototype.focus=function(){"
                    + "  try{var t=(this.tagName||'').toLowerCase();"
                    + "    if(t==='input'){"
                    + "      if(Date.now()-lastTouch>800){console.log('[KA] block autofocus input');return;}"
                    + "      var r=this.getBoundingClientRect();"
                    + "      var outside=!(tx>=r.left-8&&tx<=r.right+8&&ty>=r.top-8&&ty<=r.bottom+8);"
                    + "      if(outside&&r.width>0&&r.height>0){console.log('[KA] block offscreen input');return;}"
                    + "    }"
                    + "  }catch(e){}"
                    + "  return _f.apply(this,arguments);"
                    + "};}catch(e){}"
                    // 兜底：完全没有触摸却冒出来的聚焦，才收掉
                    + "document.addEventListener('focusin',function(e){"
                    + "  var t=e.target;if(!t)return;"
                    + "  if((t.tagName||'').toLowerCase()!=='input')return;"
                    + "  if(Date.now()-lastTouch>900){setTimeout(function(){try{t.blur();}catch(err){}},0);}"
                    + "},true);"
                    + "}catch(e){}"
                    + "})();";

    /**
     * 工作流备份 / 恢复。
     *
     * ComfyUI 1.59 的 `Comfy.Workflow.AutoSave` 默认是 off，而且这个 Vue 重构版里
     * `app.workflowManager` 不存在，没法走它的保存通道。所以自己来：
     *   - 每 4 秒把 app.graphToPrompt().workflow 写进 localStorage（只在变化时写）
     *   - 下次冷启动、图加载完之后，把备份盖回去
     * 实测 WebView 的 localStorage 跨进程可用（DPM 数据落在 app 私有目录）。
     */
    private static final String BACKUP_JS =
            "(function(){if(window.__kaBak)return;window.__kaBak=1;"
                    + "var last='';var saveTimer=null;"
                    // 只在图真的变了之后存：graphToPrompt 要遍历整张图，定时调它会在拖动时抢 CPU
                    + "async function doSave(){try{"
                    + "  if(!(window.app&&app.graphToPrompt))return;"
                    + "  var wf=(await app.graphToPrompt()).workflow;"
                    + "  if(!wf||!wf.nodes||wf.nodes.length===0)return;"
                    + "  var s=JSON.stringify(wf);"
                    + "  if(s.length<80||s===last)return;"
                    + "  last=s;localStorage.setItem('KA_WF_BACKUP',s);"
                    + "  localStorage.setItem('KA_WF_TS',String(Date.now()));"
                    + "  console.log('[KA] backup saved ' + s.length);"
                    + "}catch(e){}}"
                    + "function scheduleSave(){if(saveTimer)clearTimeout(saveTimer);saveTimer=setTimeout(doSave,2500);}"
                    // hook litegraph 的图变更回调（保留原来的）
                    + "var hooked=false;"
                    + "var hookTimer=setInterval(function(){"
                    + "  if(hooked)return;"
                    + "  try{ if(window.app&&app.graph&&app.graph.onAfterChange){"
                    + "    var _oa=app.graph.onAfterChange;"
                    + "    app.graph.onAfterChange=function(){if(_oa)_oa.apply(this,arguments);scheduleSave();};"
                    + "    hooked=true;clearInterval(hookTimer);"
                    + "    console.log('[KA] backup hook installed');"
                    + "  } }catch(e){}"
                    + "},1000);"
                    // 兜底：每 30 秒才轮询一次
                    + "setInterval(doSave,30000);"
                    // 恢复：优先等图加载出来；12 秒还没等到就强制试一次
                    + "var tries=0;"
                    + "var timer=setInterval(async function(){"
                    + "  tries++;"
                    + "  try{"
                    + "    if(window.app&&app.graphToPrompt&&app.loadGraphData){"
                    + "      var r=await app.graphToPrompt();"
                    + "      if(tries===1||tries===8){console.log('[KA] g2p='+(r?Object.keys(r).join(','):'null')"
                    + "        +' nodes='+((r&&r.workflow&&r.workflow.nodes)?r.workflow.nodes.length:-1));}"
                    + "      if(r&&r.workflow&&r.workflow.nodes&&r.workflow.nodes.length>0){clearInterval(timer);restore();return;}"
                    + "    }"
                    + "  }catch(e){if(tries===1)console.log('[KA] poll err '+e);}"
                    + "  if(tries>50)clearInterval(timer);"
                    + "},500);"
                    + "setTimeout(function(){console.log('[KA] force restore');restore();},12000);"
                    + "var done=false;"
                    + "async function restore(){"
                    + "  if(done)return;done=true;"
                    + "  try{"
                    + "    var s=localStorage.getItem('KA_WF_BACKUP');"
                    + "    var ts=parseInt(localStorage.getItem('KA_WF_TS')||'0',10);"
                    + "    console.log('[KA] restore: bak='+(s?s.length:'none')+' ts='+ts);"
                    + "    if(!s||!ts){console.log('[KA] no backup');return;}"
                    + "    var age=Date.now()-ts;"
                    + "    if(age>3*24*3600*1000){console.log('[KA] backup too old');return;}"
                    // 和当前图对比：一样就什么都不做（loadGraphData 每次都会新开一个工作流标签）
                    + "    var cur='';try{cur=JSON.stringify((await app.graphToPrompt()).workflow);}catch(e){}"
                    + "    var norm=function(x){return x.replace(/\\s+/g,'');};"
                    + "    if(cur&&cur.length>80&&norm(cur)===norm(s)){console.log('[KA] same as backup, skip');return;}"
                    // 直接改当前图：不新建工作流标签
                    + "    if(app.graph&&app.graph.configure){"
                    + "      app.graph.configure(JSON.parse(s));"
                    + "      try{app.canvasInternal&&app.canvasInternal.setDirty(true,true);}catch(e){}"
                    + "      console.log('[KA] restored via graph.configure age='+Math.round(age/1000)+'s');"
                    + "    }else{"
                    + "      await app.loadGraphData(JSON.parse(s));"
                    + "      console.log('[KA] restored via loadGraphData age='+Math.round(age/1000)+'s');"
                    + "    }"
                    + "  }catch(e){console.log('[KA] restore err '+e);}"
                    + "}"
                    + "})();";
    /** 迷你键盘：脚本放在 ComfyUI 静态目录 /rime/keyboard.js，这里只负责加载。 */
    private static final String KB_JS =
            "(function(){if(window.__kaKbLoad)return;window.__kaKbLoad=1;"
                    + "var s=document.createElement('script');"
                    + "s.src='/rime/keyboard.js?v='+Date.now();"
                    + "s.onload=function(){console.log('[KA] keyboard script loaded');};"
                    + "s.onerror=function(){console.log('[KA] keyboard script FAILED');};"
                    + "document.body.appendChild(s);})();";

    /** 页面加载后压掉 litegraph 的重绘开销：阴影/边框/info 每帧都在画，肉眼收益很低。 */
    private static final String PERF_JS =
            // 只关掉 litegraph 每帧的阴影/边框绘制（纯视觉收益，不碰交互）。
            // 这里**不要**再包 requestAnimationFrame 做限帧：上一版在跳帧分支里调用的是
            // 已经被覆盖的 window.requestAnimationFrame，等于每跳一帧就多套一层包装，
            // 画布会直接拖不动。要限帧请用 ComfyUI 自带的
            // 「LiteGraph.Canvas.MaximumFps」（设置面板里能调）。
            "(function(){"
                    + "var go=function(){"
                    + "try{"
                    + "var c=(window.LGraphCanvas&&LGraphCanvas.active_canvas)"
                    + "||(window.app&&(app.canvasInternal||app.canvas));"
                    + "if(!c)return;"
                    + "c.render_shadows=false;c.render_canvas_border=false;"
                    + "c.render_connections_border=false;c.render_curvature=false;c.show_info=false;"
                    + "console.log('[KA] perf applied');"
                    + "}catch(e){}"
                    + "};"
                    + "go();setTimeout(go,2000);setTimeout(go,6000);"
                    + "})();";

    private static final String TOOLS_JS =
            "(function(){"
                    + "if(document.getElementById('ka-reload'))return;"
                    + "var b=document.createElement('button');"
                    + "b.id='ka-reload';b.textContent='\\u21bb';b.title='刷新页面';"
                    + "b.style.cssText='position:fixed;left:50%;margin-left:-17px;bottom:6px;"
                    + "width:34px;height:34px;border-radius:17px;border:1px solid rgba(255,255,255,.22);"
                    + "background:rgba(18,18,26,.7);color:#F2FF59;font-size:17px;line-height:1;"
                    + "padding:0;z-index:2147483647;opacity:.55;';"
                    + "b.onclick=function(){location.reload();};"
                    + "b.ontouchstart=function(){b.style.opacity='1';};"
                    + "b.ontouchend=function(){setTimeout(function(){b.style.opacity='.55';},800);};"
                    + "document.body.appendChild(b);"
                    + "})();";

    // ---------------- 保存图片 ----------------

    /**
     * 把 base64 图片写进系统相册（MediaStore），Android 10+ 不需要存储权限。
     *
     * ComfyUI 的「下载」是 `URL.createObjectURL(blob)` + `<a download>`：
     * WebView 的 DownloadListener **收不到 blob 下载**，DownloadManager 也不认 blob: 协议，
     * 点了就是完全没反应。所以页面里把 blob 转成 dataURL 递进来，这里落盘。
     */
    private class KBridge {
        @JavascriptInterface
        public void saveImage(final String name, final String dataUrl) {
            final String safe = safeName(name);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String msg;
                    try {
                        int comma = dataUrl.indexOf(',');
                        String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                        byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);

                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Images.Media.DISPLAY_NAME, safe);
                        cv.put(MediaStore.Images.Media.MIME_TYPE, mimeOf(safe));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                                    Environment.DIRECTORY_PICTURES + "/ComfyUI");
                            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                        }
                        Uri uri = getContentResolver()
                                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        if (uri == null) throw new java.io.IOException("MediaStore 插入失败");
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os == null) throw new java.io.IOException("打不开输出流");
                        try {
                            os.write(bytes);
                        } finally {
                            os.close();
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Images.Media.IS_PENDING, 0);
                            getContentResolver().update(uri, done, null, null);
                        }
                        msg = "已保存到相册/ComfyUI/" + safe + "（" + (bytes.length / 1024) + " KB）";
                    } catch (Exception e) {
                        msg = "保存失败：" + e.getMessage();
                        android.util.Log.e("KAtorch", "saveImage 失败: " + e);
                    }
                    final String m = msg;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(m);
                        }
                    });
                }
            }, "katorch-save").start();
        }
    }

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.i("KAtorch", msg);
        }
    }

    /** 只保留文件名，去掉路径和非法字符，防止 ../ 之类。 */
    private static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "comfyui_" + System.currentTimeMillis() + ".png";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) return "comfyui_" + System.currentTimeMillis() + ".png";
        if (!name.contains(".")) name = name + ".png";
        return name;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    private static String nameFromUrl(String url) {
        try {
            String q = Uri.parse(url).getQueryParameter("filename");
            if (q != null && !q.isEmpty()) return safeName(q);
        } catch (Exception ignored) {
        }
        return safeName(null);
    }

    /** 非 blob 的下载交给系统 DownloadManager。 */
    private void downloadToPublicDir(String url, String contentDisposition, String mimetype) {
        try {
            String name = nameFromUrl(url);
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimetype != null && !mimetype.isEmpty() ? mimetype : mimeOf(name));
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "ComfyUI/" + name);
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("DownloadManager 不可用");
            dm.enqueue(req);
            toast("已下载到 下载/ComfyUI/" + name);
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
            android.util.Log.e("KAtorch", "download 失败: " + e);
        }
    }

    /** 劫持 <a download> 的点击，把 blob 读成 dataURL 交给 KBridge.saveImage。 */
    private static final String SAVE_JS =
            "(function(){if(window.__kaSave)return;window.__kaSave=1;"
                    + "document.addEventListener('click',function(e){"
                    + "var a=null,t=e.target;"
                    + "while(t&&t!==document.body){"
                    + "if(t.tagName==='A'&&(t.hasAttribute('download')||t.download)){a=t;break;}"
                    + "t=t.parentNode;}"
                    + "if(!a||!window.KA||!KA.saveImage)return;"
                    + "var href=a.href||'';"
                    + "if(href.indexOf('blob:')!==0&&href.indexOf('data:')!==0)return;"
                    + "e.preventDefault();e.stopPropagation();"
                    + "var name=a.getAttribute('download')||a.download||('comfyui_'+Date.now()+'.png');"
                    + "console.log('[KA] save '+name);"
                    + "if(href.indexOf('data:')===0){KA.saveImage(name,href);return;}"
                    + "fetch(href).then(function(r){return r.blob();}).then(function(b){"
                    + "var fr=new FileReader();"
                    + "fr.onload=function(){KA.saveImage(name,String(fr.result));};"
                    + "fr.readAsDataURL(b);"
                    + "}).catch(function(err){console.log('[KA] blob 读取失败: '+err);});"
                    + "},true);"
                    + "console.log('[KA] save hook installed');"
                    + "})();";

    private class KAClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                if (request.isForMainFrame()) {
                    String u = request.getUrl().toString();
                    // 只接管真正的页面文档。图片也是「主框架」导航（在预览里点链接就会跳过去），
                    // 对它做 HTML 注入会得到满屏乱码的 PNG —— 这里必须按路径白名单。
                    String path = request.getUrl().getPath();
                    boolean isDoc = (path == null || path.isEmpty()
                            || "/".equals(path) || "/index.html".equals(path));
                    if (isDoc && u.startsWith(URL)) {
                        return injectMain(u);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("KAtorch", "拦截主文档失败: " + e);
            }
            return null;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // 只在页面文档上注入；图片页没有 body，注入会抛 appendChild of null
            try {
                String path = Uri.parse(url == null ? "" : url).getPath();
                boolean isDoc = (path == null || path.isEmpty()
                        || "/".equals(path) || "/index.html".equals(path));
                if (!isDoc) return;
            } catch (Exception ignored) {
                return;
            }
            view.evaluateJavascript(PERF_JS, null);
            view.evaluateJavascript(TOOLS_JS, null);
            view.evaluateJavascript(BACKUP_JS, null);
            view.evaluateJavascript(KB_JS, null);

            view.evaluateJavascript(SAVE_JS, null);
        }
    }

    private WebResourceResponse injectMain(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Accept-Encoding", "identity");   // 拿明文，别让人给 gzip
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            String html = bo.toString("UTF-8");
            // DPR_JS 是纯 JS（对应 app/js/DPR_JS.js），拼进 head 时补上 script 标签。
            // 这样它可以单独用 `node --check` 验语法——之前忘了包标签时整个脚本没执行，
            // 限帧/防键盘都没生效，排查了很久。
            String headJs = "<script>" + DPR_JS + "</script>";
            if (html.contains("<head>")) {
                html = html.replace("<head>", "<head>" + headJs);
            } else {
                html = headJs + html;
            }
            return new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(html.getBytes("UTF-8")));
        } catch (Exception e) {
            android.util.Log.e("KAtorch", "injectMain 失败: " + e);
            return null;    // 返回 null 就走原始加载，不影响可用性
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
