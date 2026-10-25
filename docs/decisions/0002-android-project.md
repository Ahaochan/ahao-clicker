# ADR 0002：Android 原生工程初始化

- 日期：2026-10-24
- 状态：当前实施基线；后续根据需求与实测调整
- 替代：ADR 0001 的跨平台工程建议

## 背景

用户明确本阶段不考虑 iOS，并要求先制定计划，再按最佳实践初始化工程。当前优先低内存、执行稳定和可维护性，不引入尚无验证条件的跨平台构建。

## 决策

1. 使用 Kotlin 原生 Android，Views/XML + View Binding 构建简单界面。
2. 建立 app 和 core 两个模块。core 使用纯 Kotlin/JVM，便于快速测试任务规则；Android 系统集成留在 app。暂不拆出空的 runtime 模块。
3. 不引入 KMP、Compose、DI 框架、数据库或常驻后台服务；有实际需求和测量依据时再增加。
4. 暂定 minSdk 26、compileSdk/targetSdk 36，包名 com.ahao.clicker。
5. 构建基线为 JDK 17、Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.10。选用已发布且兼容关系明确的组合，不追逐最新版本；升级时重新验证。
6. Android 模块使用 AGP 内置 Kotlin，core 应用同版本 Kotlin/JVM 插件。版本统一在 libs.versions.toml 中维护。
7. 固定 Wrapper 分发包校验和，禁止动态依赖版本。用户随后授权使用国内镜像：默认优先阿里云 Google/Central/Gradle Plugin 镜像，保留官方仓库后备，可用 -PuseChinaMirrors=false 关闭。
8. Release 启用 R8 与资源压缩，发布签名后续接入；初始化不创建或提交签名凭据。
9. 初始化不声明无障碍服务或其他敏感能力，首页明确标注当前功能阶段。无障碍和浮层在 M1 实现。
10. 用户明确要求 Gradle 依赖下载目录不得位于 C 盘。保留已有 D 盘 GRADLE_USER_HOME；Windows 构建脚本校验路径，未设置时采用项目内 .gradle-user-home，禁止提交该缓存。
11. 使用 gradle/gradle-daemon-jvm.properties 固定守护进程为 Java 17，移除 IDE 自动生成的 Java 25 下载地址；JDK 17 由开发者或 CI 预先安装，不写入本机绝对路径。
12. compileSdk/targetSdk 仍保持 API 36。新版 SDK 触发的 OldTargetApi 升级建议保留为 Lint Hint；NewApi 等实际兼容性检查与 warningsAsErrors 保持启用，升级 SDK 前先验证行为变化。

## 取舍

- 当前不共享跨平台界面，未来如重新考虑 iOS，需要重新评估执行可行性与 UI 路线。
- 纯 Kotlin core 用于隔离任务规则，不承诺直接迁移到任意平台。
- Views/XML 的资源占用收益尚未真机测量，不能声称优于其他 UI 框架。
- 先建立有限单点任务参数及有效性校验，不提前实现调度、持久化或执行器。
- repeatCount 为有限正整数；intervalMillis 表示上一手势完成后等待的时间，不是固定起始间隔。屏幕边界、旋转和平台手势时长限制由平台层后续校验。

## 验证

本地检查与环境情况见 [工程验证记录](../VERIFICATION.md)。CI 执行核心测试、Android 单元测试、Lint、Debug 与 Release 构建。CI 文件存在不等于已在远程执行。

## 依据

- [AGP 9.1.1 兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)：Gradle 9.3.1、JDK 17、KGP 2.2.10。
- [AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)。
- [Android 模块化指南](https://developer.android.com/topic/modularization)。
- [Android 架构建议](https://developer.android.com/topic/architecture/recommendations)。

官方指南按当前项目规模取舍，不为满足形式预建无功能的层次。
