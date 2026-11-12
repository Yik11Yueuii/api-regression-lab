# API Regression Lab

一个轻量级 Java API 回归测试工具。当前版本（V1-04）支持发送单个 GET 或 POST 请求，可附带自定义请求头、查询参数与 JSON 请求体；可从本地 JSON 文件读取单个测试用例；也可通过 suite 清单文件顺序批量执行多个用例，输出汇总并以 JSON 报告记录每次执行结果；仓库内置 GitHub Actions CI。

## 环境要求

- Java 21
- Maven 3.8.5 或更高版本（Maven 本身必须使用 Java 21 运行）

检查环境：

```bash
java -version
mvn -v
```

## 构建

```bash
mvn clean test
mvn package
```

构建成功后生成可执行 JAR：`target/api-regression-lab-0.1.0.jar`。JAR 通过 Maven Shade Plugin 打包，已内置 JSON 解析依赖（Jackson），可直接 `java -jar` 运行，无需额外配置 classpath。如果 Maven 默认不是 Java 21，可在当前终端临时切换（不要改系统全局变量）。

PowerShell：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -v
```

bash：

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
mvn -v
```

`mvn -v` 输出的 `Java version` 必须为 21，否则 `mvn clean test` 会因 `maven.compiler.release=21` 失败。

## CLI 使用

```bash
java -jar target/api-regression-lab-0.1.0.jar --url <URL> --expect-status <状态码> [--method GET|POST] [--body-json <JSON字符串>] [--expect-contains <文本>] [--header "名称: 值"]... [--query "名称=值"]...
java -jar target/api-regression-lab-0.1.0.jar --file <用例文件.json>
java -jar target/api-regression-lab-0.1.0.jar --suite <suite.json> [--report-json <报告文件.json>]
```

示例（目标服务必须实际启动后才能访问）：

```bash
java -jar target/api-regression-lab-0.1.0.jar --url http://localhost:8080/api/weather --expect-status 200 --expect-contains "多云"
```

带自定义请求头与查询参数（Windows PowerShell 使用双引号，值中的空格与中文无需自行转义）：

```powershell
java -jar target/api-regression-lab-0.1.0.jar --url "http://localhost:8080/api/weather?unit=c" --expect-status 200 --header "Accept: application/json" --header "X-Request-Id: demo-1" --query "city=北京" --query "q=hello world" --expect-contains "ok"
```

发送 POST 请求与 JSON 请求体。Windows PowerShell 5.1 向原生程序传参时会吞掉参数内的双引号，因此 JSON 里的双引号需要写成 `\"`，外层用单引号包裹（已实测服务端收到完整 JSON）：

```powershell
java -jar target/api-regression-lab-0.1.0.jar --url "http://localhost:8080/api/weather" --method POST --body-json '{\"name\":\"张三\",\"city\":\"北京\"}' --expect-status 201 --expect-contains "id" --header "Accept: application/json"
```

bash / zsh 中单引号会原样传递，直接写 JSON 即可：

```bash
java -jar target/api-regression-lab-0.1.0.jar --url 'http://localhost:8080/api/weather' --method POST --body-json '{"name":"张三","city":"北京"}' --expect-status 201 --expect-contains 'id'
```

bash 中使用单引号可以避免 `&`、`$`、空格被 shell 解释：

```bash
java -jar target/api-regression-lab-0.1.0.jar --url 'http://localhost:8080/api/weather?unit=c' --expect-status 200 --header 'Accept: application/json' --query 'city=北京' --query 'q=hello world' --expect-contains 'ok'
```

参数：

