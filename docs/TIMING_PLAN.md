# 定时准时性与长期校时计划

日期：2026-10-26。目标是减少可控延迟并消除软件逐次累加误差，不承诺普通 Android 无障碍点击具有硬实时 1 ms 保证。

## 指标与基线

- 独立目标分别记录 DOWN 事件时间、收到 DOWN 的时间和 onClick 时间；应用记录计划与实际派发。报告样本数、成功接收数、提前次数、p50/p95/最大延迟，不能只用手势派发计数。
- 使用 R8、资源压缩、非 debuggable 的独立 benchmark 构建；测量驱动附加在独立目标进程。测试控制 Provider 只存在于该构建，受同签名权限保护，不进入 Debug/Release 正式应用。
- 同设备先测修改前，随后测修改后；用户于 2026-10-27 确认本轮仅模拟器验证，真机另期验收，不作为本轮完成条件。CPU/PSS 使用进程外采样，长时间观察至少 30 分钟。

## TDD 与修改顺序

1. 建立可复现的定时单击基准，区分网络授时误差与本机计划→派发→接收的延迟。
2. 先测试长时间等待重新校时、临近触发锁定、无效/过期参考拒绝、重复/提前回调、取消和陈旧回调。
3. 提前移除准星输入窗口，在截止前完成平台准备，仍使用最近可见准星位置；保证停止可以取消整个准备过程，点按期间不能恢复遮挡层。
4. 用异步主线程消息减少同步屏障影响；根据实际接收验证定时点按持续时间，传统录制/连点不随意改变。
5. 长期时间始终用“校时锚点 + 单调时间差”计算，不按刷新次数累加。长等待允许同一来源的新鲜校时更新截止时间，最后 5 秒锁定；失去新鲜校时或异常大跳变时终止，不追补点击。
6. 虚拟时间覆盖数日/不规则刷新/时钟改变；真实长时观察记录参考年龄、同步成功/失败、偏差与 CPU/PSS。网络失败不能声称绝对时间仍准确。
7. 标准构建、单测、Lint、功能回归及取消/断开检查通过后更新 README、ADR 和测量记录。

## 精度边界

Android 单调时间包含休眠，Handler 使用 uptime 且系统线程可能延迟；屏幕关闭仍停止任务，不借此增加唤醒权限。HTTPS 时间戳、链路不对称、NTP 服务误差、设备振荡器与目标应用处理都可能造成实际误差。单台设备短窗数据不能证明所有设备或任意时长精度。

参考：[SystemClock](https://developer.android.com/reference/android/os/SystemClock)、[Handler](https://developer.android.com/reference/android/os/Handler)、[dispatchGesture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,android.accessibilityservice.AccessibilityService.GestureResultCallback,android.os.Handler))。
