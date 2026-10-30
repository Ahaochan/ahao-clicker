# 性能证据补采结果

日期：2026-10-28。完成剩余观测缺口的补采与分析：失败检查点现在有状态/回执，目标 CPU 栈已能可靠取得。未修改生产点击和时钟算法，未实现或承诺每次 50 ms；这不是一次性能优化验收。

## 未启用检查点的含义已明确

旧 `Did not arm` 只表示驱动读取状态时不为 WAITING。它不能单独证明应用从未启用。现在保留触发断言的原始 appState、随后 diagnoseState、播放控件属性与操作起止、目标 DOWN/UP/onClick 回执、每步阶段记录和跨进程快照起止时间。稍后快照不会覆盖原始状态；它们不是同一瞬间的原子读数。

| 情况 | 实际证据 | 可以得出的结论 |
| --- | --- | --- |
| 受控迟读 `followup-arm-completed` | 准备后明确播放，故意晚 4000 ms 读取；状态 COMPLETED，目标 B 收到一次真实点击 | “不是 WAITING”也可能是已经完成；不能把测试的非通过标志解释成没有点击 |
| 自然失败 `followup-target-before` | 播放动作开始比最初准备截止晚 1483 ms；操作被接受，观察到 STOPPED，目标触摸/回执均为 0 | 驱动的前置操作已错过准备时刻，不能将其作为任务已启用后派发迟到的样本 |
| 受控过期启动 `followup-arm-expired-start` | 在播放前故意等待 2500 ms；STOPPED、无触摸，浮窗反馈“请先停止当前任务，再修改配置或位置” | 验证了过期准备任务的拒绝路径，不是自然性能分布 |
| 界面启动前采样 `followup-clicks-cold-target` | 自然播放动作比准备截止晚 1277 ms；相同反馈、STOPPED、无触摸 | 复现了相同的过期启动类别，采集时也保留了首轮失败 |
| `followup-target-before2` | UIA 3 秒内未发现播放节点，但诊断接口能看到播放控件；没有被接受的播放动作 | 属于控件发现/测试前置阶段失败，不能称为点击器截止线程未准时派发 |

对最后两种过期样本，源码与反馈一致：测试驱动使用 `prepareTimed` 进入 PREPARED；预设时间过期后，播放路径尝试打开选择器，但 PREPARED 被 `hasActiveTask` 视为活动任务，选择器保护分支拒绝修改。该测试路径与当前首页单独打开时钟的 CLOCK 路径不同，本轮没有修复或改变这条状态路径。

最初一轮 `followup-arm-green` 只验证快照结构，读到 STOPPED 且没有回执；当时尚未记录浮窗反馈，不能唯一确定其内部异常。没有把它当成已完成反例，随后另做 `followup-arm-completed` 验证并取得明确完成证据。旧历史中仅有字符串的那一次失败，也不能事后凭空恢复其精确状态。

诊断先 RED（缺少 failureSnapshot），再 GREEN 验证快照；分析器另覆盖“完成但无回执不能当成功”“历史无快照仍未知”等情况。最终把驱动错误文案改成 `Arm checkpoint expected WAITING, observed ...`，不再暗示从未启用，检查条件和非通过标志保持不变。

## 目标栈缺失的外部条件已隔离

| 采集流程 | 目标采样记录 / 带 callsite 记录 |
| --- | ---: |
| 原流程，目标仅默认 debuggable | 620 / 0 |
| 原流程，仅增加显式 profileable | 1217 / 0 |
| 界面就绪后启动采样，显式 profileable | 796 / 793 |
| 界面就绪后启动采样，撤回新增声明 | 922 / 919 |
| 与上一行完全相同两个 APK，改回原启动顺序 | 本轮未导入目标 perf_sample，带栈为 0；目标仍有实际执行/接收 |
| 正式 99 Hz 补采，界面就绪后启动 | 1406 / 1403 |
| 49 Hz 质量复核，界面就绪后启动 | 490 / 490 |

“就绪后/原顺序”关键配对的点击器 APK、目标 APK 均逐字节相同。增加 profileable 不能解决原流程缺栈，撤回后仍能取得目标栈；最终没有给测试目标保留额外声明，也没有关闭 SELinux 或调整系统安全策略。

可重复的外部条件是采样启动顺序：原流程先启动 Perfetto，随后 instrumentation 创建目标进程；在进程已经运行后建立采样会话可以恢复目标栈。数据没有进一步区分 Perfetto 内部是进程名匹配、描述符建立还是其他初始化分支，未对此编造结论。

新增两种有界协议：`TraceAfterReady` 在界面和校时就绪后采样，可能预热界面；`TraceAtProcessReady` 在目标进程已运行但 TargetActivity 尚未启动时采样，避免把这种预热当成首轮性能改善。二者互斥，等待最多 30 秒，失联停止，不自动点击或补点。

