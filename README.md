# ahao-clicker · 阿浩点击器

面向 Android 的轻量原生自动点击器，使用 Kotlin 与原生 Views/XML，关注低占用、明确的停止控制和可验证的定时行为。当前版本为 **0.2.0**，只开发 Android。

## 当前功能

主页面分为“自动点击”和“时间同步”两个页签。

| 功能 | 行为 |
| --- | --- |
| 定时单击 | 拖动准星定位，用时、分、秒、毫秒四列滚轮设定时间；播放后只点击一次，失败或过期不自动补点。 |
| 多点循环 | 加号新增带序号的准星，拖动定位、轻点设置延迟，默认1000ms；按顺序循环，直到停止。运行时准星仍显示并允许触摸穿透。当前采用手动布点，不是触摸录制。 |
| 时间同步 | 内置京东、淘宝、阿里云、腾讯云和Cloudflare，两列展示毫秒时间及与本机时间差；点击选择参考来源，以毫秒输入同步间隔。 |
| 浮窗控制 | 定时与多点浮窗互斥；半透明背景、顶部按钮始终同排，支持移动及左右下角缩放。两种浮窗分别记忆尺寸，关闭重开或重启应用后恢复。 |

浮窗由无障碍服务提供，无需额外申请普通悬浮窗权限。首次开启服务前会说明用途，只有本应用服务真正连接且具备手势能力时才显示“可用”。

## 构建与安装

需要 **JDK 17** 和 **Android SDK Platform 36**；应用最低支持Android 8.0（API26）。使用仓库内Gradle Wrapper，不依赖全局Gradle。通过IDE或本机`local.properties`指定SDK目录，该文件不提交。

Windows推荐在PowerShell中运行：

```powershell
# 替换为自己的JDK 17目录。
$env:JAVA_HOME = 'D:\JDK\jdk-17'
.\scripts\build.ps1 -Action check
```

`check`执行核心/应用单测、Lint、Debug和Release构建。仅构建APK可用`-Action assemble`。

**Gradle依赖和Wrapper分发缓存不得放在C盘。** 已有D/E盘`GRADLE_USER_HOME`配置应保留；未设置时，构建脚本默认使用项目内`.gradle-user-home`，并拒绝C盘缓存。项目自身也应位于非C盘。默认优先使用阿里云HTTPS镜像，保留官方仓库后备；需要关闭镜像时可传Gradle项目属性`-PuseChinaMirrors=false`。

镜像开关传给Wrapper，而不是`build.ps1`的参数。先在同一终端运行上述构建脚本，确认非C盘缓存检查通过，再执行：

```powershell
.\gradlew.bat -PuseChinaMirrors=false :app:assembleDebug
```

连接手机、开启USB调试并允许电脑授权后，使用SDK的`adb`，将下面的`SERIAL`替换为`adb devices -l`中状态为`device`的手机序列号：

```powershell
adb devices -l
adb -s SERIAL install -r -t .\app\build\outputs\apk\debug\app-debug.apk
adb -s SERIAL shell am start -n com.ahao.clicker/.MainActivity
```

覆盖安装会保留应用数据；签名不匹配时应先检查安装包，不直接卸载清空配置。Release产物位于`app/build/outputs/apk/release/`，正式分发前需要自行签名，签名文件不入库。IDEA或Android Studio均可导入工程，构建以Wrapper为准。

## 使用步骤

1. 在“自动点击”页完成第一步，开启阿浩点击器的无障碍服务，返回后确认状态为绿色“可用”。
2. 在“时间同步”页选择数据源，设置并保存同步间隔，确认有成功样本。默认间隔60000ms；允许输入1000～86400000ms，NTP来源实际请求至少间隔60000ms。任务执行中不能修改来源和间隔。
3. 返回点击页，第二步选择定时或多点浮窗。打开浮窗、布点、编辑参数均不会自动开始点击，必须明确按播放。
4. 定时单击：拖动准星到目标位置，点击浮窗的计划时间进行微调。新开浮窗时，小时取当前参考时间，分、秒、毫秒沿用最后一次确认值；首次无记录时全部取当前时间。确认后按下一次到达的时刻安排，已过则为明天。按播放启用，时间缺失或过期时先打开滚轮确认。
5. 多点循环：按加号新增点位，拖动准星定位，轻点编辑延迟或删除。第一点延迟从播放开始算，后续从上一个点开始算；最后一点后按第一点延迟进入下一轮。至少150ms输入准备及串行手势约束仍适用，实际接收时刻另行验证。
6. 点击方形停止图标取消任务；右上角×停止并关闭浮窗。真正服务断开、锁屏/熄屏、实际显示变化或异常时停止，不自动恢复旧任务。

