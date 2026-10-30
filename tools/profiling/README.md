# 性能轨迹采集与离线分析

先使用 JDK 17、非 C 盘 Gradle 缓存，开启模拟器无障碍服务。诊断仅在 `-PtimingBenchmark=true` 测量包中开启 `profileable`，不修改生产算法，不需要安装新的 IDE 插件。

```powershell
# CPU 99 Hz/CPU + 调度 + 帧；每轮使用新的 Label，保留失败。
.\scripts\timing-acceptance.ps1 -Samples 10 -TraceConfig tools/profiling/android.pbtxt -TraceAtProcessReady -Label diagnosis-cpu
# 去掉调用栈采样，其余系统源相同。
.\scripts\timing-acceptance.ps1 -Samples 10 -TraceConfig tools/profiling/system.pbtxt -TraceAtProcessReady -Label diagnosis-system
# 完全不启用 Perfetto 的相同 APK 对照。
.\scripts\timing-acceptance.ps1 -Samples 30 -Label diagnosis-control
```

时钟负载用 `-ClockDurationMillis 120000 -ClockSampleMillis 1000 -ClockErrorLimitMillis 50` 替代 `-Samples` 参数。严格门槛失败仍保存 result.json、trace 和资源样本，再恢复普通 Debug。每组 APK 与 R8 mapping 一起保存；不能将不同包、不同网络时段当作严格随机 A/B。采集最长 5 分钟、文件最大 128 MiB；停止会话使用本次唯一标识，成功取回后才删除设备副本，不操作其他会话。

点击 CPU 采样优先使用 `-TraceAtProcessReady`：独立目标进程已运行、但 TargetActivity 尚未启动时建立采集会话，再由主机释放有界屏障，避免在进程创建前采样而得不到目标栈。`-TraceAfterReady` 则在目标界面及校时就绪后建立会话，可能预热界面；两者互斥且仅适用于点击轨迹。时钟负载不要传这两个选项。屏障最多等待 30 秒，失联会失败并停止，不自动执行后续点击。

结果现在记录每步状态、播放控件操作及失败快照。appState 是触发检查点的原始读数；diagnoseState 是稍后的详细读数，附带可见控件、选择器和最近浮窗反馈。跨进程快照有起止时间，不伪称原子采样。两个 APK 都会在安装时归档。

以下仅用于独立反例，不能当作自然性能结果，外层测试脚本失败是预期行为：

```powershell
# 读取状态时任务可能已完成，验证“非 WAITING”不等于“从未启用”。
.\scripts\timing-acceptance.ps1 -Samples 1 -SoakMillis 3000 -AfterPlayDelayMillis 4000 -Label diagnostic-late-read
python -B tools/profiling/check_arm_diagnostic.py .tools/timing/diagnostic-late-read/result.json
# 故意过期后才发送播放操作，记录拒绝路径和真实触摸回执。
.\scripts\timing-acceptance.ps1 -Samples 1 -BeforePlayDelayMillis 2500 -Label diagnostic-late-start
```

BeforePlayDelayMillis 默认 150，AfterPlayDelayMillis 默认 0；非默认值仅允许模拟器点击测试，不改变产品定时器。不要把 WAITING 检查点的失败直接解释为调度性能故障。

短等待的产品/测试前置工作需分别记录。新增 `-LeadMillis 4000` 可固定另一组等待时间（范围 500～30000，默认仍为 1800），不能与时钟或 SoakMillis 同用；改变等待时间后不能与旧组混合声称同条件性能改善。原始结果记录 requestedSamples 和 leadMillis。每轮仍遇到首个失败即结束，没有自动重试失败任务。

`tools/timing-report.py` 现在报告 p99 和 50 ms 超限数量/比例；小样本 p99 常等于最大值，不外推可靠性。成功样本的延迟分布与失败/未执行任务数分别列出，失败快照已有的实际 onClick 单独统计；历史没有回执时保持未知，不补成零。