- `--url`：目标 URL，必须是绝对 http 或 https 地址
- `--method`：可选，`GET` 或 `POST`（不区分大小写），默认 `GET`；其他方法输出 `ERROR` 并以退出码 2 结束
- `--body-json`：可选，JSON 请求体字符串，仅在 `--method POST` 时允许，且 POST 必须提供非空值
- `--expect-status`：期望的 HTTP 状态码，取值 100-599
- `--expect-contains`：可选，期望响应体中包含的文本；使用区分大小写的普通字符串包含判断，不支持 JSONPath、正则或 JSON 字段解析。不提供时只断言状态码
- `--header`：可选，可重复，格式为 `"名称: 值"`，在第一个冒号处分割名称与值，值中可以包含冒号
- `--query`：可选，可重复，格式为 `"名称=值"`，在第一个等号处分割名称与值，值可以包含 `=` 或为空

### POST 与 JSON 请求体规则

- 请求体按 UTF-8 原样发送，不做 JSON 语法校验，也不做压缩或格式化
- 默认添加 `Content-Type: application/json; charset=utf-8`
- 如果显式传入 `Content-Type` 请求头，以用户提供的值为准，不会再追加默认值，也不会产生重复头
- 工具不会在正常输出或错误信息中回显请求体内容
- 显式提供的 `Content-Type` 必须是合法请求头格式，否则按请求头规则报错
- 未提供 `--method POST` 却传入 `--body-json`、POST 缺少请求体或 POST 请求体为空，均输出 `ERROR` 并以退出码 2 结束

### 请求头规则

- 名称必须是合法的 HTTP token 字符，名称或格式非法时输出 `ERROR` 并以退出码 2 结束
- JDK HttpClient 限制的请求头（`connection`、`content-length`、`expect`、`host`、`upgrade`）会被拒绝，并输出明确的 `ERROR` 提示
- 工具不会把请求头的值写入标准输出或错误输出，`Authorization`、`Cookie` 等敏感值不会出现在日志中；请求头解析失败时也只提示名称或格式问题，不回显原始值

### 查询参数规则

- 名称不能为空，缺少 `=` 或格式非法时输出 `ERROR` 并以退出码 2 结束
- 名称与值按 UTF-8 做百分号编码，中文、空格、`&`、`=`、`?` 等字符都会被正确编码（空格编码为 `%20`）
- URL 中已有的查询参数会被完整保留，新参数追加在其后
- 输入中已经合法的 `%XX` 百分号编码不会被重复编码

## JSON 文件模式

用 `--file <路径>` 从本地 JSON 文件读取一个测试用例，路径可以是相对路径或绝对路径：

```bash
java -jar target/api-regression-lab-0.1.0.jar --file examples/weather-get.json
```

文件模式与命令行模式互斥：`--file` 不能与 `--url`、`--method`、`--body-json`、`--expect-status`、`--expect-contains`、`--header`、`--query` 混用，也不能重复提供。

字段说明：

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `url` | 是 | 字符串 | 目标地址，必须是绝对 http/https URL |
| `method` | 否 | 字符串 | `GET` 或 `POST`，默认 `GET` |
| `headers` | 否 | 字符串数组 | 每项使用 `Name: Value` 格式，规则与 `--header` 相同 |
| `query` | 否 | 字符串数组 | 每项使用 `name=value` 格式，保留原有顺序与重复参数 |
| `bodyJson` | 否 | JSON 对象或数组 | POST 请求体，自动序列化为 UTF-8 JSON；GET 提供时报错 |
| `expectStatus` | 是 | 整数 | 100-599 |
| `expectContains` | 否 | 非空字符串 | 响应体包含断言 |

完整示例：[examples/weather-get.json](examples/weather-get.json)（GET + 中文查询参数）和 [examples/user-create-post.json](examples/user-create-post.json)（POST + 中文 JSON 请求体）。

这两个示例指向 `http://localhost:8080` 的本地模拟服务，不依赖任何外部或公司内部接口，验证前需要自行启动满足以下行为的服务：

- `GET /api/weather?city=<城市>`：返回 200，响应体包含 `多云`
- `POST /api/users`：返回 201，响应体包含 `id`，并接收 UTF-8 JSON 请求体

文件校验规则：

