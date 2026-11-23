# Changelog

版本号规则 `MAJOR.MINOR.PATCH`，`versionCode` 单调递增。本文件从 2.2.0 开始记录，更早的版本见 Git 历史与 GitHub Releases。

## 2.2.0 — 2026-11-23

保活增强 + 短信可靠投递。改动来自对拼多多 8.32.0 保活策略的静态逆向，保留了本项目已有的 WakeLock / WiFiLock / 原生守护 / 3 分钟心跳，并在其上补齐缺失的几层。

### 新增

- **JobScheduler 周期看门狗**（`KeepAliveJobService`，jobId 2001，15 分钟，`setPersisted(true)`）：
  进程被杀后系统到点创建进程，重新拉起前台服务；重启后任务依然存在，不依赖 BOOT_COMPLETED。
- **心跳存活判定**（`KeepAliveManager`）：服务每 60 s 写一次心跳时间戳，所有系统入口先判断"是否真的死了"再拉起，避免无效重启。
- **桌面小部件「SMSPost 状态」**（`StatusWidgetProvider`，`updatePeriodMillis=1800000`）：
  桌面存在小部件时，系统每 30 分钟发一次 `APPWIDGET_UPDATE`，即使进程已被杀也会被创建——这是本方案里唯一"系统周期性冷启动"的入口。点按小部件走 `PendingIntent.getForegroundService()` 冷启动转发服务。
- **保活设置卡片**（首页）：显示心跳时间 / 累计重启次数 / 电池优化状态 / 精确闹钟状态 / 机型；提供「自启动」「精确闹钟」「重启转发服务」三个操作，并按 ROM 跳转到对应的自启动管理页。
- **短信投递队列**：短信先落库（`queued` / `attempts`），由前台服务或"有网 Job（jobId 2002）"重试投递，失败最多 5 次。
- 后台启动前台服务被系统拒绝时，退化为一条可点按的"恢复转发"通知。

### 变更

- 前台服务类型：Android 14+ 使用 `specialUse`（`dataSync` 在 Android 15 有 6 h/24 h 运行配额，会掐断常驻转发），Android 11–13 仍用 `dataSync`。
- `BootReceiver` 扩展为统一入口：新增 `MY_PACKAGE_REPLACED`、`TIME_SET`、`TIMEZONE_CHANGED`、精确闹钟权限变化，全部走同一套幂等拉起逻辑。
- 通知栏新增「停止」操作；主动停止后所有保活入口静默，不会"停不掉"。
- `SmsReceiver` 只负责解析并入队（`goAsync` + 工作线程），不再把网络请求放在广播里。

### 修复

- 修复 `LogDatabaseHelper` 把 `/storage/emulated/0/smspost_log.db` 当"文件名"传给 `SQLiteOpenHelper` 导致的首次读写崩溃；现在直接 `openOrCreateDatabase(File)`，无"所有文件访问"权限时自动回退到应用内部数据库。
- 修复弱网/断网时短信直接丢失的问题。
- 修复重复 `JobScheduler.schedule()` 会重置周期任务、导致周期 Job 永远不执行的问题。
- 日志页不再依赖存储权限：数据库回退到内部存储后仍可正常显示。

### 兼容性

- `minSdk 30` / `targetSdk 36` / `versionCode 5`。
- 沿用 `signingConfig = debug`（与 2.x 既有 Release 一致），覆盖安装需同一签名。
