# ahao-clicker · 阿浩点击器

面向 Android 的轻量原生自动点击器，优先关注低占用、稳定执行和明确的停止控制。本阶段不开发 iOS。

## 当前状态

0.2.0 采用“自动点击”“时间同步”两个页签。点击页只保留“设置无障碍服务 → 设置浮窗”两步，红绿文字状态随服务连接更新；浮窗由无障碍服务提供，无需另授普通浮窗权限。第二步提供定时点击和多点点击两个互斥浮窗入口；不再显示第三步、模式和坐标摘要。多点浮窗用加号新增带中心序号的准星，拖动定位、轻点编辑延迟（默认1000ms），按序持续循环直到按停止；运行时准星始终显示并让触摸穿透，停止后恢复编辑，不包含定时设置。时间页按两列比较京东、淘宝和已有 NTP 来源，显示三位毫秒与本机时间差，支持数字输入同步间隔。最新浮窗与交互见 [UI/UX V4](docs/UI_UX_V4.md)，双页签设计见 [UI/UX V3](docs/UI_UX_V3.md)，验证结果见 [验证记录](docs/VERIFICATION.md)。

此前真机已完成点击、录制、停止与悬浮时钟性能检查，历史结果见 [性能记录](docs/PERFORMANCE.md)。绝对精度、长时功耗和更多机型仍待验证；历史测量不能直接视为本次来源比较页面的性能结果。

定时单点和多点控制浮窗使用半透明背景，按住左右下角的角标拖动可放大或缩小；按钮保持可用，内容可滚动，准星位置和尺寸独立。执行期间冻结缩放，停止后可继续调整。两种浮窗分别记忆尺寸，关闭重开或重启应用后恢复，屏幕变化时限制在可用区域。详见[浮窗尺寸记忆](docs/decisions/0017-overlay-size-memory.md)。

浮窗支持[小尺寸响应式布局](docs/decisions/0016-responsive-overlay.md)，最小宽度128dp。播放/停止、加号、移动、关闭始终同排；窄窗缩小图标、按钮和内边距，停止和关闭常显。时钟与定时时间自动适配字号，小窗减少留白。

系统录屏发出的无障碍反馈中断不会关闭浮窗或取消等待任务；真正的服务断开、锁屏/熄屏及显示变化仍停止任务。小米真机复现与修复记录见[录屏浮窗诊断](docs/RECORDER_OVERLAY_DIAGNOSIS.md)。

最新 [V3 分析报告](docs/PERFORMANCE_ANALYSIS_RESULTS_V3.md) 和 [分析方法](docs/PERFORMANCE_ANALYSIS_METHODS.md) 已保存。小规模实测准入尚未通过，正式矩阵未启动；当前不能保证每次点击或可见时钟误差不超过 50 ms，也没有证据认定已达到设备物理极限。原始数据、失败记录、火焰图与分批统计保存在本地归档中，生产算法未在该轮修改。

2026-10-29完成[测量修复与单变量实验](docs/MEASUREMENT_REPAIR_RESULTS.md)：帧唯一关联与局部时基校验已修复；完整呈现和高频打点开销仍未通过准入。关闭观察器的固定A/B各100次实际接收，基线onClick P95/P99/最大为34.088/39.573/44.319ms（相对本地截止）；候选收益不一致，已撤回。32项功能及独立进程重建检查通过，原应用和设置已恢复。未把新boot的基线变化算作代码优化，也不承诺绝对每次50ms。

第二步开启定时点击浮窗时同时显示 FPS 风格准星，拖动后自动保存位置；定时等待时可移动，截止前约 250 毫秒短暂收起准星完成输入准备，触发时点击最后可见中心。

每次新开定时浮窗，小时取当前参考时间，分、秒、毫秒沿用最后一次滚轮确认的值；首次没有记录时全部取当前时间。取消不覆盖记录，关闭浮窗或重启应用仍保留分秒毫秒；已打开时重复点击入口不重置。点击该时间，用时、分、秒、毫秒四列滚轮微调，不需要年月日；确认时按下一次到达的时刻执行一次（未到为今天，已过为明天）。打开或编辑不会自动点击，仍须明确播放。

