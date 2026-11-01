# 浮窗诊断采集指南

用于浮窗消失、服务中断或显示变化的现场留存。只采集`AhaoOverlayDiag`标签的事件，不执行点击或自动安装应用。源码中的日志默认关闭，工具临时开启并在结束时恢复原值。

## 操作流程

1. 使用包含`OverlayDiagnostics`的当前Debug构建。确认构建版本和目标设备；不能仅凭版本号相同认定安装包相同。准备Python 3.10+和ADB，手机已连接并授权USB调试。
2. 在仓库根目录运行`adb devices -l`，将下面的`SERIAL`替换为状态为`device`的目标序列号。工具依次从`--adb`、PATH、SDK环境变量、本机`local.properties`查找ADB。

   ```powershell
   python -B tools/profiling/overlay_diagnostics.py --serial SERIAL
   ```

   ADB不在默认位置时可指定路径；需要短时采集时可缩小预算：

   ```powershell
   python -B tools/profiling/overlay_diagnostics.py --serial SERIAL --adb D:\Android\Sdk\platform-tools\adb.exe --seconds 300 --max-lines 1024
   ```

3. 等待工具显示`READY`和本次输出目录。这表示本次唯一探针已写入文件；未出现就先处理采集错误，不请用户反复复现。探针验证采集链路，不能替代确认应用版本或系统回调实际送达。
4. 用户按原步骤复现一次，记下发生时间、任务是否已播放、无障碍状态；浮窗消失后先不要重开服务或清数据。需要时同时保留系统窗口与服务快照，与日志中的单调时间、PID和窗口身份交叉核对。
5. 复现后在采集终端按Ctrl+C。工具停止自己的ADB日志进程，恢复原日志级别，并写入本次`summary.json`。核对`probeSeen=true`、`logLevelRestored=true`及停止原因，再分析现场。

工具不清空系统日志。没有事件可能是采集/链路问题，不能直接解释成没有触发问题。成功、失败与复测使用不同目录，保留第一次失败，不覆盖原始记录。

## 输出与上限

每次创建`.tools/overlay-diagnostics/<UTC时间>-<唯一标识>/`，该目录已被Git忽略。

| 文件或字段 | 用途 |
| --- | --- |
| `events.log`、`.1`、`.2` | UTF-8事件，单文件最多1MiB，最多3份；逐行落盘，按字节计算轮转，超长行截断并保留有效UTF-8。 |
| `summary.json` | 开启日志前先持久化本次标识、预算和原日志级别，探针确认与结束时原子更新；包含UTC起止时间、行数、停止原因和还原结果。 |
| `reason` | `starting`、`capturing`表示尚未收尾；`user_stopped`、`duration_limit`、`line_limit`为正常结束；`transport_closed`、`probe_missing`、`capture_error`需保留日志并检查链路。 |
| `probeSeen` | 必须匹配本次标识，旧探针不算确认。 |
| `logLevelRestored` | 是否成功恢复本次开启前的日志属性。 |

默认上限为1200秒、4096行；参数只允许缩小预算。达到任一上限即结束；采集队列也有界，日志状态与调用栈仅在诊断开启时求值。该工具用于功能诊断，其运行条件不能直接当作无打点的性能基线。

## 异常恢复与交付

如果手机断开或进程被强制终止，先保留现场；正常清理以`summary.json`的还原结果为准。原值在开启诊断前已经保存，即使进程未收尾，也可从文件中读取`previousLogLevel`。还原失败时，重新连接同一设备，用该原值恢复属性：

```powershell
# 示例：原值为INFO。
adb -s SERIAL shell setprop log.tag.AhaoOverlayDiag INFO
# 原值为空时，在PowerShell中向设备传递空字符串。
adb -s SERIAL shell setprop log.tag.AhaoOverlayDiag '""'
```

如果目录尚未生成`summary.json`，工具还未开启该标签。若文件被删除或此前曾手动改过级别，无法确认原值时可设置`INFO`关闭本标签的DEBUG日志，并在报告中标明“原值未知，关闭诊断”，不能标成“已还原原值”。

将结论、方法和脱敏证据索引写入`docs/`，原始日志、截图、设备标识等留在忽略目录。报告说明已验证和未验证的范围；回归真正断开/锁屏/旋转等停止保护，不能为了保留浮窗而取消安全停止。

小米系统录屏案例的调用链与修复前后记录见[诊断报告](RECORDER_OVERLAY_DIAGNOSIS.md)。工具离线检查：

```powershell
python -B -m unittest discover -s tools/profiling -p test_overlay_diagnostics.py
```
