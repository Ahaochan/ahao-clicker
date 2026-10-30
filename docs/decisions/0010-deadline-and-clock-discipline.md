# ADR 0010：定时截止与长期校时

- 日期：2026-10-27。
- 状态：已实现；55 个单测、28 项模拟器场景、R8 两组各 30 次及超过半小时等待验证通过，本轮按用户确认仅在模拟器验收，真机未验证。
- 替代 ADR 0004/0008/0009 中“从启用到结束一直冻结参考”及“到点后才等待窗口移除”的定时单击规则。传统连点/录制引擎不变。

## 决定

时间仍由校时锚点加 elapsedRealtime 差值直接计算，显示次数和回调耗时不进入计算。长等待在距截止超过 5 秒时采纳同一来源的较新有效样本，重新计算绝对单调截止；更新时使旧定时回调失效。新样本使目标已过则结束，单次校正大于 1000 ms 或突然跨入最后 5 秒则失败停止，不追补。最后 5 秒固定参考以隔离链路抖动。来源身份变化停止；没有新鲜校时的任务不得进入最终窗口准备。

截止前 100 ms 移除准星输入窗口，保存最后可见中心；最后 100 ms 准星短暂不可拖动，到点后使用该位置。窗口仍需至少 50 ms 生效；如果系统延迟使准备晚到，则等待剩余生效时间而非冒险把触摸送给自己。等待、准备、派发都可取消；停止、断开、异常仍不能恢复任务。完成后通常 500 ms 恢复准星。

使用现有 androidx.core 的异步主线程 Handler 安排定时截止，减少同步屏障影响；窗口、状态与调度仍由同一主线程拥有，不加忙等待、额外高频轮询或永久高优先级线程。每次唤醒重新核对绝对截止，提前回调不会派发；最多 250 ms 的迟到关闭边界保留，不视为精度承诺。

定时单击的点按持续从 40 ms 缩至 1 ms，以减少等待 UP 的固定成本；平台输入采样与目标处理仍可能使 onClick 晚于 DOWN。只在独立目标实际接收与取消回归通过后采用；传统录制/连点仍为原持续时间。

## 测量与边界

可选 benchmark 构建继承 R8 Release，并用本机调试证书签名；同签名保护的测量 Provider 只存在于该构建。独立目标的仪器化驱动只附加在目标进程，分别收集计划截止、派发、目标 DOWN 和 onClick。正式 Debug/Release 不包含测量控制；原有公开表单 Release 多点脚本仍暂停。

SystemClock 单调计时避免刷新回调累加误差，但振荡器和网络授时误差仍存在，需要有效的周期校时。无障碍服务不提供硬实时 deadline API，主线程被阻塞、系统输入链路及目标应用都可能延迟；不提前派发来伪造低误差，不使用 Root、精确闹钟或新增唤醒权限。休眠/屏幕关闭仍停止任务。

来源：[SystemClock](https://developer.android.com/reference/android/os/SystemClock)、[Handler](https://developer.android.com/reference/android/os/Handler)、[dispatchGesture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,android.accessibilityservice.AccessibilityService.GestureResultCallback,android.os.Handler))。

完成/失败/过期后仍显示的浮窗改用最新来源参考，不能保留任务旧锚点；补充决定及一小时外部对照见 [ADR 0011](0011-floating-clock-resynchronization.md)。

输入窗口提前准备由 [ADR 0012](0012-clock-paint-time.md) 补充为 250 ms 提前量、150 ms 切换余量，以实际接收复测，仍不提前派发或自动补点。
