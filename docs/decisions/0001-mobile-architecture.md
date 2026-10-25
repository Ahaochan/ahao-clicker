# ADR 0001：移动端自动点击器技术选型

- 日期：2026-10-24
- 状态：历史讨论稿；当前方案已由 [ADR 0002](0002-android-project.md) 替代
- 项目：ahao-clicker

> 2026-10-24：用户决定本阶段不考虑 iOS。以下保留调研过程，不作为当前工程实施要求；以 ADR 0002 和 docs/PLAN.md 为准。

## 1. 已确认需求

1. 开发移动端自动点击器，面向 Android 和 iOS。
2. 优先实现 Android，目前只有 Android 真机可用于验证。
3. 重视执行性能、低内存占用和持续运行稳定性。
4. 保留未来迁移到 iOS 的可能性，避免核心逻辑与 Android 紧密耦合。

当前方案按“跨应用点击、手机独立运行、Android 默认无需 Root”评估。这些属于工作假设，尚待产品讨论确认。

## 2. 平台可行性

### Android

用户启用无障碍服务后，可通过 AccessibilityService.dispatchGesture() 发送点击、长按和滑动。该接口从 API 24 开始提供，需要声明 canPerformGestures。

调用新的 dispatchGesture() 会取消正在进行的手势。因此执行器应处理派发返回值、完成和取消回调、超时及停止，而非不断堆积定时调用。配置间隔不等于目标应用实际收到点击的间隔。

浮动控制条与点位标记可研究 WindowManager 的 TYPE_ACCESSIBILITY_OVERLAY。

### iOS

普通第三方应用受沙箱和公开 API 能力限制。当前调研没有找到与 Android 无障碍服务对等、供普通应用使用的通用跨应用触摸注入接口。因此，不能承诺普通 iOS 应用具有与 Android 版相同的自动点击能力。

未来可能分别研究：

- 普通用户形态：系统切换控制的手势和配方；它们由系统管理，不是本应用可直接替换的通用执行后端。
- 开发者工具形态：XCTest / WebDriverAgent，涉及签名及测试运行环境，不等同于普通手机 App 独立运行。

使用 Kotlin、Swift、Flutter 或其他框架，均不会自动获得系统未提供的权限。iOS 的执行可行性应先于完整 iOS 产品开发验证。

## 3. 当前建议与备选

| 部分 | 当前建议 | 状态或理由 |
| --- | --- | --- |
| Android 语言 | Kotlin | 直接接入系统 API |
| 公共核心 | Kotlin Multiplatform（KMP） | 共享平台无关逻辑 |
| Android 执行 | AccessibilityService + dispatchGesture() | 先验证无需 Root 的路径 |
| Android 浮层 | 原生 View + 无障碍浮层 | 单独管理浮层与触摸区域 |
| 设置与任务管理界面 | Views/XML 或 Compose Multiplatform | 待讨论，见第 4 节 |
| 异步调度 | Kotlin Coroutines，单一可取消执行队列 | 控制并发和资源生命周期 |
| 任务存储 | 带格式版本的 JSON | 先满足轻量任务持久化 |
| 最低 Android 版本 | 建议 API 26；必要时评估 API 24 | 尚未确认，需结合目标设备 |
| iOS 执行后端 | 能力接口占位，后续实证 | 不承诺功能对等 |

Flutter 和 React Native 可以共享界面，但点击执行仍需平台适配；当前没有证据表明其收益优于直接使用 Kotlin。C++ / Rust 暂不引入，待性能测量证明存在值得优化的计算瓶颈后再评估。

以上是架构取舍，尚无本项目内存或速度测试结果，不构成不同框架的性能排名。

## 4. Kotlin 迁移到 iOS 是否需要重写

Kotlin Multiplatform 可将公共 Kotlin 代码编译到 iOS 使用，不需要把业务逻辑翻译成 Swift。普通 Android Kotlin 工程中依赖 Android SDK 或 JVM 专属库的代码，不会因此自动成为跨平台代码。

有两种主要界面路线：

