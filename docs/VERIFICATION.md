# 工程与功能验证记录

日期：2026-10-24。

## 最终结果

最小 MVP 的 9 组模拟器功能场景、独立进程终止检查均通过。正式项目 Gradle 检查通过：20 个单元测试，0 失败、0 错误；Android Lint 0 错误、0 警告、5 个版本升级提示；Debug 与启用 R8/资源压缩的 Release 均构建成功。真机性能和兼容性仍待验证。

| 项目 | 结果 |
| --- | --- |
| 核心测试 | 17 个，覆盖时间参考、限时限次、多点间隔、拒绝/取消/超时、陈旧回调、录制完成状态、输入边界 |
| Android JVM 测试 | 3 个，覆盖 HTTP Date、JSON 路径、秒/毫秒、溢出和异常响应 |
| Android Lint | 通过；修复主题 API 兼容性、备份规则、图标、背景重复绘制及资源目录问题 |
| Debug APK | app/build/outputs/apk/debug/app-debug.apk，2698870 字节 |
| Release APK | app/build/outputs/apk/release/app-release-unsigned.apk，135310 字节，未配置发布签名 |
| JDK / 构建工具 | JDK 17.0.12、Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.10 |
| Gradle 缓存 | D:\JetBrains\.gradle；构建入口拒绝 C 盘缓存的测试已通过 |
| SDK | D 盘共享 Android SDK，Platform 36、Build Tools 36.0.0；本机路径配置不提交 |
| 国内镜像 | 阿里云、华为云都成功读取此前失败的 profileinstaller POM；工程默认阿里云优先，官方仓库后备 |
| 静态检查 | XML、TOML、YAML、PowerShell、文档链接及 git diff --check 均检查 |

APK 文件大小是包体大小，不等于运行内存。Release 未签名，不能作为可直接安装的发布包。

版本检查提示保留为 Hint，SDK 与工具链升级按兼容表单独评估。OldTargetApi 升级建议保留为 Hint；NewApi 等实际 API 使用检查、资源与业务正确性检查保持启用，没有建立 Lint 错误基线。

## IDE 环境修正与复验

- gradle/gradle-daemon-jvm.properties 固定为 Java 17，删除 IDE 生成的 Java 25 下载地址；Wrapper 输出与实际守护进程日志均确认使用本机 JDK 17.0.12。
- 移除仅适用于 Gradle 9.4+ 的并行模型提取属性。忽略的 IDEA 本机配置改为项目 Wrapper 与 JDK 17，移除旧全局 Gradle 7.2 路径。
- 保留当前 D 盘共享 SDK，将此前已安装的官方 Platform 36 复制补齐，并对 android.jar 做 SHA-256 一致性校验。SDK、本机设置和缓存不纳入版本控制。
- D 盘 SDK 预装 API 37，首次复验触发 OldTargetApi 升级建议；保持已选 API 36，将该建议保留为可见 Hint 后，scripts/build.ps1 -Action check 完整通过，101 项任务中 12 项执行、89 项为最新。
- 随后使用 :core:test --rerun :app:testDebugUnitTest --rerun 强制重新执行 20 个单元测试，0 失败、0 错误；不是仅沿用先前测试结果。
- IDEA 2026.2.3 的 MCP 没有单独的 Gradle 同步工具，但可通过 run_inspection_kts 调用 IDE 公开的 Android.SyncProject 动作，并显式指定本项目上下文。首次请求因旧同步仍在运行而被拒绝；旧同步自然结束后，于 19:04:40 成功触发新一轮 RESOLVE_PROJECT:1，同步约 7 秒完成，日志同时记录 onSuccess 与 onImportFinished。
- 同步后，MCP 已返回 app/core 及 main/unitTest/androidTest 等源码模块，并返回 Android app 运行配置，IDE 导入验证通过。临时检查脚本与连接客户端均放在忽略的 .tools/ 中，不作为项目构建依赖。
- 本次未暂存、提交或推送文件。

## TDD 过程

