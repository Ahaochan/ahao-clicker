# 项目协作规则

## 范围

- 当前只开发 Android，不引入 iOS、KMP 或其他跨平台工程。
- 目标是低占用、稳定的原生自动点击器。先阅读 README.md、docs/PLAN.md 和当前 ADR。
- 用户的新指示优先；架构、依赖、构建方式变化时同步更新文档与计划。
- 默认使用中文沟通和写文档，代码标识符使用英文。

## 架构与代码

- app 负责 Android 界面、系统集成和生命周期；core 是纯 Kotlin/JVM，不依赖 Android SDK。
- 使用 Kotlin、Gradle Kotlin DSL、版本目录和仓库内 Wrapper。AGP 9 使用内置 Kotlin，不重复应用 kotlin-android 插件。
- 用户已授权使用国内镜像：默认优先阿里云 Google/Central/Gradle Plugin 镜像，保留官方仓库后备；可用 -PuseChinaMirrors=false 关闭。镜像调整须实测，不使用 HTTP 或关闭证书验证。
- 只为实际功能增加依赖；不提前引入 DI 框架、数据库、脚本运行时或多层空模块。
- 页面文案使用字符串资源，尺寸和颜色使用资源；适配系统栏、字体缩放和横竖屏。
- 业务状态明确建模；避免静态 Activity 引用、无界日志、轮询忙等待和无法取消的任务。

## 自动点击约束

- 只在用户明确启动任务后执行点击；必须保留可用的停止路径。
- 无障碍服务断开、任务取消或异常时停止调度；不自动恢复点击任务。
- dispatchGesture 的派发成功不等于目标接收成功。处理完成、取消、拒绝和超时，避免重叠派发。
- 坐标模型、重复规则等放在 core；无障碍、浮层和屏幕坐标转换留在 app 的平台实现中。
- 权限按需申请，不能为初始化空壳提前申请截图、联网、浮窗等无关权限。

## 验证与交付

- 首选检查：./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease。
- Windows 使用 .\gradlew.bat；始终使用 JDK 17 和项目 Wrapper，不依赖全局 Gradle。
- Gradle 依赖与 Wrapper 分发缓存禁止放在 C 盘。运行前检查 GRADLE_USER_HOME；保留已有 D/E 盘配置。Windows 优先使用 scripts/build.ps1，它会拒绝 C 盘缓存，变量未设置时默认项目内 .gradle-user-home。不要通过 -g 或系统属性绕过该规则，不改动其他项目缓存。
- 为参数边界、状态转换、调度取消与故障写有意义的测试；简单资源调整不添加镜像测试。
- 如无 SDK、依赖下载受阻或无真机，明确区分已通过和未执行，不能宣称构建或性能已验证。
- 内存以进程 PSS 为主要观察项；性能用优化构建与真机有效接收点击数验证，不以派发计数替代。
- 修改完成后运行 git diff --check，交付说明变化、验证结果和剩余限制。
- 不提交 SDK、本机路径、构建产物、密钥、签名文件。提交或推送遵循用户明确要求。