悬浮窗仅保留顶部播放/停止、四向拖动和右上角关闭三个图标，移除重复的底部定时按钮，主时钟下显示精确到毫秒的计划开始时刻，可直接点击编辑；有效时间直接播放，缺失或过期则从播放进入滚轮确认开启；启停给出短提示，停止保留浮窗，完成后恢复播放图标。

2026-10-29的[两部分精度实测](docs/ACCURACY_CAPTURE_RESULTS.md)发现：周期校时会更新参考，但高RTT样本可将时钟校快约190ms；正式100次按下全部接收，本地截止到DOWN最大12.347ms，相对独立京东参考却有2次可确认提前约164–165ms。当前50ms总精度目标未通过，应优先研究校时样本质量。时钟组因ADB中断只有96个有效对照，缺失原样保留；本轮未改生产算法，原环境已恢复。

## 技术方案

- Kotlin + 原生 Views/XML + View Binding。
- app 负责 Android 界面与系统集成，time 功能包独立管理校时、来源状态与时间页；core 负责平台无关的任务与时间规则。
- 通过 AccessibilityService 执行用户明确启动的任务；多点采用手动增加准星与独立串行执行器，历史连点和实际点击录制引擎保留回归，新公开入口不做触摸录制。
- 当前不引入 KMP、Compose、数据库或额外后台框架。

## 开发环境

| 工具 | 固定基线 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 9.3.1 |
| Android Gradle Plugin | 9.1.1（Android 内置 Kotlin） |
| Kotlin/JVM | 2.2.10 |
| Android SDK | Platform 36、Build Tools 36.0.0 |
| Android 设备 | Android 8.0 / API 26 或更高 |

使用支持 AGP 9.1 的 Android Studio，或通过命令行构建。将 Gradle JDK 设置为 17；不要使用全局安装的旧版 Gradle。

使用 IntelliJ IDEA 时需安装兼容的 Android 和 Android Design Tools 插件，并在 Gradle 设置中选择项目 Wrapper、JDK 17 和非 C 盘用户主目录。项目通过 gradle/gradle-daemon-jvm.properties 固定守护进程为 Java 17；本机需预先安装 JDK 17，不自动下载 JDK。

compileSdk/targetSdk 暂定 API 36。安装更新的 SDK 后，Lint 的 OldTargetApi 升级建议保留为提示；SDK 升级与行为适配单独验证，实际 API 兼容性错误仍会使检查失败。

SDK 可由 Android Studio SDK Manager 安装，或在已配置的命令行工具中执行：

```shell
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

按 SDK 工具提示完成许可流程。将 SDK 路径设置到 ANDROID_HOME，或创建不提交的 local.properties，例如 Windows：

```properties
sdk.dir=E\:/Android/Sdk
```

首次构建需要访问 Gradle 分发与依赖仓库。依赖默认优先使用阿里云的 Google、Central 和 Gradle Plugin 镜像，官方仓库作为后备；使用 -PuseChinaMirrors=false 可关闭国内镜像。版本和 Wrapper 校验和保持固定，不关闭 HTTPS 验证。

GitHub 托管 CI 使用官方仓库；国内本地开发使用默认镜像配置。镜像只影响依赖下载，不改变应用运行时的时间源。

### Gradle 缓存位置

**Gradle 依赖和 Wrapper 下载目录不允许放在 C 盘。** 本机已有 GRADLE_USER_HOME=D:\JetBrains\.gradle，可继续使用。

Windows 推荐通过 scripts/build.ps1 构建：保留现有非 C 盘 GRADLE_USER_HOME，未设置时默认项目内 .gradle-user-home；配置到 C 盘会直接报错。若通过 Android Studio 或直接运行 Wrapper，必须先在启动环境中设置非 C 盘 GRADLE_USER_HOME，不能依赖默认的用户目录。

例如为当前 PowerShell 会话设置到项目所在 E 盘：

```powershell
$env:GRADLE_USER_HOME = 'E:\Code\ahao-clicker\.gradle-user-home'
```

Android Studio 应在其启动环境中继承同一变量；修改后需要重启 IDE。不要使用 -g 或 gradle.user.home 系统属性覆盖到 C 盘。

## 构建与检查

Windows PowerShell：

```powershell
.\scripts\build.ps1 -Action test
.\scripts\build.ps1 -Action check
```

macOS / Linux：

```shell
bash ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