从 [官方入口](https://get.perfetto.dev/trace_processor) 获取 Trace Processor，选择 Windows 版本并校验入口中列出的 SHA-256；把分析器放在本机非版本控制的工具目录，不引入生产依赖。

```powershell
python -B -m unittest discover -s tools/profiling -p test_*.py
python -B tools/profiling/analyze.py --processor .tools/perfetto-analysis/bin/trace_processor.exe .tools/timing/diagnosis-cpu .tools/timing/diagnosis-system
```

每个 `analysis/` 目录保留执行的 export.sql、原始 SQL 输出、分析器健康提示、summary.json 和基于实际样本生成的 SVG 火焰图。也可将 capture.perfetto-trace 在本地 Perfetto UI 打开，查看逐事件时间线；无需上传轨迹到外部存储。

分析按实际测量窗口裁剪，CPU 火焰图宽度表示采样 CPU 占比，不能当作墙钟耗时。调度中的 `R/R+` 是可运行但未执行，`Running` 是正在 CPU 上运行；`S` 仅表示睡眠，缺少其他证据时不能直接认定是某个锁或消息队列。毫秒 JSON 对齐纳秒轨迹有约 1 ms 量化边界；框架 slice 不是自定义业务标记，未标注的函数区间应明确保留未知。

稳定时钟 CPU、帧时序、校时参考估计、目标 DOWN 与 onClick 分别报告。采样会扰动结果；模拟器的 SwiftShader 和宿主机调度不代表手机硬件。

## 测量修复与固定单变量探索

本轮独立协议见 [计划](../../docs/MEASUREMENT_REPAIR_PLAN.md)，当前状态见 [结果](../../docs/MEASUREMENT_REPAIR_RESULTS.md) 和 [完成审计](../../docs/MEASUREMENT_REPAIR_AUDIT.md)。它不替代冻结V3正式矩阵。原始数据保存在本机`.tools/measurement-repair/`，不能把历史采样模式、不同APK或跨boot结果合并。

- `frame_protocol.py <trace> --out <newAuditDirectory>`逐包无损审计，保留原始包位置、字段和未知主呈现类型；输出目录必须不存在。
- `v3-frame-export.sql`与`v3_analysis.py`使用创建前唯一窗口、HWUI原始令牌和精确surface/display关联。未知呈现与歧义不能用最近时刻补齐。
- 时基用elapsed前后包围marker、BOOTTIME身份与clock_snapshot不确定度；打点调用延迟不等于漂移。
- `v3_host.py`分别支持off/light/frame；off关闭高频观察器及旧字符串绘制监听器，保留目标入口标量。最新测量版仅在失败分支记录有界原因，普通应用不安装该观察器。
- `run_repair_discovery.py <newExperimentRoot> --adb <adb> --prefix <uniquePrefix>`按ABBABAAB运行归档的variant-A/B APK，每批10次预热＋25次测量；目录需先保存共同源码、mapping、hash及两个版本。目标hash和boot变化即拒绝继续，失败不自动重试。运行中不能改APK、驱动、渲染配置、样本数或保留门槛。
- `repair_report.py <batchDirectory> --warmup 10`报告实际接收分母、DOWN/UP/onClick及原始ARM提前量。`repair_compare.py <experimentRoot>`应用预注册判定，拒绝缺批、无效预热、提前接收和尾部回退；已有比较文件不覆盖。探索通过仍需要独立确认，不能自动批准生产保留。
- `restore_v3.py --adb <adb> <runRoot>`只在采集与最终功能验证完成后恢复备份APK、偏好及设置，检查活动trace为0，并移除初始不存在的目标/仪器化包。使用本轮开始前的备份，不能用HEAD或其他轮次备份代替。

原始记录P99和资源开销未通过门槛的模式只作诊断，不用于正式收益比较；不能扣掉空计时器P99后声称门槛通过。对已有归档重新分析时先保存原分析输出或在副本操作，不能覆盖原始trace、二进制回执或失败记录。

### 固定探索图表和结案

可选`plot_repair.py <discoveryRoot> --out <newDirectory>`仅接受质量通过的固定每版100次数据，输出完整ECDF、配对P95柱图和带输入hash的plot-input.json。需要分析环境matplotlib（本轮3.11.2）；不加应用依赖，不覆盖已有图或输入。生成后检查SVG XML并目视PNG。结果与门槛见`docs/MEASUREMENT_REPAIR_RESULTS.md`，不要用图的某一分位低于基线代替3/4配对及整体非回退规则。

### 合成像素与真实DOWN精度

`capture_accuracy_clock.py --adb <adb> --out <newDirectory> --id <uniqueId> --duration 3600000 --sample 30000`使用独立目标AccuracyClockAcceptance，固定截图预热，不开启高频观察器。保存合成像素、设备时刻包围、独立来源请求与被动源快照。`accuracy_report.py <target-session> --out <newAnalysis>`分析像素；`--kind clicks <batchDirectory>`分析真实DOWN与外部源。分析环境需要Pillow/numpy（不进入生产依赖）；模板置信不足、坏时钟、参考冲突和区间跨50ms均保留为失败/未知。

点击主机支持`v3_host.py --oracle --lead 4000`，沿用有界公开流程；20分钟等待用`--samples 1 --lead 1200000`单列。不要把有限样本P99、截图中点或高RTT接口估计宣传为50ms硬保证。传输退出/文件取回失败现在返回失败状态并保留host-report；恢复查询用完整包名清单，未安装可选测试包是正常情况。实际本轮采集仍使用source-frozen中的旧监督器，后修复版本未用于已存数据。
