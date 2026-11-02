# ADR 0020：每日测试APK构建与最新下载入口

- 日期：2026-11-02。
- 状态：工作流已编写；本地校验见PLAN，GitHub实际运行待默认分支推送后确认，无需配置签名Secret。
- 用户确认默认北京时间每天08:17执行，使用固定测试签名；随后明确要求免配置默认值，密码为`android`。

## 决定

1. 新增`.github/workflows/daily-apk.yml`，仅`schedule`及`workflow_dispatch`触发，构建任务限制默认分支。UTC表达式`17 0 * * *`对应北京时间08:17，避开整点；原有提交/PR工作流继续负责代码检查。
2. 使用Ubuntu、JDK17、Android SDK36及项目Wrapper，沿用标准单测、Lint、Debug/Release检查。GitHub运行环境使用官方依赖仓库，Gradle缓存显式放在工作区；不会触及本机D/E盘缓存或C盘。
3. 每日下载产物选择已签名Debug APK。没有Secret时，从`.github/signing/default-debug.keystore.b64`读取一次生成后固定保存的仓库公开测试密钥；别名`androiddebugkey`，密钥库和密钥密码均为`android`。该公用测试密钥由用户明确授权入库，不来自本机现有签名，不用于正式Release；后续不能自动重新生成或替换。可选的`ANDROID_DEBUG_KEYSTORE_BASE64`优先覆盖默认值，但提供了坏值时失败，不静默回退。私有签名仍不入库、不上传产物、不输出日志，本轮没有读取或上传用户已有私钥，也不改远程Secrets。
4. 签名放在runner临时的`ANDROID_USER_HOME`，Gradle签名报告已确认本项目遵循该目录。构建后验证APK签名、生成固定名称`Ahao-clicker-latest.apk`及SHA-256，再写入`build-info.json`记录源码提交、UTC时间与运行链接。现有包名、应用版本号及算法保持。
5. 构建任务仅`contents: read`；依赖构建成功的独立发布任务仅获得`contents: write`，从同一次运行下载产物、复核校验和，通过GitHub CLI创建`daily-<run_id>-<run_attempt>`独立Release并标记为Latest。固定入口是`releases/latest/download/Ahao-clicker-latest.apk`，不用移动同一个标签或覆盖旧资产，也兼容仓库的不可变Release策略。
6. 每日Release保留，Actions产物及报告保留14天。固定并发组串行发布，工作流设置超时，失败不更新Latest，签名文件在清理步骤移除。Latest在此项目中表示每日测试构建，并非正式稳定版。

## 验证与限制

本地静态检查YAML、Actions表达式、权限和Shell语法，离线运行签名还原、APK文件打包/元数据与模拟发布的成功/失败流程；真实APK另用SDK验证签名。模拟`gh`不会创建远程标签或Release，本地通过不等于GitHub首次运行成功。

默认公开测试签名不保证与手机现有安装一致，首次可能签名冲突；不自动卸载或清空数据。定时任务要求工作流已位于默认分支；GitHub负载可能使任务延迟或丢弃，公开仓库60天无活动可能停用。实际首次发布需推送后手动运行确认，不再要求配置Secret；提交、推送和远程操作遵循用户另行授权。异常情况下可能留下GitHub CLI创建的未发布草稿，排查后处理，不自动删除既有发布。

## 依据

- [GitHub schedule触发规则](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)。
- [GitHub CLI创建Release及Latest行为](https://cli.github.com/manual/gh_release_create)。
- [GitHub不可变Release规则](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)。
- [Android用户目录环境变量](https://developer.android.com/tools/variables)。