- 字段类型严格匹配，不做隐式转换，例如 `"expectStatus": "200"`（字符串）会报错
- 未知字段会报明确错误，避免拼写错误导致配置被静默忽略
- 缺少 `url` 或 `expectStatus` 时直接报错
- 文件不存在、无法读取或 JSON 格式错误时输出 `ERROR` 并以退出码 2 结束，且不回显文件内容、请求头值或请求体
- 文件内配置与命令行模式复用同一套校验、请求与断言流程（POST 请求体、Content-Type、请求头、查询参数、PASS/FAIL/ERROR 与退出码 0/1/2）
- 支持带 UTF-8 BOM 的 JSON 文件（仅兼容文件开头的标准 BOM，不做其他编码猜测）

## 批量执行（suite）

用 `--suite <清单路径>` 顺序执行多个已有测试用例文件：

```bash
java -jar target/api-regression-lab-0.1.0.jar --suite examples/suite.json
```

清单格式（[examples/suite.json](examples/suite.json)）：

```json
{
  "cases": [
    "weather-get.json",
    "user-create-post.json"
  ]
}
```

规则：

- `cases` 必填，必须是非空字符串数组；未知字段、空数组、非字符串条目都会报 `ERROR`
- 清单与 `--file`、`--url` 等参数互斥，`--suite` 不可重复；唯一允许的组合是 suite 模式的可选参数 `--report-json`（见下文）
- 相对路径以 suite 文件所在目录为基准，与当前工作目录无关；绝对路径同样支持（Windows 路径中的反斜杠需写成 `\\`，或改用 `/`）
- 按数组顺序逐个执行，单个用例 FAIL 或 ERROR 后继续执行剩余用例
- 每个用例打印一行 `CASE <名称>`，随后是该项的 PASS / FAIL / ERROR 输出；名称为条目原文（绝对路径条目只显示文件名），其中的换行、制表符等控制字符会被替换为 `?`，避免破坏日志格式
- 最后打印汇总：`SUMMARY total=N pass=N fail=N error=N`
- 退出码：全部 PASS 为 0；至少一个 FAIL 且没有 ERROR 为 1；至少一个 ERROR 为 2
- 清单无法读取、JSON 非法、`cases` 为空或类型错误时直接 `ERROR` 并以退出码 2 结束
- 无法构成本地路径的条目（例如包含控制字符）只会让该项记为 ERROR，不会中断整个 suite
- 不支持嵌套 suite，条目必须指向 V1-02 格式的单用例文件

输出与退出码：

| 情况 | 输出 | 退出码 |
| --- | --- | --- |
| 状态码匹配，且未提供 `--expect-contains` 或响应体包含预期文本 | `PASS expected=200 actual=200` | 0 |
| 状态码不匹配 | `FAIL expected=200 actual=404` | 1 |
| 响应体不包含预期文本 | `FAIL expected=200 actual=200` 及 `reason: response body does not contain expected text: 多云` | 1 |
| 状态码与响应体断言都失败 | `FAIL expected=200 actual=404` 及内容缺失原因 | 1 |
| 参数错误或请求执行失败 | `ERROR <原因>` | 2 |

程序不会默认打印完整响应体，避免无意泄露响应数据。响应体按 UTF-8 解码。

退出码可用于脚本或 CI 判断，例如 PowerShell：

```powershell
java -jar target/api-regression-lab-0.1.0.jar --url "http://localhost:8080/api/weather" --expect-status 200 --expect-contains "ok"
if ($LASTEXITCODE -ne 0) { throw "API regression failed with exit code $LASTEXITCODE" }
```

各结果的真实命令示例：

