package com.katorch.npu;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 只干一件事：在「从最近任务划掉 app」时把后端关掉。
 *
 * 为什么不能用 Activity.onDestroy：
 *   实测（Android 15）划掉任务时 Activity 只收到 onStop(isFinishing=false)，
 *   onDestroy 根本不调用，进程被系统直接回收 —— 关后端的逻辑永远不会执行。
 *   Service 的 onTaskRemoved() 才是任务被移除时的可靠回调，
 *   配合 manifest 里的 android:stopWithTask="false" 才能赶在进程被杀之前跑起来。
 */
public class StopService extends Service {

    static final String STOP_SH = "/data/data/com.termux/files/home/npt/ka_stop.sh";
    static final String LOG = "/data/local/tmp/ka_stop_log.txt";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;   // 划掉后不要再把进程拉起来
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        android.util.Log.i("KAtorch", "onTaskRemoved → 关闭后端");
        try {
            // 不依赖设备上的脚本文件，命令内联（pkill 是 toybox 自带的，一定有）。
            // 带 -t 1：Magisk 的 su 默认沿用调用者的 mount namespace，app 的 namespace 里
            // 看不到 /data/data/com.termux，接管 init 的全局 namespace 才有完整视野。
            String cmd = "(id; "
                    + "pkill -9 -f 'main.py --cpu'; sleep 2; pkill -9 host_new; sleep 1; "
                    + "echo '--- 剩余进程:'; ps -A | grep -E 'python|host_new' | grep -v grep | wc -l; "
                    + "free -m | head -2) > " + LOG + " 2>&1";
            Runtime.getRuntime().exec(new String[]{"su", "-t", "1", "-c", cmd});
        } catch (Exception e) {
            android.util.Log.e("KAtorch", "onTaskRemoved 执行失败: " + e);
        }
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
}