控制窗最小宽度128dp，小窗收紧按钮、图标与留白，按钮不换行。执行时冻结控制窗移动和缩放；缩放不改变准星坐标。定时等待期间可拖动准星，截止前约250ms短暂收起以准备输入，点击最后可见中心。更多边界与规则见[功能说明](docs/FEATURES.md)。

## 准时性与验证边界

分别看三个指标：外部时间源是否准确、悬浮时钟实际显示的时间、到点后目标是否收到点击。毫秒格式与周期同步本身不能证明绝对误差；高网络往返延迟的校时样本仍可能引入偏差。当前**不保证每次误差均在50ms以内**。

悬浮时钟使用最近成功样本和单调计时推进，不按刷新频率请求接口；长等待允许新鲜校时修正截止，最后5秒锁定参考。异常跳变或有效样本不足时停止，不追补。周期同步依赖进程存活，不修改系统时间，也不承诺休眠唤醒。具体结果与不确定性见[两部分精度分析](docs/ACCURACY_CAPTURE_RESULTS.md)、[测量修复与对照实验](docs/MEASUREMENT_REPAIR_RESULTS.md)。

截至2026-11-01，当前源码通过108项单测、Lint和Debug/R8 Release构建。录屏兼容修复通过API37模拟器的单点/多点、空闲/等待与停止保护回归，并在API36小米真机复测。该功能验收不等于重新完成全部机型、长期运行或点击精度认证；历史性能数据以各自版本和条件为准。

## 浮窗异常排查

先保留现场：记录复现步骤、是否已播放、发生时间，以及首页无障碍状态；先别反复关闭重开服务或清空数据。再区分应用主动关闭、系统隐藏、服务断开和进程退出。

已修复的小米录屏问题：系统发出`onInterrupt`反馈中断，旧代码误执行`stopAll`，主动移除了浮窗与准星。现在反馈中断保留浮窗和等待任务，真正断开等停止保护继续生效。原始调用链与修复前后证据见[录屏浮窗诊断](docs/RECORDER_OVERLAY_DIAGNOSIS.md)。

开发排查使用可复用的有界采集工具（需要Python 3.10+，不增加应用依赖），仍需将`SERIAL`替换为目标设备序列号：

```powershell
python -B tools/profiling/overlay_diagnostics.py --serial SERIAL
```

工具自动创建被忽略的本次目录、临时开启日志、生成唯一探针；看到`READY`后再复现，完成后按Ctrl+C。最多采集20分钟/4096行，文件按1MiB轮转、最多3份，正常结束会恢复原日志级别并写入`summary.json`。日志只覆盖关键事件，不逐帧或逐次点击打点。完整操作、异常恢复和字段解释见[浮窗诊断采集指南](docs/OVERLAY_DIAGNOSTICS.md)。不要清空系统日志、上传私人截图或将原始现场加入Git。

## 开发与资料入口

开发前阅读唯一的[AGENTS.md](AGENTS.md)，按“计划 → 失败测试 → 实现 → 回归 → 记录”推进。`app`负责Android界面、系统集成与生命周期，`core`是纯Kotlin/JVM任务及时间规则；诊断测量与生产行为修改的授权范围分开。

| 内容 | 入口 |
| --- | --- |
| 当前计划与验证记录 | [PLAN](docs/PLAN.md)、[VERIFICATION](docs/VERIFICATION.md) |
| 按需整理的架构问题 | [审查记录与触发条件](docs/ARCHITECTURE_REVIEW.md) |
| 功能与最新交互 | [FEATURES](docs/FEATURES.md)、[UI/UX V4](docs/UI_UX_V4.md) |
| 架构与浮窗决策 | [工程架构](docs/decisions/0002-android-project.md)、[多点规则](docs/decisions/0014-multipoint-overlay.md)、[尺寸记忆](docs/decisions/0017-overlay-size-memory.md) |
| 性能采集与方法 | [分析方法](docs/PERFORMANCE_ANALYSIS_METHODS.md)、[采集计划](docs/PERFORMANCE_ANALYSIS_PLAN.md)、[工具说明](tools/profiling/README.md) |
| 录屏问题证据 | [诊断报告](docs/RECORDER_OVERLAY_DIAGNOSIS.md)、[验收索引](docs/acceptance/2026-11-01-recorder-overlay.json) |

原始诊断数据保存在被忽略的`.tools/`，可提交的结论、方法与脱敏证据索引保存在`docs/`。SDK、本机配置、构建产物及签名凭据不进入版本控制；提交和推送按用户明确要求分别执行。

后续重点是更多机型兼容、真机优化构建的PSS/实际接收与长期稳定性验证；触摸录制、长按、滑动及任务导入导出按实际需求另行规划。