```powershell
# 退出码 0：状态码与响应体断言均通过
java -jar target/api-regression-lab-0.1.0.jar --url "http://localhost:8080/api/weather" --expect-status 200 --expect-contains "ok"

# 退出码 1：状态码不匹配（例如目标返回 404）
java -jar target/api-regression-lab-0.1.0.jar --url "http://localhost:8080/missing" --expect-status 200

# 退出码 2：参数错误（非法请求头）
java -jar target/api-regression-lab-0.1.0.jar --url "http://localhost:8080/api/weather" --expect-status 200 --header "NoColonHere"
```

## JSON 测试报告

在 suite 模式下追加 `--report-json <输出文件路径>`，执行完成后生成一份 JSON 报告：

```bash
java -jar target/api-regression-lab-0.1.0.jar --suite examples/suite.json --report-json reports/result.json
```

Windows 路径分隔符可写成 `\\` 或直接用 `/`。

规则：

- `--report-json` 只能与 `--suite` 一起使用，不能单独使用，也不能在单个 `--url` / `--file` 模式下使用
- 两个参数都不可重复；出现重复或与其他参数混用时输出 `ERROR` 并以退出码 2 结束
- 不提供 `--report-json` 时，suite 的行为与之前完全一致（只输出控制台结果）
- 输出路径支持相对路径与绝对路径；相对路径以**当前工作目录**为基准（注意：与 suite 中用例路径以 suite 文件目录为基准的规则不同）
- 目标文件的**父目录必须已存在**，工具不会自动创建目录
- 报告先写入同目录下的临时文件，再移动到目标路径：文件系统支持原子移动时使用原子替换；不支持时退回一次普通替换移动（此时临时文件已完整写入并关闭）。无论哪种方式，写入失败都不会留下不完整的最终报告，也不会残留临时文件
- 报告写入失败（目录不存在、无写权限等）会在控制台输出 `ERROR cannot write JSON report: <路径>`，进程以退出码 2 结束，不会假装执行成功
- 报告只包含用例名称、状态、状态码与原因代码，不保存请求头值（如 `Authorization`、`Cookie`）、请求体或任何响应体

报告格式（`version` 固定为 1，便于其他程序稳定读取）：

