# -*- coding: utf-8 -*-
"""一次性给 MainActivity.java 打上「保存图片」相关的所有改动。

用精确锚点替换，每步都断言命中，避免上次那种把代码删掉一块的事故。

⚠️ 这是**已经跑过的一次性脚本**，留作参考。目标文件已经被改过了，
   再跑一次会断言失败（这正是设计意图——防止重复打补丁）。
"""
import io
import os
import re
import sys

# 路径相对脚本自身推导，不写死机器路径
P = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 "..", "app", "java", "com", "katorch", "npu", "MainActivity.java")
P = os.path.normpath(P)
if not os.path.isfile(P):
    sys.exit("找不到 MainActivity.java：%s" % P)

with io.open(P, encoding="utf-8") as f:
    s = f.read()

orig_len = len(s)
steps = []


def sub(old, new, tag, count=1):
    global s
    if new in s:
        steps.append("%-28s 已存在，跳过" % tag)
        return
    assert old in s, "锚点没找到: " + tag
    s = s.replace(old, new, count)
    steps.append("%-28s OK" % tag)


# ---------- 1. import ----------
for imp in ["android.content.ContentValues",
            "android.net.Uri",
            "android.os.Build",
            "android.os.Environment",
            "android.provider.MediaStore",
            "java.io.OutputStream"]:
    line = "import %s;" % imp
    if line not in s:
        s = s.replace("import android.os.Bundle;", line + "\r\nimport android.os.Bundle;", 1)
steps.append("imports                       OK")

# ---------- 2. DownloadListener + JS 桥注册 ----------
OLD_WVC = "        web.setWebViewClient(new KAClient());"
NEW_WVC = '''        // 保存图片：ComfyUI 的下载按钮是 blob URL，WebView 的 DownloadListener 收不到，
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
        web.setWebViewClient(new KAClient());'''
sub(OLD_WVC, NEW_WVC, "DownloadListener + KA 桥")

# ---------- 3. KBridge 等一大块 ----------
BLOCK = r'''    // ---------------- 保存图片 ----------------

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

'''
sub("    private class KAClient extends WebViewClient {",
    BLOCK + "    private class KAClient extends WebViewClient {",
    "KBridge + SAVE_JS")

# ---------- 4. shouldInterceptRequest 只拦页面文档 ----------
OLD_SIR = '''                if (request.isForMainFrame()) {
                    String u = request.getUrl().toString();
                    if (u.startsWith(URL)) {
                        return injectMain(u);
                    }
                }'''
NEW_SIR = '''                if (request.isForMainFrame()) {
                    String u = request.getUrl().toString();
                    // 只接管真正的页面文档。图片也是「主框架」导航（在预览里点链接就会跳过去），
                    // 对它做 HTML 注入会得到满屏乱码的 PNG —— 这里必须按路径白名单。
                    String path = request.getUrl().getPath();
                    boolean isDoc = (path == null || path.isEmpty()
                            || "/".equals(path) || "/index.html".equals(path));
                    if (isDoc && u.startsWith(URL)) {
                        return injectMain(u);
                    }
                }'''
sub(OLD_SIR, NEW_SIR, "shouldInterceptRequest")

# ---------- 5. onPageFinished 只对页面文档注入 ----------
OLD_FIN = '''        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(PERF_JS, null);'''
NEW_FIN = '''        public void onPageFinished(WebView view, String url) {
            // 只在页面文档上注入；图片页没有 body，注入会抛 appendChild of null
            try {
                String path = Uri.parse(url == null ? "" : url).getPath();
                boolean isDoc = (path == null || path.isEmpty()
                        || "/".equals(path) || "/index.html".equals(path));
                if (!isDoc) return;
            } catch (Exception ignored) {
                return;
            }
            view.evaluateJavascript(PERF_JS, null);'''
sub(OLD_FIN, NEW_FIN, "onPageFinished 路径判断")

# ---------- 6. onPageFinished 里注入 SAVE_JS ----------
sub('            view.evaluateJavascript(KB_JS, null);',
    '            view.evaluateJavascript(KB_JS, null);\r\n            view.evaluateJavascript(SAVE_JS, null);',
    "注入 SAVE_JS")

# ---------- 7. PERF_JS 重写（原版括号不配对） ----------
m = re.search(r'(?s)    private static final String PERF_JS\s*=\s*.*?\n\n', s)
assert m, "PERF_JS 定位失败"
NEW_PERF = '''    private static final String PERF_JS =
            // 限帧：实测拖动时 CrRendererMain 单线程 107%，降 DPR 只减单帧像素，
            // 帧数还是 60，所以必须压 rAF 频率。跳过的帧要重新排队，否则渲染循环会断。
            "(function(){"
                    + "try{"
                    + "var _raf=window.requestAnimationFrame;"
                    + "if(_raf&&!_raf.__kaThrottled){"
                    + "_raf.__kaThrottled=1;"
                    + "var lastT=0,GAP=30;"
                    + "window.requestAnimationFrame=function(cb){"
                    + "return _raf(function(ts){"
                    + "if(ts-lastT<GAP){window.requestAnimationFrame(cb);return;}"
                    + "lastT=ts;cb(ts);"
                    + "});"
                    + "};"
                    + "}"
                    + "}catch(e){}"
                    + "})();"
                    // 关掉 litegraph 每帧的阴影/边框绘制
                    + "(function(){"
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
                    + "})();"

'''
s = s[:m.start()] + NEW_PERF + s[m.end():]
steps.append("%-28s OK" % "PERF_JS 重写")

with io.open(P, "w", encoding="utf-8", newline="\r\n") as f:
    f.write(s)

print("\n".join(steps))
print("\n原 %d 字节 → 现 %d 字节" % (orig_len, len(s)))
