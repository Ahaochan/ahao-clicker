# 性能轨迹分析结果

日期：2026-10-27。本轮完成“先采集，再分析”的 Goal，没有修改生产点击或时钟算法；严格 50 ms 指标仍未通过。

2026-10-28 补充：目标栈缺失与未启用检查点已进一步补采，结果见 [后续报告](PERFETTO_FOLLOWUP_RESULTS.md)。旧 203 ms 样本确认由目标 RenderThread 唤醒，但历史原生栈仍无法恢复。下文保留当时证据与限制，不用后续采样冒充历史现场。

## 采集与质量

API 37、x86_64、6 个虚拟 CPU，实际查询到 OpenGL ES Translator / Google SwiftShader。设备 Perfetto v54.0，本地官方 Trace Processor v58.2，下载时核验官方 SHA-256。五组正式测量使用同一 R8 APK：`0fb66c0a28b3ea5ed27cacadf61fded145f6b846b2e419129990582be5249a34`。最后补充 API 范围 Lint 标注后 APK 仍逐字节一致。

| 正式组 | 采集内容 | 结果 |
| --- | --- | --- |
| trace-clock | 两分钟悬浮时钟、独立京东请求、CPU 栈/调度/帧 | 121 个有效对照，44 个估算偏差 >50 ms；trace 约 109 MB |
| trace-clicks-cpu | 30 次真实目标接收、CPU 栈/调度/帧 | 30/30 接收；trace 约 97 MB |
| trace-clicks-system | 去除 CPU 栈，仅调度/帧 | 首轮 `Did not arm` 退出，未形成成功样本；trace 约 5.8 MB。未记录失败即时状态，不能断定失败原因或实际点击总数 |
| trace-clicks-control | 同包不启用 Perfetto | 30/30 接收 |
| trace-clock-control | 同包不启用 Perfetto，两分钟时钟 | 121 个有效对照，12 个估算偏差 >50 ms |

全部正式采集结束后才对正式数据运行 SQL。执行的 SQL、分析器日志、结果、配置、mapping 和原始轨迹均保留。正式轨迹没有报告 ftrace overrun 或数据丢失错误；有 `compact_sched_switch_skipped` 6 个、`compact_sched_waking_skipped` 4～5 个 info 项，不能宣称任意边界事件都完整。独立时钟样本均处在轨迹覆盖范围内。

稳定测量窗口用于火焰图，不把启动期混入静态时钟热点。时钟组应用有 3567 个带 callsite 的样本，其中测量窗口 3338 个；点击组有 3779 个，窗口 3678 个。时钟组 1 个、点击组 5 个样本带展开错误标志，可能只有部分栈。目标进程虽有 CPU 采样记录，但没有获得有效 callsite（点击组 3139 个样本、栈为 0）；本轮不声称已经定位目标内部具体方法的 CPU 开销。

短采集失败同样保留：第一次 PowerShell `-o` 参数冲突、第二次配置文件被 SELinux 阻止读取，改从标准输入传入后成功。第三次短采集取得真实轨迹，但首次点击过期、无触摸记录；没有作为正式通过结果。测量包的 `profileable` API 29 属性首次被 Lint 提示后补充工具标注，普通 Debug/Release 不增加该声明。

## 发现一：本轮 CPU 热区主要在图形提交路径

静态时钟测量窗口：点击器 RenderThread 占单核 CPU 约 19.35%，主线程约 6.66%。应用 CPU 栈中，2283/3338（约 68.4%）来自 RenderThread；1567/3338（约 46.9%）包含 `eglSwapBuffersWithDamageKHR`。上层链路可见：

```text
RenderThread
  → DrawFrameTask
    → CanvasContext::draw
      → SkiaOpenGLPipeline::swapBuffers
        → eglSwapBuffers / 模拟器 EGL 通信
          → write / read / clock_gettime
```

包含 `libEGL_emulation.so` 的样本有 1661/3338（约 49.8%）。这些是重叠的包含统计，不能相加。不能因为叶子是 `write` 就推断应用写文件或业务日志过多；调用链指向图形路径。

