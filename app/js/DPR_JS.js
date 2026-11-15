/* ------------------------------------------------------------------
 * DPR_JS —— 注入到 <head> 的早期脚本
 *
 * 做两件事，都必须在页面脚本之前执行：
 *   1. 把 devicePixelRatio 压到 1.0
 *      ComfyUI 的画布按 DPR 渲染，2K/4K 屏 + DPR 2.5 会拖到 3~4 fps。
 *      压到 1.0 后实测 25 fps。
 *   2. 阻止可疑的自动聚焦（手机键盘乱弹）
 *      litegraph 的 textarea 是"创建即 focus"，会顶起软键盘。
 *
 * 注意：这个是拼进 HTML 的（MainActivity 里加 <script> 标签包住），
 *      不是 evaluateJavascript 执行的，所以文件本身必须是纯 JS。
 * ------------------------------------------------------------------ */
(function () {
    // ---- 1. 压 devicePixelRatio ----
    try {
        var LIMIT = 1.0;
        if ((window.devicePixelRatio || 1) > LIMIT) {
            try {
                Object.defineProperty(window, 'devicePixelRatio', {
                    configurable: true,
                    get: function () { return LIMIT; }
                });
                console.log('[KA] dpr clamped to ' + LIMIT);
            } catch (e) { /* 有的 WebView 不允许重定义，忽略 */ }
        }
    } catch (e) { }

    // ---- 2. 防键盘乱弹 ----
    var lastTouch = 0, tx = 0, ty = 0;

    function mark(e) {
        lastTouch = Date.now();
        try {
            var p = (e.touches && e.touches[0]) || e;
            tx = p.clientX || 0;
            ty = p.clientY || 0;
        } catch (x) { }
    }
    document.addEventListener('touchstart', mark, true);
    document.addEventListener('mousedown', mark, true);

    // 拦截 focus()：不是用户主动点的、或者元素在屏幕外的，都不给聚焦
    try {
        var _focus = HTMLElement.prototype.focus;
        HTMLElement.prototype.focus = function () {
            try {
                var tag = (this.tagName || '').toLowerCase();
                if (tag === 'input') {
                    // 800ms 内没有触摸 => 不是用户操作的
                    if (Date.now() - lastTouch > 800) {
                        console.log('[KA] block autofocus input');
                        return;
                    }
                    // 元素不在刚才点击的位置附近 => 屏幕外
                    var r = this.getBoundingClientRect();
                    var outside = !(tx >= r.left - 8 && tx <= r.right + 8 &&
                                    ty >= r.top - 8 && ty <= r.bottom + 8);
                    if (outside && r.width > 0 && r.height > 0) {
                        console.log('[KA] block offscreen input');
                        return;
                    }
                }
            } catch (e) { }
            return _focus.apply(this, arguments);
        };
    } catch (e) { }

    // 兜底：focusin 后 900ms 内没有触摸就把焦点撤掉
    document.addEventListener('focusin', function (e) {
        var t = e.target;
        if (!t) return;
        if ((t.tagName || '').toLowerCase() !== 'input') return;
        if (Date.now() - lastTouch > 900) {
            setTimeout(function () {
                try { t.blur(); } catch (err) { }
            }, 0);
        }
    }, true);
})();
