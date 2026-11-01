# 小米录屏导致点击浮窗消失：现场与修复

日期：2026-11-01。用户在真机上手动复现，诊断包保留原有关闭行为，只增加可关闭事件打点。

## 已保存的现场

手机1280×2772，旋转0。16:26:53打开定时浮窗；16:26:58开启小米系统录屏浮窗后，收到`feedback_interrupt`。此时`connected=true`、`timed=CLOCK`、`timedVisible=true`、`locked=false`，显示标识与任务开始尺寸完全一致。

单调时基上的事件顺序：

| elapsedRealtime（ms） | 事件 | 观察 |
| --- | --- | --- |
| 5651845 | feedback_interrupt | 服务连接正常，浮窗可见 |
| 5651846 | stop_all | 调用栈明确为`ClickService.onInterrupt → stopAll` |
| 5651851 | view_detached | 同一个定时控制窗被移除 |
| 5651860 | view_detached | 同一个准星被移除 |

进程PID前后均为12635，没有`service_unbind`、`service_destroy`或显示变化记录。之后窗口快照中仍有小米录屏窗口，点击控制窗与准星已经不在。这证明本次消失由应用的`onInterrupt`处理主动触发，而非仅凭“录屏出现”推断原因。

`view_detached`记录的是移除回调发生时刻；Android此时尚可保留`attached=true`、`shown=true`，不能单凭这些瞬时字段判断移除失败。最终移除状态由之后的系统窗口快照交叉确认。

Android将[onInterrupt](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onInterrupt())定义为中断无障碍反馈（例如语音），与[onUnbind](https://developer.android.com/guide/topics/ui/accessibility/views/service)区分。点击器不输出这类反馈，不能据此关闭浮窗、取消等待任务或认定服务断开。

## 诊断方式与边界

`OverlayDiagnostics`采用[Log.isLoggable](https://developer.android.com/reference/android/util/Log#isLoggable(java.lang.String,%20int))按标签开关。默认不启用DEBUG；本次通过`log.tag.AhaoOverlayDiag=DEBUG`临时启用，结束后还原原值。只在服务连接/断开/中断、配置变化、受保护广播、显示浮窗、关闭全部窗口、窗口可见性/附着变化时记录，不采样时钟帧或每次点击。关闭时记录最多8层本应用调用栈，单条详情限制3000字符。状态取值或写日志失败不影响原流程。

主机按标签连续采集，文件按1MiB轮转，最多3份、4096行、20分钟；不清空系统日志，不上传日志。原始日志、窗口/服务快照、构建记录和采集脚本保存在被忽略的`.tools/recorder-overlay-20261101/`。首次设备端文件采集存在写入缓冲，未将空文件误认为没有事件；切换到主机逐行刷新后，先验证探针事件已经落盘，再请用户复现。手动复现的原始事件已完整保存。

## 修复与验证

仅移除`onInterrupt`中的`stopAll`，保留诊断事件。真正服务断开、锁屏/熄屏、真实旋转或尺寸变化、异常、用户停止/关闭仍沿用现有停止路径。不更改调度、校时或点击算法，不自动恢复旧任务。

模拟器通过系统`AccessibilityManager.interrupt()`派发真实反馈中断，并在日志中确认回调确实送达。旧实现先出现`Feedback interruption closed the TIMED panel (waiting=false)`失败，原样保存。只移除回调中的`stopAll`后，单点/多点、空闲/等待四种组合均通过，核对原窗口对象保留、等待任务不取消、零误点击；两种等待任务在真正断开服务后均停止，重连不恢复。另执行真实旋转、熄屏、服务断开回归，停止保护通过。

核心83、应用25项单测、Lint及Debug/R8 Release构建均通过。修复版覆盖安装到API36小米真机并核对安装包SHA-256一致，用户复测明确确认“点击浮窗不再消失”。16:37:08再次收到反馈中断，当时是`ARMED/WAITING`，服务连接、尺寸与浮窗状态正常；没有随之调用`stopAll`或移除窗口。直到单调时间6280156（约18.163秒后），用户主动点击浮窗关闭图标，才从UI关闭调用栈进入`stopAll`并移除窗口。这一正常关闭保留在日志中，不把它误计为问题复现。

复测后已停止采集、还原日志属性，清理模拟器测试组件。真机只验证了本机定时浮窗的本次场景，多点反馈中断由模拟器覆盖；不外推其他系统/录屏软件，也不作新的点击精度或性能保证。修复、测试与诊断结论纳入版本管理，原始现场保留在忽略目录。证据索引见[2026-11-01-recorder-overlay.json](acceptance/2026-11-01-recorder-overlay.json)。
