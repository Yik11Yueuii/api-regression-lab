# CLI 参考

本页整理 API Regression Lab v0.3.0 的命令行用法、JSON 文件模式、批量执行和报告格式。CLI 与 Web UI 共享同一套请求与断言引擎。

## 快速调用

```bash
java -jar api-regression-lab-0.3.0.jar --url <URL> --expect-status <状态码> [选项]
java -jar api-regression-lab-0.3.0.jar --file <用例文件.json>
java -jar api-regression-lab-0.3.0.jar --suite <suite.json> [--report-json <报告文件.json>]
```

所有示例的目标服务必须实际监听；如需脱离外部依赖，可参考仓库中的本地模拟服务说明。

## 参数

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `--url` | 单用例必填 | 绝对 http/https 地址 |
| `--method` | 否 | `GET` 或 `POST`，默认 `GET` |
| `--body-json` | POST 必填 | JSON 请求体字符串，UTF-8 发送 |
| `--expect-status` | 是 | 100-599 之间的整数 |
| `--expect-contains` | 否 | 期望响应体包含的文本；不提供时只断言状态码 |
| `--header` | 可重复 | 格式 `"名称: 值"`，在第一个冒号处分割 |
| `--query` | 可重复 | 格式 `"名称=值"`，按 UTF-8 百分号编码 |
| `--file` | 文件模式 | 单用例 JSON 文件，相对或绝对路径 |
| `--suite` | suite 模式 | 清单 JSON 文件，列出多个单用例路径 |
| `--report-json` | suite 模式可选 | JSON 报告输出路径，父目录必须已存在 |

参数之间严格互斥；混用或重复会输出 `ERROR` 并以退出码 2 结束。

## 请求规则

- POST 默认追加 `Content-Type: application/json; charset=utf-8`；显式提供时以用户值为准。
- 请求头名必须是合法 HTTP token 字符；`connection`、`content-length`、`host` 等 JDK HttpClient 限制的头会被拒绝。
- 查询参数按 UTF-8 编码，中文、空格、`&`、`=`、`?` 都会正确处理。URL 中已有的查询参数会被完整保留，新参数追加在其后。
- 请求头的值、请求体和完整响应体**不会**写入标准输出或错误输出；出错时也只回显简短原因。

## 退出码

| 退出码 | 含义 |
| --- | --- |
| 0 | PASS：状态码匹配，且未提供 `--expect-contains` 或响应体包含预期文本 |
| 1 | FAIL：状态码不匹配，或状态码匹配但响应体不包含预期文本 |
| 2 | ERROR：参数错误、文件缺失、请求头非法、连接失败等 |

示例（PowerShell，POST JSON 需转义双引号）：

```powershell
java -jar api-regression-lab-0.3.0.jar --url "http://localhost:8080/api/weather" `
  --method POST --body-json '{\"name\":\"张三\"}' `
  --expect-status 201 --header "Content-Type: application/json"
if ($LASTEXITCODE -ne 0) { throw "API regression failed" }
```

## 单用例 JSON 文件（`--file`）

文件指向单个 `{...}` 对象，与 CLI 单用例字段一致。示例：

```json
{
  "url": "http://127.0.0.1:8080/api/weather/%E5%8C%97%E4%BA%AC",
  "method": "GET",
  "headers": ["Accept: application/json"],
  "query": ["unit=c"],
  "bodyJson": null,
  "expectStatus": 200,
  "expectContains": "多云"
}
```

完整字段表：

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `url` | 是 | 字符串 | 绝对 http/https URL |
| `method` | 否 | 字符串 | `GET` 或 `POST`，默认 `GET` |
| `headers` | 否 | 字符串数组 | 每项 `Name: Value` |
| `query` | 否 | 字符串数组 | 每项 `name=value`，保留顺序 |
| `bodyJson` | 否 | JSON 对象/数组 | POST 请求体，自动序列化为 UTF-8 JSON |
| `expectStatus` | 是 | 整数 | 100-599 |
| `expectContains` | 否 | 非空字符串 | 响应体包含断言 |

- 字段类型严格匹配，不做隐式转换（例如 `"expectStatus": "200"` 字符串会报错）。
- 未知字段会报明确错误，避免拼写错误被静默忽略。
- 支持带 UTF-8 BOM 的文件开头。

已提交示例：[examples/weather-get.json](../examples/weather-get.json)、[examples/user-create-post.json](../examples/user-create-post.json)。

## 批量清单（`--suite`）

清单列出多个单用例文件路径：

```json
{
  "cases": [
    "weather-get.json",
    "user-create-post.json"
  ]
}
```

- `cases` 必填、非空、只能是字符串数组；未知字段会报错。
- 相对路径以**清单文件所在目录**为基准（不是当前工作目录）。
- 按数组顺序逐个执行，单个用例 FAIL/ERROR 不会中断后续用例。
- 控制台逐行打印 `CASE <名称>` + PASS/FAIL/ERROR，最后打印 `SUMMARY total=N pass=N fail=N error=N`。

退出码规则：全部 PASS 为 0；至少一个 FAIL 且无 ERROR 为 1；至少一个 ERROR 为 2。

示例：[examples/suite.json](../examples/suite.json)。

## JSON 测试报告

suite 模式追加 `--report-json <路径>`，生成 JSON 报告（固定版本号 1）：

```bash
java -jar api-regression-lab-0.3.0.jar --suite examples/suite.json --report-json reports/result.json
```

```json
{
  "version": 1,
  "generatedAt": "2026-11-11T09:34:35.369123600Z",
  "summary": { "total": 2, "pass": 2, "fail": 0, "error": 0 },
  "cases": [
    {
      "name": "weather-get.json",
      "status": "PASS",
      "expectedStatus": 200,
      "actualStatus": 200,
      "reasonCode": null
    }
  ]
}
```

- `--report-json` 只能与 `--suite` 搭配；输出路径支持相对路径（以当前工作目录为基准）和绝对路径。
- 目标文件父目录必须已存在，工具不会自动创建目录。
- 写入使用临时文件 + 原子替换，不留下不完整报告。
- 报告**不包含**响应体、请求头值或请求体。

`cases[].reasonCode` 枚举：

| 代码 | 含义 |
| --- | --- |
| `STATUS_MISMATCH` | 实际状态码与 `expectStatus` 不一致 |
| `BODY_MISMATCH` | 状态码匹配但响应体不包含 `expectContains` |
| `INVALID_CASE` | 用例文件缺失、不可读或格式非法 |
| `INVALID_REQUEST` | 构建请求失败（请求头被 JDK HttpClient 拒绝等） |
| `REQUEST_FAILED` | 网络层失败（连接被拒绝、超时等） |
| `INTERRUPTED` | 请求被中断 |
