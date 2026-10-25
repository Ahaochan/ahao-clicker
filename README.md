# ahao-clicker · 阿浩点击器

面向 Android 的轻量原生自动点击器，优先关注低占用、稳定执行和明确的停止控制。本阶段不开发 iOS。

## 当前状态

最小 MVP 已完成模拟器功能验收：外部时间同步、时间窗内限次连点、多点真实点击录制和逐点间隔回放。20 个单元测试、9 组设备功能场景以及进程终止后不恢复旧任务验证通过，Lint 和 Debug/Release 构建通过。实际点击由独立测试应用接收核实。真机性能与厂商兼容性仍待验证，详见 [验证记录](docs/VERIFICATION.md)。

## 技术方案

- Kotlin + 原生 Views/XML + View Binding。
- app 负责 Android 界面与后续系统集成；core 负责平台无关的任务规则。
- 通过 AccessibilityService 执行用户明确启动的任务；录制时也会实际点击。
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

验收脚本仅接受模拟器，使用独立点击计数 APK 和本机 HTTPS 服务，不访问真实账户或公共时间源。先完成普通构建以准备离线依赖；脚本保持 JDK 17、非 C 盘缓存约束，结束后恢复应用配置、无障碍和旋转设置。结果保存在忽略的 .tools/mvp-acceptance/。进程终止场景会主动结束测试进程，“Process crashed”是预期现象，以 process-recreation.json 的检查结果为准。

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

1. 在首页阅读用途说明，并通过系统设置启用无障碍点击服务。
2. 配置 HTTPS 时间源、HTTP Date / JSON 毫秒 / JSON 秒格式、JSON 字段路径和同步间隔；可立即同步或开启周期同步。HTTP Date 仅有秒级精度。
3. 单点连点填写一行步骤；多点填写多行，每行格式为 x,y,点击前等待毫秒。次数为总点击次数，不是循环次数。
4. 录制前准备目标应用页面，再返回点击器启动录制。每次点按后等待“正在执行点击”提示消失、录制层恢复，再录下一点；派发期间的触摸不会被采集，不支持快速连续触摸的无损录制。用浮动“结束录制”或“停止”结束，返回首页编辑间隔。
5. 设置开始、结束本地日期时间，或点击“3 秒后开始”填充时间窗。启动后浮动停止按钮始终保留，次数或截止时刻先到即停止。

执行时冻结本次时间参考，不修改系统时间。没有成功同步时页面明确显示使用设备时间。周期同步依赖应用进程存活，不使用精确闹钟，也不承诺休眠时毫秒调度。浮动控制按钮区域不支持录点；当前只录制点按，不录制滑动和长按。

更多语义和测试说明见 [功能说明](docs/FEATURES.md)。

## 后续计划

1. 完成 Android 真机上的权限、录制、回放、异常停止与厂商兼容验证。
2. 采集真机优化构建的 PSS、有效点击数、间隔分布和长期稳定性基线。
3. 根据实际需求扩展长按、滑动或任务导入导出。

完整计划见 [PLAN](docs/PLAN.md)，当前方案见 [ADR 0002](docs/decisions/0002-android-project.md)，早期跨平台调研见 [ADR 0001](docs/decisions/0001-mobile-architecture.md)。

开发前阅读 [AGENTS.md](AGENTS.md)。SDK、构建产物、本机配置和签名凭据均不进入版本控制。