静态浮窗层的 3430 个实际帧中，帧跨度 p50 5.41 / p95 22.34 / 最大 189.14 ms；1964 个直接标为 `Buffer Stuffing`，还存在组合标签。该标签表示未呈现的缓冲积压，可能增加显示延迟，并不等于每帧都出现视觉卡顿。帧跨度也不是时钟字符从取值到屏幕发光的精确延迟。[Perfetto 帧时间线解释](https://perfetto.dev/docs/data-sources/frametimeline)

**支持的结论**：模拟器的持续绘制/提交是明确 CPU 热区，有提交积压的框架证据；目前没有数据支持把时间数字计算认定为主要 CPU 瓶颈。尚未隔离宿主机软件 GPU、虚拟化调度及应用刷新策略的各自贡献。

## 发现二：慢点击包含调度等待和目标绘制等待

带 CPU 栈组第一条也是最慢的一条，从计划截止到 onClick 共 505 ms：

| 阶段 | 间隔 | 对应证据 |
| --- | ---: | --- |
| 计划截止 → 实际派发 | 81 ms | 点击器主线程 Running 13.71 ms，Runnable（R/R+）30.03 ms，Sleeping 37.26 ms；可见绘制、`postAndWait` 和 `FullSuspendCheck` slice |
| 派发 → 目标 DOWN | 33 ms | 实际接收时间戳；不把此段全部归为某一个 IPC 或输入函数 |
| 目标 DOWN → UP | 257 ms | 目标主线程 Sleeping 209.20 ms，其中连续睡眠 203.21 ms，与 `postAndWait` 203.34 ms 区间对应；外层 `draw-VRI[TargetActivity]` 203.89 ms |
| 目标 UP → onClick | 134 ms | 可见 `postAndWait` 44.09 ms 和 Binder transaction 43.84 ms，以及运行和调度等待 |

MotionEvent 中 DOWN/UP 的事件时间仍只相差 1 ms。257 ms 是目标实际处理两次事件之间的间隔，不能说成产品按压了 257 ms。嵌套 slice 与线程状态会重叠，不能把 203 ms 等数值再加到 505 ms 上。

**支持的结论**：这个样本不只是点击器计算慢；调度等待和目标界面等待渲染都有直接证据。框架 slice 给出了等待阶段，但目标 CPU 栈缺失、业务截止回调未额外插桩，所以不能完整指认每一段 Sleeping 的具体函数或锁。

同包无采样组的首条同样最慢，派发迟到 97 ms、DOWN 154 ms、onClick 231 ms；问题并非只在启用采样时出现。它仍不能证明这两次尾部延迟来自完全相同的原因。

## 发现三：授时参考与绘制滞后都需分别处理

| 时钟组 | 绝对显示估算偏差 p50 / p95 / 最大 | >50 ms | 参考估计误差范围 | 字符相对参考的滞后 p95 / 最大 |
| --- | --- | ---: | --- | --- |
| 带 CPU 栈和系统采样 | 40 / 181 / 327 ms | 44/121 | −317～+268 ms | 54 / 166 ms |
| 无 Perfetto | 26 / 64 / 90 ms | 12/121 | −19～+6 ms | 53 / 77 ms |

带采样组的 44 个超标样本中：21 个参考估计误差单独 >50 ms、9 个字符滞后单独 >50 ms、1 个两者均超、13 个两者单独均未超但合成显示偏差超限。无采样组参考误差无 >50 ms 样本，12 个显示超限中 6 个字符滞后本身已 >50 ms。

这里的“参考误差”是相对独立 HTTP 中点估计的差，**不是已知真实 UTC 的误差**。请求 ID 精度、单向网络延迟不对称和独立请求 RTT 不确定度仍在；不能据此证明京东绝对授时精度。字符也只是观察到的绘制数据，不是物理屏幕发光时刻。

**支持的结论**：不能用单一“Android 计数器差异”解释当前全部偏差，也不能把本轮显示超限全部归给网络。两条链路需要独立的质量预算。

## 采样扰动与不能下的结论

| 点击组 | 派发 p95 / 最大 | DOWN p95 / 最大 | onClick p95 / 最大 |
| --- | --- | --- | --- |
| CPU 栈＋系统轨迹，30 次 | 28 / 81 ms | 34 / 114 ms | 72 / 505 ms |
| 无 Perfetto，30 次 | 27 / 97 ms | 41 / 154 ms | 112 / 231 ms |

带采样时钟组的单核 CPU 区间中位约 29.58%，无采样约 22.58%；点击组分别 45.49% 与 40.51%。资源采样数量很少，且网络/宿主机负载未恒定；不能将差值当成采样器的精确因果开销。点击组也没有“所有统计量都更差”的一致关系。

不能从本轮推导真机极限、每次 50 ms 保证、长期不漂移、目标应用真实业务成功，或把首条样本删除后声称达标。仅系统轨迹组失败原因仍未知，目标栈缺失仍是观测缺口。

## 下一轮应验证的假设

1. 针对帧提交积压做单变量绘制实验，同时观察 CPU、字符滞后和帧队列；不能盲目提高刷新率，也不能通过冻结/提前时钟数字伪造准时。
2. 减少截止路径与主线程绘制的竞争，并用实际 DOWN/UP/onClick 验证；保留停止、取消及不自动补点。
3. 单独测试授时多样本估计和异常样本处理，不自动更换用户选中的来源。
4. 补足“未启用”失败时的即时状态及目标进程栈；将首次任务/绘制过渡作为独立场景，保留所有首轮结果，检验更轻量目标与普通按钮目标的差异。

这些是依据现有证据提出的下一轮实验，本轮没有实施上述生产优化。

## 文件与复现

- [采集与分析方法](../tools/profiling/README.md)
- [汇总与原始测量证据](acceptance/2026-10-27-perfetto-analysis.json)
- [时钟 CPU 火焰图](../.tools/timing/trace-clock/analysis/app-flamegraph.svg)
- [点击 CPU 火焰图](../.tools/timing/trace-clicks-cpu/analysis/app-flamegraph.svg)
- [最慢点击调度时间线](../.tools/timing/trace-clicks-cpu/analysis/slowest-click-timeline.svg)

原始 trace、SQL 导出和图表位于本机 `.tools/timing/`，不将大体积二进制、签名材料或分析器可执行文件加入版本控制。证据索引记录各文件 SHA-256；[CPU 采样语义](https://perfetto.dev/docs/getting-started/cpu-profiling)说明了为何火焰图不能替代调度时间线。