先编写行为测试，再运行 RED，之后实现 GREEN。分别记录了以下实现前失败：TimeSourceConfig/SyncedTime/ClickRunner/PointRecorder、StepText、未完成录制点过滤、时间响应解析、来源切换重置。最终全部通过正式项目测试。

早期环境未就绪时使用独立编译器验证核心与解析器；SDK 与国内镜像就绪后，已以固定版本项目 Gradle 的测试与完整 Android 编译替代该临时结果。开发中间文件位于忽略的 .tools/，不构成正式构建依赖。

## 构建验证

在 Gradle 缓存明确指向 D 盘的条件下执行：

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -Pandroid.builder.sdkDownload=false --offline --no-watch-fs
```

此前目录移动引起资源缓存不一致，已关闭配置/构建缓存做全量编译验证，再移除遗留空资源目录，最终完整检查通过。SDK 自动下载关闭参数只用于本机已备齐编译 SDK 时绕开清单服务，不作为应用运行配置。

直接访问 Google Maven 曾发生握手失败和超时。用户授权国内镜像后，默认使用阿里云 Google/Central/Gradle Plugin 镜像并保留官方回退；可通过 -PuseChinaMirrors=false 关闭。固定版本和 Wrapper SHA-256 未变化，未关闭证书校验。临时本机下载转接已停止，不是工程所需服务。

早期 E 盘编译 SDK 未安装 Platform Tools；当前已改用 D 盘共享 SDK，其中已有 Platform Tools。模拟器安装验证已执行，真机安装尚未执行。

## IDEA MCP 模拟器启动验证

- 通过 execute_run_configuration 运行现有 Android app 配置，IDEA 完成 Gradle 构建，自动启动 Medium_Phone_API_37.0（API 37、x86_64、1080×2400）。没有启动 Android Studio。
- 首次冷启动约 118 秒，超过临时 MCP 客户端原先的 55 秒等待时间；客户端超时后 IDE 后台仍继续启动、安装和运行。已延长忽略的诊断客户端等待时间，未将超时当作运行取消或重复发起部署。
- 启动时设备暂时 offline，发现后台 ADB 来自旧版 33。确认连接列表只有目标模拟器后重启临时 ADB 服务，统一到 SDK 中的 37.0.1，随后 emulator-5554 在线并确认 boot_completed=1。
- IDEA 日志确认完整安装 Debug APK 并启动 com.ahao.clicker.MainActivity。ADB 确认包已安装、应用进程存在、MainActivity 为前台 Activity；检查时 AndroidRuntime 与 crash 日志未见应用崩溃。
- 截图检查首页与滚动后的任务表单，控件和文本可见，未改动默认配置。证据保存在忽略的 .tools/emulator-first-launch.png、.tools/emulator-task-form.png。
- 本轮没有启用无障碍服务、执行录制或派发点击，也未配置外部时间源；不能据此宣称完整 MVP 验收或性能验证通过。模拟器保留运行，便于继续验证。

## 最小 MVP 功能验收（2026-10-24）

环境为 API 37 / Android 17、x86_64、1080×2400 的本地模拟器；没有启动 Android Studio。本轮通过 SDK 的 ADB 和系统 Instrumentation 完成验收，应用点按以独立接收进程的真实 onClick 和单调时间记录核对。

| 项目 | 结果与证据 |
| --- | --- |
| 用途披露与未授权启动 | 取消披露、设置导航意图、未启用服务时不点击通过；测试开启服务使用模拟器设置，不等于人工操作各厂商设置页 |
| 本机 HTTPS 时间源 | Date、JSON 秒/毫秒、嵌套字段、立即与周期同步、暂停、503 后恢复通过；无效 JSON、超限响应、Age 和过慢响应不覆盖旧样本 |
| 单点与截止 | 延迟开始后实际接收 5 次 A；100 次任务在 900 毫秒窗口内实际接收 4 次后停止 |
| 录制与多点回放 | 录制 A→B→A 真实触发；编辑间隔后实际收到 A→B→A→A→B→A，5 个接收间隔为 516、276、217、474、263 毫秒，配置等待为 450、200、150、450、200 毫秒，另外包含手势与系统延迟 |
| 录制重复与取消 | 12 次交替逐点录制均被目标收到；等待采集恢复后再操作；录制中停止，延后回调不重新打开采集层 |
| 停止与中断 | 等待时停止、运行时停止、旋转、熄屏、无障碍断开通过；重连不恢复旧任务 |
| 进程终止 | 实际收到 2 次点击后主动终止进程；PID 5907→6058，重建后观察 3 秒仍为 2 次，不恢复点击 |
| 构建与静态检查 | scripts/build.ps1 -Action check 通过；20 个单元测试，Lint 0 错误、0 警告、5 提示；Debug 和 R8/资源压缩 Release 构建成功 |

9 组场景全通过，另有独立进程终止检查。原始结构化证据保留在 [acceptance/2026-10-24.json](acceptance/2026-10-24.json)。可用 README 中的 acceptance.ps1 命令重现；该入口只允许模拟器，恢复配置和系统设置，不提交本机证书或日志。进程终止检查中的 “Process crashed” 是主动终止的预期输出，必须同时通过新 PID 和独立计数器不增长检查。

验收新增的计数 APK 仅在 -Pacceptance=true 时加入构建，测试框架使用系统 Instrumentation，不新增生产依赖。本机 HTTPS 监听回环地址，通过本轮反向端口访问；测试进程只信任当轮生成的证书，正常证书及主机名校验保持启用，生产应用没有加入测试 CA。

### 问题修复与验证边界

- RED：先观察到保存了录点但目标未收到触摸，之后才调整实现。采集窗口改为保留输入通道，临时透明且不可触摸；确认输入事件后再切换，增加有限缓冲，并用代次标识丢弃停止后的旧回调。逐点录制与重复录制验收通过。
- 浮动控件固定宽度和状态文本行数，避免进度改变按钮位置；验收仅在无障碍浮层定位按钮，排除退场中的首页同名按钮。
- 默认模拟器图形模式曾在 HardwareRenderer 绘制调用阻塞，引起输入延迟和 ANR；冷启动还出现 System UI 无响应弹窗。临时以 -gpu swiftshader -no-snapshot-load -no-snapshot-save 启动并恢复系统焦点后，完成最终验收；没有修改 AVD 永久配置。硬件渲染下的失败记录没有当作最终通过证据。
- 录制窗口有 200 毫秒派发前缓冲及 500 毫秒恢复缓冲，另有窗口帧更新时间。提示恢复后再录下一点；暂停采集期间的快速触摸不保证录入。回放没有这些录制缓冲，按编辑的等待间隔调度。

### 优化 APK 的短时资源观察

对未签名 Release 的本地副本使用标准调试证书签名，仅在模拟器安装，确认安装包无 DEBUGGABLE 标记。空闲首页每 3 秒采样一次，共 18 秒，PSS 为 23975、24571、24079、24119、24135、24175 KiB（约 23.4–24.0 MiB）。完成后已恢复 Debug 安装；正式 Release 产物仍未签名。

这只是短时、空闲、软件渲染模拟器观察，不是点击负载、泄漏、吞吐、功耗或真机性能结论。没有据此设定内存预算。

## 尚待真机验收

- 没有在用户手机上启用服务、录制真实页面或执行自动点击，未操作实际账户。
- 首次权限流程、实际点击录制、各点间隔、时间窗起止、停止、旋转、熄屏、服务断开需要真机验证。
- 尚未测量真机优化构建的有效接收点击数、PSS、CPU、点击抖动、停止延迟或 30 分钟运行趋势。
- 时间同步已通过本机 HTTPS 服务验收，没有对用户指定的公共时间源作在线验证；当前没有默认外部源自动访问。
- CI 配置已更新，但尚未推送或验证 GitHub 托管运行。

不把编译与单元测试通过等同于真机性能或目标应用业务操作成功。