```json
{
  "version": 1,
  "generatedAt": "2026-11-11T09:34:35.369123600Z",
  "summary": {
    "total": 2,
    "pass": 2,
    "fail": 0,
    "error": 0
  },
  "cases": [
    {
      "name": "weather-get.json",
      "status": "PASS",
      "expectedStatus": 200,
      "actualStatus": 200,
      "reasonCode": null
    },
    {
      "name": "user-create-post.json",
      "status": "PASS",
      "expectedStatus": 201,
      "actualStatus": 201,
      "reasonCode": null
    }
  ]
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `version` | 整数 | 报告格式版本，当前为 `1` |
| `generatedAt` | 字符串 | 报告生成时间，UTC ISO-8601 格式 |
| `summary.total` | 整数 | 用例总数 |
| `summary.pass` / `fail` / `error` | 整数 | 各状态数量，三者之和等于 `total` |
| `cases[].name` | 字符串 | 用例展示名称，与 `CASE` 输出完全一致；绝对路径条目只显示文件名，控制字符替换为 `?` |
| `cases[].status` | 字符串 | `PASS`、`FAIL` 或 `ERROR` |
| `cases[].expectedStatus` | 整数或 null | 用例声明的期望状态码；用例文件无法读取或解析时为 `null` |
| `cases[].actualStatus` | 整数或 null | 实际收到的 HTTP 状态码；未取得响应（参数错误、连接失败等）时为 `null` |
| `cases[].reasonCode` | 字符串或 null | PASS 时为 `null`，否则为下表中的原因代码 |

原因代码：

| 原因代码 | 含义 |
| --- | --- |
| `STATUS_MISMATCH` | 实际状态码与 `expectStatus` 不一致 |
| `BODY_MISMATCH` | 状态码匹配，但响应体不包含 `expectContains` 文本 |
| `INVALID_CASE` | 用例文件缺失、无法读取，或内容不符合单用例文件规则 |
| `INVALID_REQUEST` | 构建请求失败（例如请求头被 JDK HttpClient 拒绝） |
| `REQUEST_FAILED` | 网络层失败（连接被拒绝、超时等），未取得响应 |
| `INTERRUPTED` | 请求被中断 |

报告中的 `cases` 顺序与 suite 清单顺序一致，便于与控制台输出逐项对照。退出码规则与不生成报告时相同（0 / 1 / 2）。

## 持续集成（GitHub Actions）

仓库内置 [.github/workflows/ci.yml](.github/workflows/ci.yml)：

- 在 `main` 分支 push 以及所有 Pull Request 上自动运行
- 使用 Ubuntu 与 Java 21（Temurin），并通过 `actions/setup-java` 缓存 Maven 依赖
- 执行 `mvn -B clean package`，即完整的编译、单元测试与打包
- 额外校验可执行 JAR：确认 `dev/yik/apilab/Main.class` 与 Jackson 类已打入 JAR，并用一个不可达地址运行 JAR，确认退出码为 2
- 上传构建产物 JAR 作为 workflow artifact
- 所有自动化测试都使用本机启动的模拟 HTTP 服务，不访问公司接口或外部测试 API，也不需要配置任何 GitHub Secrets
- 不会自动创建 Release，也不会自动合并 PR

## 运行测试

```bash
mvn clean test
```

测试使用 JDK 自带 `com.sun.net.httpserver.HttpServer` 在本机随机空闲端口启动模拟服务，结束后停止服务，不依赖公网或公司内部服务。除直接调用入口方法的单元测试外，还包含以子进程方式启动 CLI 的集成测试，用于校验真实的进程退出码 0 / 1 / 2。

## 已实现功能

- Maven 项目结构与可执行 JAR 打包
- 使用 Java 原生 `java.net.http.HttpClient` 发送单个 GET 或 POST 请求
- `--method GET|POST` 选择 HTTP 方法，默认 GET
- `--body-json` 以 UTF-8 发送 JSON 请求体，默认 `Content-Type: application/json; charset=utf-8`，显式 Content-Type 优先
- `--url`、`--expect-status` 与可选 `--expect-contains` 命令行参数
- 可选 `--expect-contains` 参数对响应体执行区分大小写的普通字符串包含断言
- 可重复的 `--header` 参数，支持自定义请求头，拒绝非法格式与 JDK HttpClient 限制的请求头
- 可重复的 `--query` 参数，按 UTF-8 编码并保留 URL 原有查询参数
- 请求头与查询参数的值不会输出到日志
- 响应体按 UTF-8 读取，不默认打印完整响应体
- HTTP 状态码与响应体包含断言，输出 PASS / FAIL / ERROR
- 退出码 0（PASS）、1（FAIL）、2（ERROR）
- 基于本机 HttpServer 的 JUnit 5 自动化测试
- 以子进程启动 CLI 的集成测试，覆盖真实进程退出码 0 / 1 / 2
- `--file` 从本地 JSON 文件读取单个测试用例，字段类型、必填项与未知字段均做严格校验
- `--suite` 按清单顺序批量执行多个用例，逐项输出结果并打印 `SUMMARY` 汇总，支持相对/绝对路径与 UTF-8 BOM
- `--report-json` 在 suite 模式下生成 JSON 测试报告（临时文件 + 原子替换，不支持原子移动时退回普通替换；不包含请求头值/请求体/响应体）
- 控制台 `CASE` 名称与错误信息中的控制字符会被替换，避免破坏日志格式
- Maven Shade Plugin 打包可自包含运行的 JAR（包含 Jackson）
- GitHub Actions CI（Ubuntu + Java 21）自动执行测试、打包与 JAR 校验

## 尚未实现功能

- PUT、DELETE 等其他 HTTP 方法
- JSON 语法校验与 JSON 字段级断言
- 并发执行、失败重试与用例间变量关联
- HTML 测试报告
- 历史测试结果比较
- Web GUI