| 路线 | 可复用内容 | iOS 新增工作 | 取舍 |
| --- | --- | --- | --- |
| KMP 核心 + Android Views + iOS SwiftUI | 任务模型、校验、状态机、任务解释等平台无关逻辑 | 用 SwiftUI 实现 iOS 界面，适配平台能力与工程 | 保留各平台原生 UI，但维护两套界面 |
| KMP 核心 + Compose Multiplatform | 公共逻辑及适合共享的设置、任务编辑界面 | iOS 工程入口、签名、生命周期及平台能力适配 | 减少界面重复开发，需测量实际资源成本 |

即便采用 Compose Multiplatform，也不能自动复用 Android 的 AccessibilityService、WindowManager 浮层和权限流程。Android 无障碍浮层应保持独立，不能要求 iOS 提供同等实现。

KMP 允许 Kotlin 调用 iOS 平台 API，因此平台适配也不必一律改用 Swift；具体使用 Kotlin/Native、Swift 或 Objective-C，应按互操作和维护成本决定。iOS 工程仍需要平台配置，标准构建与验证需要 macOS 和 Xcode。

上一轮倾向 Views/XML，是基于简单界面和低占用目标的工程建议，尚未测量验证。若更看重减少未来 iOS 界面重复开发，应将 Compose Multiplatform 作为重点候选，不能把 Views 记为最终决定。

## 5. 模块边界

建议先分为：

- shared-core：任务定义、循环规则、运行状态机、参数校验、任务解释、坐标模型及平台接口。
- android-app：设置页面、任务管理、权限引导和生命周期集成。
- android-runtime：无障碍服务、手势执行、浮层和屏幕坐标转换。
- iOS 适配模块：执行可行性验证后再实现。

公共核心不得引用 Android Context、Service、PointF 或其他平台专属类型。通过 GestureExecutor、Clock、TaskStore 等接口连接平台实现；调度所依赖的时间与等待能力也应隔离。

执行后端显式声明支持的能力，对不支持的动作返回明确结果。任务格式记录版本、参考屏幕尺寸、方向和坐标区域；跨平台或设备迁移时仍可能需要重新校准点位。

## 6. 性能验证计划

先制作最小 Android 原型：浮动开始/停止按钮、单点连点、多点顺序点击、本地点击计数测试页面。

在真机上使用优化构建，分别测量主界面、浮层待机、连续点击状态：

- 进程 PSS 内存、CPU 使用情况。
- 目标测试页面实际接收点击数，区别于派发次数。
- 点击间隔分布、取消和失败情况、停止响应时间。
- 连续运行 30 分钟后的内存趋势和稳定性。

执行循环应可取消、避免忙等待、限制日志容量，并使点击执行与界面刷新解耦。第一版先验证坐标点击；截图、找图和 OCR 按实际需求另行评估资源成本。

当前没有设定未经测试的内存上限、极限点击频率或硬实时保证。

## 7. 待讨论事项

1. 是否要求手机独立运行、是否始终不依赖 Root 或电脑？
2. iOS 的产品目标是普通用户 App，还是允许开发者测试环境的工具？
3. UI 更重视低占用还是未来共享？对比 Views/XML 与 Compose Multiplatform。
4. 第一版需要哪些动作：单点、多点、长按、滑动、条件触发？
5. 最低 Android 版本，以及内存、点击频率、停止响应的验收目标。
6. 是否计划上架 Google Play？若上架，需要按无障碍 API 当前政策设计用途申报、披露和同意流程。

## 8. 官方资料

资料调研日期：2026-10-24。平台与分发政策可能变化，落地时复核。

- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- [Kotlin Multiplatform](https://kotlinlang.org/multiplatform/)
- [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/)
- [KMP 开发环境](https://kotlinlang.org/docs/multiplatform/quickstart.html)
- [Apple 运行时安全与沙箱](https://support.apple.com/guide/security/security-of-runtime-process-sec15bfe098e/web)
- [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/#software-requirements)
- [Apple 切换控制](https://support.apple.com/en-ie/guide/iphone/iph400b2f114/ios)
- [Appium XCUITest 环境要求](https://github.com/appium/appium-xcuitest-driver/blob/master/docs/getting-started/system-requirements.md)
- [Flutter 架构](https://docs.flutter.dev/resources/architectural-overview)
- [React Native 原生模块](https://reactnative.dev/docs/turbo-native-modules-introduction)
- [Android 内存测量工具](https://developer.android.com/topic/performance/memory/guide/tools-overview)
- [Google Play 无障碍 API 政策](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