## 目标等待链有了线程和原生栈证据

只读重放上一轮 203.3403 ms 样本：目标主线程的睡眠结束后，由该目标的 RenderThread 唤醒；同一窗口 RenderThread 在 guest 内记为 Running 179.1621 ms、Runnable 合计约 24.18 ms。原 CSV 与 trace 不变，新推导单独保存。该历史窗口没有当时的目标 CPU 栈，无法恢复其精确原生调用链。

本轮 99 Hz 补采中，一段 178.0858 ms 的 postAndWait 同样由目标 RenderThread 唤醒，主线程连续睡眠约 177.96 ms；RenderThread 在窗口内记为 Running 176.09 ms。同期 17 个实际渲染线程栈均经过：

```text
RenderThread → CanvasContext::draw
  → 帧提交 / EGL 同步
    → QemuPipeStream::commitBufferAndReadFully
      → qemu_pipe_read → read
```

其中 16 个还包含 EGL swap / native sync 路径。**这段 178 ms 等待位于点击回执之后，不能把它加进该次点击延迟。** 本轮最长的若干 200～350 ms 绘制等待也是回执后的开销；原生栈揭示了模拟器图形通信路径，但不证明它们导致同一点击超时。

仅系统轨迹对照则另有一段 91.4552 ms postAndWait，确实位于第 2 条样本的 UP → onClick 之间，同样由 RenderThread 唤醒；这一组没有 CPU 栈，不能用另一组的栈冒充该时刻的精确调用链。

结合证据，可以确认目标主线程会等待自己的渲染线程，且实际点击关键区间与回执后都可能出现这种等待；模拟器原生热点包含图形提交及宿主通信。不能仅凭 guest 的 Running 状态区分宿主机排队、SwiftShader 内部计算和虚拟 CPU 暂停，也不能推导手机硬件上的耗时极限。

## 正式补采与质量

| 组 | 实际接收 | 派发 / DOWN / onClick 最大迟到 |
| --- | ---: | --- |
| 99 Hz CPU 栈＋系统轨迹 | 10/10 | 59 / 69 / 108 ms |
| 仅系统轨迹，相同两个 APK | 10/10 | 109 / 141 / 257 ms |
| 49 Hz CPU 栈质量复核 | 5/5 | 62 / 87 / 147 ms |

这是小样本、顺序执行的诊断数据，不是随机 A/B，也没有实施生产优化。不能据此宣称某采样频率一定更准，更不能把受控反例或启动拒绝从整体测试记录中删除后声称全部达标。

99 Hz 正式组报告 2 条 perf CPU 丢记录，原轮次完整保留。3 个目标空栈样本发生在预先定义的测量窗口之后；没有把它们写成有效栈。降低到 49 Hz 的新配置仅用于质量复核，该组未报告 perf 丢记录或 skipped，490 个目标记录均有 callsite；仍保留 compact scheduler 的 info 提示。这不证明任意未来采样都无损，CPU 火焰图始终是抽样数据。

## 交付与边界

原始结果、各阶段失败、两个实际 APK、配置、mapping、trace、SQL、火焰图和校验值均保存。早期 RED/初次日志验证未归档目标 APK 的情况在索引中明确标记，不用后来的 APK 填补。旧 203 ms 原始文件指纹保持不变。

生产点击/时钟源码未修改；仅增强 benchmark 诊断接口、独立驱动与采集/分析工具。完整功能套件本轮未重跑，不把上轮结果当作本轮执行。构建、测试与恢复结果见证据索引的 checks。

59 个单元测试、11 个分析测试、标准及辅助构建/Lint 通过，21 个 SVG 格式校验通过。普通 Debug 与偏好配置已按指纹确认恢复，目标测试包已移除，活动采集会话为 0；IDEA 内嵌模拟器未重启。

本轮补采分析已足以进入按证据优化的下一阶段：先处理测试前置预算/检查点语义，再分别验证主线程竞争与图形提交策略；不把测试驱动前置失败算作派发性能，不用目标回执后的等待解释回执前延迟。50 ms 指标仍未达标，历史单次缺失的精确信息仍不能恢复。

- [本轮证据索引](acceptance/2026-10-28-perfetto-followup.json)
- [复现方法](../tools/profiling/README.md)
- [目标 CPU 火焰图，49 Hz 质量复核](../.tools/timing/followup-clicks-cpu49/analysis/target-flamegraph.svg)
- [目标/点击器调度时间线，99 Hz 正式组](../.tools/timing/followup-clicks-cpu/analysis/slowest-click-timeline.svg)
- [上一轮报告](PERFETTO_RESULTS.md)

分析语义参考 [Perfetto CPU 采样](https://perfetto.dev/docs/getting-started/cpu-profiling) 和 [线程唤醒与调度](https://perfetto.dev/docs/data-sources/cpu-scheduling)。原始大文件位于本机忽略目录，不加入 Git。
