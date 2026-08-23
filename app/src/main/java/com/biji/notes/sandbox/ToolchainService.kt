package com.biji.notes.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.biji.notes.MainActivity
import com.biji.notes.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 装工具的时候把进程钉住。
 *
 * 没有它的下场：模型点了「装 zig」，用户随手锁屏，几十秒后进程被冻结或被
 * 回收，230 MB 解到一半，环境留在半装状态 —— 而且下一轮模型还以为装好了。
 * 前台服务把进程提到 foreground 优先级，partial wakelock 保证屏幕灭了
 * CPU 还在跑。
 *
 * 这个服务**不拥有安装**：job 跑在 [ToolchainJobs] 的进程级 scope 上，
 * 这里只做三件事 —— 举通知、拿 wakelock、活儿干完把自己收掉。所以服务被
 * 系统干掉也不会中断安装（只是失去保活），反过来安装结束服务会自动消失。
 *
 * targetSdk = 28（为了绕 W^X，见 build.gradle.kts）在这里顺带省了两件事：
 * Android 14 的 foregroundServiceType 强制和 Android 12 的「后台不许起前台
 * 服务」都按 targetSdk 门控，对我们都不生效，[startForeground] 走老路即可。
 *
 * 通知渠道是独立的，不碰 notif/ChatNotifier 那个 —— 那个是「AI 回答完了」
 * 的提醒，要震动要 DEFAULT 重要性；这个是长期驻留的进度条，必须 LOW、
 * 不出声，否则装个 zig 用户能被打扰十几次。
 */
class ToolchainService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    /** 没活儿了之后的收尾定时器。连着装两个工具时别把服务停了又起。 */
    private var idleJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 必须在 5 秒内 startForeground，而且要赶在任何 stopSelf 之前 ——
        // 「起了前台服务却没举通知」是必崩的。所以这里先举一个占位的。
        //
        // wakelock **不**在这里拿：这一刻可能还一个 RUNNING 的 job 都没有（服务是
        // 提前 1.5 秒起的），空举着 wakelock 没有意义。第一条 active 立刻会到，
        // 由 render() 按「有没有真的在跑的活儿」决定拿还是放。
        startForeground(NOTIF_ID, build(ToolchainJobs.active.value.firstOrNull()))
        scope.launch {
            ToolchainJobs.active.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次被拉起都重新举一次：服务可能是被通知栏的取消按钮唤醒的，
        // 那条 PendingIntent 走的也是 startService。
        startForeground(NOTIF_ID, build(ToolchainJobs.active.value.firstOrNull()))
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_JOB_ID)
                scope.launch {
                    if (id.isNullOrBlank()) ToolchainJobs.cancelAll() else ToolchainJobs.cancel(id)
                }
            }
        }
        // 进程死了 job 也就没了，重启一个空服务毫无意义。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- 通知 -----------------------------------------------------------

    private fun render(active: List<ToolchainJobView>) {
        val running = active.firstOrNull { it.state == ToolchainJobState.RUNNING }
        // wakelock 严格跟着「有没有 RUNNING 的 job」走：
        //  * 只剩排队中的（前一个刚做完、下一个还没抢到那把单槽锁）→ 没人在下载 /
        //    解包，放掉；
        //  * 一条都不剩（进了下面 1.5 秒的收尾宽限）→ 立刻放掉，不用等 onDestroy。
        // 每来一条进度也会重新 acquire 一次，那等于把超时刷新一遍 —— 见
        // [acquireWakeLock] 里为什么超时的语义要从「装了多久」改成「多久没动静」。
        if (running != null) acquireWakeLock() else releaseWakeLock()

        val head = running ?: active.firstOrNull()
        if (head == null) {
            // 留一点余地：模型常常一口气起好几个安装，上一个结束到下一个
            // 冒头之间有个空档，停了再起会闪通知、还白白放掉 wakelock。
            if (idleJob?.isActive == true) return
            idleJob = scope.launch {
                delay(IDLE_GRACE_MS)
                if (ToolchainJobs.active.value.isEmpty()) {
                    ServiceCompat.stopForeground(this@ToolchainService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return
        }
        idleJob?.cancel()
        idleJob = null
        notificationManager().notify(NOTIF_ID, build(head))
    }

    private fun build(v: ToolchainJobView?): Notification {
        val title = when {
            v == null -> "正在准备安装"
            v.stepCount > 1 -> "正在装 ${v.stepLabel}（${v.stepIndex + 1}/${v.stepCount}）"
            else -> "正在装 ${v.stepLabel}"
        }
        val text = v?.let { ToolchainJobs.progressLine(it) } ?: "准备中"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, ToolchainService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_JOB_ID, v?.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            // 进度每几秒刷一次，不加这个每次都会重新提示一遍。
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "取消", cancel)
        val pct = v?.percent ?: -1
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = notificationManager()
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "工具安装", NotificationManager.IMPORTANCE_LOW).apply {
                description = "下载和安装开发工具时保持后台运行"
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ---- wakelock -------------------------------------------------------

    /**
     * 前台服务保住的是「进程不被杀」，保不住「CPU 不休眠」。装大件时用户
     * 多半已经锁屏走开了，没有 partial wakelock 的话下载和解包会被拖到
     * 屏幕再次点亮。
     *
     * 超时的语义是**「多久没动静」而不是「装了多久」**：每来一条进度就重新
     * acquire 一次（`setReferenceCounted(false)` 下这就是刷新超时），所以正常
     * 推进的安装想装多久装多久，而一个卡死的 job（下载连接半开、服务端不回也
     * 不断）最多再钉住 CPU [WAKE_TIMEOUT_MS] 就自己松手。
     *
     * 原来是 onCreate 拿一次、30 分钟平铺 —— 卡死的多熬一倍时间，而一个诚实地
     * 装了 40 分钟的大件反倒会在第 30 分钟被断掉保活。两头都不对。
     *
     * 进度上报本身是按档发的（下载每 5%、解包每 2000 个文件），所以这个窗口必须
     * 给得宽：慢网络下 5% 走十几分钟是可能的。松手也不等于安装被杀，只是屏幕灭着
     * 的时候会被系统拖慢，下一条进度到达时又会重新拿上。
     */
    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing != null) {
            // 刷新超时。isHeld 为假说明上一轮超时已经到了，acquire 会重新拿上。
            runCatching { existing.acquire(WAKE_TIMEOUT_MS) }
            return
        }
        wakeLock = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "toolchain_install"
        private const val NOTIF_ID = 0x70C1
        private const val WAKE_TAG = "biji:toolchain-install"
        /** 「多久没有进度就松手」。每条进度都会把它刷新一遍，见 acquireWakeLock。 */
        private const val WAKE_TIMEOUT_MS = 15L * 60 * 1000
        private const val IDLE_GRACE_MS = 1_500L

        const val ACTION_CANCEL = "com.biji.notes.action.TOOLCHAIN_CANCEL"
        const val EXTRA_JOB_ID = "job_id"
    }
}
