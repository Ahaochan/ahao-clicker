# ADR 0011：悬浮时钟的参考生命周期

- 日期：2026-10-27。
- 状态：已实现；58 个单测、31 项模拟器场景、一小时 121 次独立对照通过。见 [验证报告](../CLOCK_DRIFT_RESULTS.md)。
- 范围：只修正定时任务结束后仍显示的时钟，不改变任务的临界锁定规则。

## 问题与决定

此前 ARMED 和 RESULT 均使用 oneShot.reference。完成后 RESULT 可持续存在，虽然网络还在周期同步，显示却永远使用旧锚点，硬件频率差或来源校正会继续积累在显示中。

只有 ARMED 且阶段为 WAITING/RUNNING 时使用任务参考；完成、失败、过期以及普通时钟使用当前所选来源的最新参考。完成后重新同步不会恢复点击或更改已结束任务。任务最后五秒固定参考的规则保持不变。

显示始终按 `epochAnchor + elapsedRealtime - elapsedAnchor` 重算。刷新频率只决定画面滞后，不参与时间累计。[SystemClock](https://developer.android.com/reference/android/os/SystemClock) 说明 elapsedRealtime 包含深度休眠，而 [Handler](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,long)) 的唤醒基于 uptimeMillis；唤醒只是重算的机会，不能拿回调次数当经过时间。屏幕关闭会停止任务及浮窗，当前产品没有睡眠中持续点击的承诺。

## 验证边界

测试先将独立 HTTPS 源前移 4000 ms，旧版实际绘制字符相对源落后 4048 ms；修复后前后跳变及后台失败恢复均需跟上，且不重复点击。

可选 benchmark 构建只增加同签名测量观察器。在真实 View 绘制遍历读取 Canvas 使用的字符及 elapsedRealtime，独立测试进程自行发 OPTIONS、解析京东响应，绝不写回应用参考。观察器不主动刷新或绘制。普通 Debug/Release 不含此观察器或测量 Provider。

网络授时是估计：RTT 中点不能消除路径不对称；京东请求编号末段的时间语义没有公开精度 SLA。保留上次参考可使断网时继续走时，但无法保证长期离线不漂移；间隔过大也会扩大振荡器误差。使用 `60_000 ms` 校时，观察最大误差和校时年龄，不承诺任何配置或无限时长下绝对毫秒准确。