核心代码不依赖 Android SDK；但根工程仍需下载 Android 构建插件等依赖。没有 SDK 时可尝试只执行 :core:test，不能据此认定 Android APK 构建已通过。

Debug APK 输出到 app/build/outputs/apk/debug/。Release 当前构建未签名 APK，用于验证压缩流程，尚不可直接分发安装。

### 模拟器功能验收

启动 API 29+ 模拟器后运行（应用本身最低仍为 API 26）：

```powershell
.\scripts\acceptance.ps1 -Serial emulator-5554
.\scripts\acceptance.ps1 -Serial emulator-5554 -Case process_death
```

验收使用独立点击计数 APK 和本机 HTTPS 服务；内置来源场景会请求五个公开时间源，不操作真实账户。先完成普通构建以准备离线依赖；脚本保持 JDK 17、非 C 盘缓存约束。模拟器模式结束后恢复应用配置、无障碍和旋转设置，结果保存在忽略的 .tools/mvp-acceptance/。进程终止场景会主动结束测试进程，“Process crashed”是预期现象，以 process-recreation.json 的检查结果为准。

真机调试必须显式指定设备，并保持手机解锁：

```powershell
.\scripts\acceptance.ps1 -PhysicalDevice -Serial '设备序列号'
```

真机模式跳过权限开关自动修改、旋转、字体缩放、锁屏和进程终止场景，保留手机已有服务与系统设置。需在手机手动启用点击器无障碍服务，允许安装独立测试目标及测试组件；部分小米系统在测试进程重启后需要关闭再开启服务。电脑模拟触摸还需要小米的“USB 调试（安全设置）”，普通使用点击器无需该调试权限。结果位于忽略的 .tools/physical-acceptance/。重复验收已安装的同一主应用和目标 APK 可加 -SkipAppInstall；修改主应用或目标后应重新安装。

旧优化包多点验收依赖已移除的首页表单，scripts/release-acceptance.ps1 当前在操作设备前退出，待新入口与驱动适配后恢复。历史 20 次真实接收与资源测量保留在 [PERFORMANCE.md](docs/PERFORMANCE.md)，不能视为本版性能结果。

本机默认图形渲染曾造成主线程阻塞和 System UI 无响应，本次验收临时使用 SwiftShader 软件渲染，未修改 AVD 永久配置。遇到系统无响应弹窗应先恢复模拟器，再验收。

## 目录

```text
app/                   Android 首页、资源及后续平台实现
core/                  纯 Kotlin/JVM 任务模型与测试
gradle/                版本目录与 Wrapper
docs/PLAN.md           分阶段计划与进度
docs/VERIFICATION.md   本次验证结果与环境限制
docs/decisions/        技术决策记录
AGENTS.md              开发代理协作规则
.github/workflows/     持续集成配置
```

## 使用

1. 在“自动点击”页完成第一步，点“启用无障碍点击服务”，阅读用途说明，再通过系统设置开启服务；返回后状态应变绿。
2. 切到“时间同步”页签，两列查看京东、淘宝、阿里云、腾讯云和 Cloudflare 的时间；点击卡片选择任务与时钟的参考。括号内为与本机的毫秒差。输入同步间隔后点“保存并同步”，范围 1000～86400000 毫秒；NTP 来源至少间隔 60000 毫秒。失败会明确显示，不自动换源。
3. 回到第二步，点“开启定时点击浮窗”，同时显示时钟和 FPS 准星；重复点击保持浮窗打开，不会启动点击。打开目标页面，将准星拖到目标位置，松手自动保存。
4. 新开浮窗的“开始点击”默认是打开时当前参考时间；点击该时间块，用时、分、秒、毫秒四列滚轮微调，确认只保存。按所选来源计算下一次到达的时刻，未到为今天，已过为明天。关闭再开重新取当前时间，同一浮窗内保留微调结果。
5. 点浮窗播放图标开启一次定时；时间缺失或过期时先弹滚轮，确认后开启，取消不点击。等待期间可拖动准星，截止前约 250 毫秒收起并保存最后可见中心，提前让开输入窗口。停止图标取消任务，右上角 × 停止并关闭浮窗；执行、失败或过期均不自动补点。
6. “开启多点录制浮窗（暂未开放）”当前禁用，本版不提供录制与连点配置界面。

