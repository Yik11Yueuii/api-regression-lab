# Web UI 使用

v0.3.0 起提供本地 Web 页面，通过浏览器完成单接口可视化调试与批量回归。页面运行在 `http://127.0.0.1:8765/`，与 CLI 共用同一套请求与断言逻辑，不是另一套测试引擎。

## 启动

Windows 双击仓库根目录的 `start-web.bat`：

1. 依次检查有效 `JAVA_HOME`、`%USERPROFILE%\.jdks\openjdk-21.0.2` 以及 PATH 上的 `java`，只接受 Java 21。
2. 检查 `target\api-regression-lab-0.3.0.jar` 是否存在，缺失时提示先执行 `mvn clean package`。
3. 启动服务并自动打开默认浏览器。

也可以手动启动：

```bash
java -jar target/api-regression-lab-0.3.0.jar --web
```

启动后保持窗口开启，`Ctrl+C` 停止。启动失败（未找到 Java 21、JAR 缺失、端口占用等）会打印明确错误，不会静默退出。

## 界面概览

顶部切换「单接口测试」与「批量回归测试」两个工作区，各自独立保留当前配置和本次结果。

### 单接口测试

| 区域 | 说明 |
| --- | --- |
| Method / URL | 选择 `GET` / `POST`，填写绝对 http/https 地址 |
| Params | 多组查询参数，增删后按 UTF-8 编码追加到 URL |
| Headers | 多组 `名称: 值` 请求头 |
| Body | 仅 POST 可见；填写 JSON 对象或数组，页面和后端都会校验语法 |
| Assertions | 预期 HTTP 状态码（100-599）+ 可选响应包含文本 |
| Result | PASS / FAIL / ERROR、状态码、耗时、中文失败原因 |
| 响应内容 | 目标接口实际返回，JSON 自动缩进，超过 64 KB 截断 |

快捷键：选项卡方向键切换、`Ctrl+Enter` 直接发送。响应内容仅在浏览器内存中预览，不写入报告或日志。

### 智能断言基线

单接口执行成功后，点击「从结果创建用例」：

- 根据真实响应的 HTTP 状态码建议预期状态码。
- 对于较短（≤ 120 字符）、非 JSON 格式、未截断的纯文本响应，建议以响应文本作为包含断言基线。
- 建议仅为辅助判断；JSON 响应、长文本或截断响应**不自动建议**。用户在编辑器中审核并「保存用例」后才加入测试集。

### 过期响应保护

- 切换 URL 到不同接口时，旧的 `expectContains` 会被清空并提示。
- 请求执行期间若修改了表单，返回后旧响应会被标记为过期，不能再创建新用例基线。
- 仅状态码通过但未校验包含文本的用例在批量中显示 `PASS·仅状态码`。

## 批量回归测试

页面顶部切换到「批量回归测试」工作区：

- 点击「添加用例」打开共享请求编辑器（单接口与批量编辑器 DOM 复用），从空 URL 和状态码 200 开始。
- 每条用例直接显示名称、Method + URL、预期状态码、包含断言、PASS / FAIL / ERROR 和耗时。
- 用例可单独编辑保存、删除、上下移动调整顺序。
- 「运行全部」按顺序逐个调用后端执行接口，每完成一条立即刷新状态与 `2/5` 进度。
- 运行期间锁定添加、编辑、删除、排序与导入，执行使用不可变快照避免结果错位。
- 含 POST 用例的批量运行会先提示可能修改目标服务数据，确认后才发送。
- 运行完成后生成 `N 项 PASS N FAIL N ERROR` 汇总，可下载 JSON 报告和离线 HTML 报告。

## Web 测试集 JSON 格式（导入/导出）

```json
{
  "version": 1,
  "cases": [
    {
      "name": "北京天气",
      "method": "GET",
      "url": "http://127.0.0.1:8080/api/weather/%E5%8C%97%E4%BA%AC",
      "headers": ["Accept: application/json"],
      "query": ["unit=c"],
      "expectStatus": 200,
      "expectContains": "多云"
    }
  ]
}
```

- 顶层 `version` 必须为 `1`，`cases` 非空且不超过 20 条。
- 导入做严格校验，任一条用例字段非法都会拒绝，保持当前测试集不变。
- POST 请求体字段为 `body`（字符串）；导入时也接受 `bodyJson`。
- **与 CLI `suite.json` 格式不同且不兼容**，CLI 的 `suite.json` 是路径字符串数组，Web 的 `cases` 是内联对象数组。

### 与 CLI 格式对比

| 区别点 | Web 测试集 | CLI suite.json |
| --- | --- | --- |
| 顶层结构 | `{version:1, cases:[对象]}` | `{cases:[路径字符串]}` |
| POST body 字段 | `body`（字符串） | 从外部单用例文件读取 |
| 用例数量上限 | 20 | 无硬编码上限 |
| 敏感头处理 | 导出时敏感头值写成 `***` | 无导出场景 |

## 批量报告格式

批量执行完成后浏览器生成 JSON 报告 v1，结构与 CLI 报告一致：

```json
{
  "version": 1,
  "generatedAt": "2026-11-12T04:19:27.405942900Z",
  "summary": { "total": 3, "pass": 2, "fail": 1, "error": 0 },
  "cases": [
    { "name": "北京天气", "status": "PASS", "expectedStatus": 200, "actualStatus": 200, "reasonCode": null },
    { "name": "创建用户", "status": "FAIL", "expectedStatus": 201, "actualStatus": 200, "reasonCode": "STATUS_MISMATCH" },
    { "name": "仅状态码-北京", "status": "PASS", "expectedStatus": 200, "actualStatus": 200, "reasonCode": null }
  ]
}
```

「下载 HTML 报告」生成独立离线 HTML 文件，显示执行时间、汇总和逐条详情，样式与主页面一致。报告不含脚本、外部 CDN 或联网资源，动态文本均经 HTML 转义，可直接离线打开。

## Web 执行接口

页面通过 `POST /api/run` 执行测试，请求体字段与单用例 JSON 文件一致（POST 场景多一个 `body` 字符串字段）：

```json
{
  "method": "GET",
  "url": "http://127.0.0.1:8080/api/weather/%E5%8C%97%E4%BA%AC",
  "headers": [],
  "query": [],
  "expectStatus": 200,
  "expectContains": "多云"
}
```

响应：

```json
{"status":"PASS","expectedStatus":200,"actualStatus":200,"reasonCode":null,"message":null,"durationMillis":12,"responseBody":"...","responseTruncated":false,"responseBytes":38}
```

`responseBody` 仅用于页面预览，不写入报告也不回显到后端日志。参数错误返回 `HTTP 400`，跨站请求返回 `HTTP 403`。该接口仅服务于本地页面，不承诺对外稳定。
