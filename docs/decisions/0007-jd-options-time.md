# ADR 0007：京东 OPTIONS 请求 ID 时间来源

- 日期：2026-10-26。
- 状态：已实现；41 个单测、20 组模拟器场景、Lint 与 Debug/Release 构建通过。真实京东卡片同步成功，本次 RTT 为 44 ms。
- 取代 ADR 0006 的京东旧 JSON 来源，其他来源和任务冻结时间规则不变。

## 来源与证据

用户提供 `curl -X OPTIONS https://api.m.jd.com` 与响应头 `X-API-Request-Id: 10105444978-107668-1792980614483`。该尾段换算为北京时间 2026-10-26 10:10:14.483。独立四次请求均 HTTP 200，各尾段递增且与 HTTP Date 的秒数一致，支持“毫秒 Unix 时间戳”的实测推断。没有找到其授时精度或长期格式的官方承诺，不将其等同于业务服务器的绝对时间。

## 实现

- 固定 HTTPS 根地址，发送无正文 OPTIONS，不再调用 queryMaterialProducts。正常 TLS/主机名校验、不跟随重定向、不缓存，不传用户凭据。
- 从响应头读取三段数字请求 ID，前两段分别限制 1～32 位，尾段严格为 13 位毫秒数；整体长度有上限。HTTP Date 必须有效，二者相差超过 2000 ms 则拒绝。此阈值是异常校验，不是精度保证。
- 不读 JSON 响应体，空 204 也能采纳有效头；没有该头、格式变化、异常日期、Age 非零、非 2xx 或请求往返超过 2 秒时拒绝并保留同源样本。不回退为 Date、本机时间或其他供应商时间。
- 复用已有有界工作线程、限频、连接取消和代次校验。默认 60000 ms，最低请求间隔 1000 ms。Android 的连接准备完成后记录发送基点，解析之前记录接收时刻，继续使用单调时钟的往返中点估计，排除建连耗时的主要偏差。
- Android 平台实现的 connect/sendRequest 与读取响应阶段划分参考 [AOSP HttpURLConnectionImpl](https://android.googlesource.com/platform/external/okhttp/+/master/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java)、[AOSP HttpEngine](https://android.googlesource.com/platform/external/okhttp/+/71b9f47/okhttp/src/main/java/com/squareup/okhttp/internal/http/HttpEngine.java)。本轮在 API 37 验证，不把源码分析当成 API 26～36 全部实测。

## 验证

先增加配置映射、完整格式、秒/毫秒误用、符号/小数/多段、长度、缺头、日期缺失及日期不一致的失败测试，再实现。HTTPS 测试服务只对 OPTIONS 返回有效请求头和空 204，覆盖周期同步、六种失败、恢复与取消后的旧响应丢弃。对照独立服务端时钟校验结果，不假设模拟器本机时钟准确。结果见 [VERIFICATION.md](../VERIFICATION.md)。