长等待允许同一来源的新鲜校时修正截止，最后 5 秒锁定参考；异常大跳变或缺少新鲜校时会停止，不追补。不修改系统时间；启用及最终准备要求最近 5 分钟内有效校时。来源比较只在时间页可见时运行，切走后只维护选中来源；没有相关页面、时钟或任务时暂停周期同步。来源比较卡片每 100 毫秒刷新；悬浮时钟在实际绘制时按参考取值，最小重绘请求间隔 16 毫秒，在本轮 60 Hz 模拟器通常约 30 帧/秒，实际可能因系统卡顿更慢。使用最后成功样本在本地推进，不按刷新频率请求接口。周期同步依赖进程存活，不使用精确闹钟，不承诺休眠唤醒或 1 毫秒真实点击精度。

更多语义和测试说明见 [功能说明](docs/FEATURES.md)。

定时调度、长期校时策略及实际 DOWN/onClick 延迟分布见 [计时优化记录](docs/TIMING_RESULTS.md)。数据相对应用计划截止，不是绝对 UTC 精度或硬实时 1 ms 保证。

悬浮时钟相对独立数据源的长时验证见 [验证结果](docs/CLOCK_DRIFT_RESULTS.md) 和 [参考生命周期决策](docs/decisions/0011-floating-clock-resynchronization.md)。完成/失败后仍显示的时钟继续跟随最新校时，已结束的点击不会因校时重启。建议维持默认 60000 毫秒同步；断网或间隔过大时，单调计时仍可能存在振荡器漂移，不能只凭毫秒数字判定授时准确。

50 ms 优化已去掉旧绘制缓存，但严格上限本轮未达标：最终单回调版本 181 个独立样本中仍有 49 个估算超限，最大 297 ms，不能保证每次不超过 50 ms。详见 [50 ms 实测与边界](docs/CLOCK_50MS_RESULTS.md)。

后续按“先采集，再分析”完成同一优化包的五组对照及 Perfetto 轨迹/CPU 火焰图分析，定位模拟器图形提交热点、调度等待和目标绘制等待；本轮没有修改生产算法，50 ms 状态保持未通过。详见 [轨迹分析报告](docs/PERFETTO_RESULTS.md)。

剩余证据补采已完成：记录失败检查点与真实接收，恢复目标 CPU 调用栈，核对 RenderThread 唤醒和点击关键区间。14 轮结果及 10 份轨迹均保留；这次完成的是数据分析，严格 50 ms 指标仍未通过。详见 [补采分析报告](docs/PERFETTO_FOLLOWUP_RESULTS.md) 和 [证据索引](docs/acceptance/2026-10-28-perfetto-followup.json)。

后续优化保留了临界区状态轮询减负：可见时每 100 ms 检查，不再在最后五秒加速至 33 ms，点击截止仍独立调度。帧阶段刷新候选因未证明整体收益而撤回；实际点击延迟改善尚未证实，严格 50 ms 仍未达标。详见 [优化实验结果](docs/OPTIMIZATION_RESULTS.md) 与 [ADR 0013](docs/decisions/0013-frame-aligned-refresh.md)。

京东已改为 OPTIONS 请求 https://api.m.jd.com，解析 X-API-Request-Id 末段的毫秒时间戳，取代先前不可用的 JSON 接口；格式或响应日期异常时保留上次成功样本。来源状态与本轮检查结果见 [验收记录](docs/VERIFICATION.md)，请求 ID 的时间语义与精度限制见 [ADR 0007](docs/decisions/0007-jd-options-time.md)。

## 后续计划

1. 完成当前定时浮窗在真机上的权限、实际接收、异常停止与厂商兼容验证；新多点录制浮窗另行规划。
2. 采集真机优化构建的 PSS、有效点击数、间隔分布和长期稳定性基线。
3. 根据实际需求扩展长按、滑动或任务导入导出。

完整计划见 [PLAN](docs/PLAN.md)，当前方案见 [ADR 0002](docs/decisions/0002-android-project.md)，早期跨平台调研见 [ADR 0001](docs/decisions/0001-mobile-architecture.md)。

开发前阅读 [AGENTS.md](AGENTS.md)。SDK、构建产物、本机配置和签名凭据均不进入版本控制。
