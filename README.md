# API Regression Lab

一个轻量级 Java API 回归测试工具。当前版本 v0.2.0 支持发送单个 GET 或 POST 请求，可附带自定义请求头、查询参数与 JSON 请求体；可从本地 JSON 文件读取单个测试用例；也可通过 suite 清单文件顺序批量执行多个用例，输出汇总并以 JSON 报告记录每次执行结果；仓库内置 GitHub Actions CI。V2-01 起提供本地 Web 页面，可在浏览器中完成单接口可视化测试，无需重复输入命令。V2-02 起支持 Web 测试集；V2-03 将单接口与批量回归整理为顶部双工作区，完善用例创建、编辑、响应预览，并可下载 JSON 或独立离线 HTML 报告。

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

构建成功后生成可执行 JAR：`target/api-regression-lab-0.2.0.jar`。JAR 通过 Maven Shade Plugin 打包，已内置 JSON 解析依赖（Jackson），可直接 `java -jar` 运行，无需额外配置 classpath。如果 Maven 默认不是 Java 21，可在当前终端临时切换（不要改系统全局变量）。

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

## 图形界面（本地 Web UI）

V2-01 起提供本地 Web 页面，用浏览器完成单接口测试。页面通过本机 HTTP 服务调用与 CLI 完全相同的请求和断言逻辑，不是另一套测试引擎。

### 启动方式

Windows 下双击仓库根目录的 `start-web.bat`。脚本会：

1. 依次检查有效的 `JAVA_HOME`、`%USERPROFILE%\.jdks\openjdk-21.0.2` 以及 PATH 上的 `java`，只接受 Java 21；
2. 检查 `target\api-regression-lab-0.2.0.jar` 是否存在，缺失时提示先执行 `mvn clean package`；
3. 启动服务并自动打开默认浏览器访问 <http://127.0.0.1:8765/>。

也可以在终端手动启动：

```bash
java -jar target/api-regression-lab-0.2.0.jar --web
```

启动后保持脚本窗口或终端开启，按 `Ctrl+C` 停止服务。启动失败（未找到 Java 21、JAR 不存在、端口被占用等）会打印明确错误并保留窗口，不会静默退出。

### 访问范围与安全边界

- 只监听 `127.0.0.1:8765`（loopback），默认不监听所有网卡，局域网内其他机器无法访问
- `--web` 不能与其他命令行参数混用；重复提供或与其他模式混用时输出 `ERROR` 并以退出码 2 结束，原有 CLI 行为与退出码不变
- 执行接口只接受本页面发起的请求：同时校验 `Origin` 与 `Host`，跨站网页或缺失 `Origin` 的请求返回 `403`，且不返回任何 CORS 允许头
- 页面不写入 localStorage，不持久化 `Authorization`、`Cookie` 等敏感请求头，也不默认记录完整请求体
- 出错时只返回简短说明，不显示 Java 异常堆栈
- 响应内容只在浏览器内存中预览，不写入测试报告、不写入标准输出/错误输出，也不落盘；关闭页面即丢失

### 页面功能

| 区域 | 说明 |
| --- | --- |
| Method / URL | 选择 `GET` 或 `POST`，填写绝对 http/https 地址 |
| Params | 多组查询参数，可增删，按 UTF-8 编码后追加到 URL |
| Headers | 多组 `名称: 值` 请求头，可增删 |
| Body | 仅 POST 显示，填写 JSON 对象或数组；页面与后端都会校验 JSON 语法 |
| Assertions | 预期 HTTP 状态码（100-599）与可选的响应包含文本 |
| Result | PASS / FAIL / ERROR、预期与实际状态码、失败原因、耗时 |
| 响应内容 | 目标接口返回的实际文本；JSON 自动缩进格式化，普通文本原样展示，超过 64 KB 截断并提示 |

交互说明：

- 点击「发送测试」后通过 `fetch` 提交，页面不刷新
- 请求期间按钮禁用并显示「发送中…」，避免重复提交
- 表单错误显示在对应输入框下方，并自动切换选项卡、聚焦第一个错误项
- 选项卡支持左右方向键、`Home`、`End` 切换，`Ctrl` + `Enter` 直接发送
- 响应内容使用纯文本渲染，不解析也不执行其中的 HTML 或脚本
- 响应内容按 UTF-8 字节数限制为 64 KB，超出时截断并提示「仅显示前 64 KB」，不会截断半个中文字符
- 请求未完成（如连接被拒绝）时不显示任何虚构的响应内容，只显示失败原因
- 首次打开页面预填本地天气示例：`GET http://127.0.0.1:8080/api/weather/北京`，预期 200 且响应包含「多云」。示例指向本地模拟服务，可随时替换为任意 http/https 接口

### Web 执行接口

页面通过 `POST /api/run` 执行测试，请求体字段与单用例 JSON 文件一致，POST 场景额外提供字符串字段 `body`：

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

响应示例：

```json
{"status":"PASS","expectedStatus":200,"actualStatus":200,"reasonCode":null,"message":null,"durationMillis":12,"responseBody":"{\"city\":\"北京\",\"weather\":\"多云\"}","responseTruncated":false,"responseBytes":38}
```

`responseBody` 为目标的实际响应文本，仅供页面预览：`responseBytes` 是完整响应的 UTF-8 字节数，超过 64 KB 时 `responseTruncated` 为 `true` 且 `responseBody` 只保留前 64 KB；请求未完成时 `responseBody` 为 `null`。该字段不写入测试报告，也不影响 CLI 输出格式。

参数错误或非法 JSON 返回 `HTTP 400`，跨站请求返回 `HTTP 403`，响应体同样包含 `status`、`reasonCode` 与 `message`。该接口仅服务于本地页面，不承诺对外稳定。

### Web 批量回归测试（V2-03）

页面顶部提供「单接口测试」和「批量回归测试」两个工作区，切换时保留各自的当前配置和本次结果。批量工作区是独立完整的页面区域，无需经过单接口响应区。点击「运行全部」后，页面按顺序逐个调用 `POST /api/run`，每完成一条就立即刷新该用例的状态与 `2/5` 形式的进度；每个请求仍由 CLI 完全相同的请求与断言逻辑执行，不是第二套测试引擎。

#### 页面操作

- 点击批量工作区的「添加用例」打开共享请求编辑器。单接口和批量添加/编辑复用同一个表单 DOM，不维护两套请求编辑器；关闭编辑器后恢复原来的单接口草稿
- 新增用例从空 URL、空 Params/Headers/Body、预期状态码 200、空包含文本开始，不会继承上一条用例的断言。编辑器持续显示当前断言；URL 会自动生成可修改的默认名称
- 单接口得到结果后，可点击「从结果创建用例」。页面先请求确认，再复制该次结果对应的请求快照；仍需在编辑器中审核并点击「保存用例」才会加入测试集
- 点击用例行的 ✎ 编辑名称、Method、URL、Params、Headers、JSON Body 和断言。「保存用例」只写回当前位置，「取消」或关闭按钮放弃修改，不会误新增或覆盖其他用例；编辑期间需先保存或取消才能切回单接口
- 每条用例直接显示名称、Method + URL、预期状态码、期望包含文本、PASS / FAIL / ERROR 和耗时。FAIL / ERROR 自动展开中文原因，点击行可展开或收起详情；实际响应只在当前页面预览，仍遵守 64 KiB 限制
- 测试集中的每个用例可单独删除、上下移动调整执行顺序；支持「导出…」下载测试集 JSON，或「导入…」严格校验后恢复
- 批量运行期间锁定添加、编辑、删除、排序与导入，执行使用不可变快照并按用例 id 关联结果，避免结果写回错误的行
- 运行完成后显示总数、PASS、FAIL、ERROR 汇总，可分别下载 JSON 报告和离线 HTML 报告
- 任何会改变测试集的操作（新增、编辑保存、删除、排序、导入）都会让上一份报告失效，两个报告下载按钮随之不可用，避免下载与当前配置不对应的结果
- 测试集包含 POST 用例时，点击「运行全部」会先提示可能修改目标服务的数据，确认后才发送

#### Web 测试集 JSON 格式（单文件，可导入/导出）

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
    },
    {
      "name": "创建用户",
      "method": "POST",
      "url": "http://127.0.0.1:8080/api/users",
      "headers": ["Content-Type: application/json"],
      "body": "{\"name\":\"张三\"}",
      "expectStatus": 201,
      "expectContains": "id"
    }
  ]
}
```

字段与单用例 JSON 文件（`--file`）高度相似，但有几个区别：

| 区别点 | Web 测试集 | CLI 单用例文件 | CLI suite 清单 |
| --- | --- | --- | --- |
| 顶层结构 | `{version: 1, cases: [...]}` | 单个 `{...}` | `{cases: [字符串]}` |
| POST 请求体字段 | `body`（字符串）；导入时也接受 `bodyJson`（JSON 对象或数组） | `bodyJson`（JSON 对象或数组） | 从外部文件读取 |
| 用例名称 | 必须有 `name`，同一文件内不可重复 | 无名称字段 | 文件名即为名称 |
| 用例数量上限 | 20 个 | 单个 | 无硬编码上限 |
| 敏感头处理 | 导出时只把敏感请求头的值写成 `***` | 无导出场景 | 无 |

导入时会严格校验：`version` 必须为 `1`、`cases` 必须是非空数组且不超过 20 个、每个用例必须是对象且只包含已知字段，并逐一校验 `name`、`method`、`url`、`headers`、`query`、`expectStatus`、POST `body` 与重复名称。任一校验失败都会给出具体到「第 N 个用例」的错误提示，并保持当前测试集完全不变，不会出现部分导入。

**重要**：Web 测试集 JSON 与 CLI `suite.json` 是**不同格式**。Web 的 `cases` 是内联对象数组（每个对象完整定义一个用例），而 CLI 的 `suite.json` 的 `cases` 是相对路径字符串数组（每个字符串指向外部单用例文件）。两者**不兼容**，不能直接混用。

#### 安全与数据边界

- 测试集只保存在当前页面内存中，不写入 `localStorage` 或 `sessionStorage`；刷新页面后需要用导入文件恢复，或重新创建
- 导出前会明确提示：`Authorization`、`Cookie`、`Set-Cookie`、`Proxy-Authorization` 请求头的值会写成 `***`；URL、查询参数与请求体**不会自动脱敏**，其中可能包含 token、密钥或其他凭据，需要用户自行判断是否适合保存到文件
- 导入后，敏感请求头值为 `***` 的用例会标记「待补全凭据」；在编辑补全之前点击「运行全部」会被拒绝，`***` 绝不会被当成真实凭据发送
- JSON 报告由浏览器按 CLI v1 结构生成，**不包含**任何响应体、请求头值或请求体，V2-03 未改变其字段
- HTML 报告默认不包含 Authorization、Cookie、请求体、完整 URL 或响应正文；所有动态文本都会进行 HTML 转义，文件不含脚本、外部 CDN 或联网资源，可直接离线打开
- 详细响应内容（`responseBody`）仅由 `/api/run` 返回用于页面预览，不写入 JSON 或 HTML 报告、不打印到后端日志
- `/api/run` 只接受 loopback Origin、校验 `Origin` 与 `Host`、拒绝跨站请求
- 测试集最多 20 个用例，超出时页面会阻止新增或拒绝导入
- 单个用例 FAIL 或 ERROR 后继续执行后续用例，不会中断整个批量执行
- POST 用例只在用户明确点击「运行全部」并确认后发送，页面加载后不会自动执行

#### 批量报告格式

批量执行完成后，浏览器生成一份与 CLI 报告 v1 结构相同的 JSON 报告（不包含 `responseBody`、不包含请求凭据），点击「下载 JSON 报告」即可保存到本地：

```json
{
  "version": 1,
  "generatedAt": "2026-11-12T04:19:27.405942900Z",
  "summary": { "total": 3, "pass": 2, "fail": 1, "error": 0 },
  "cases": [
    { "name": "北京天气", "status": "PASS", "expectedStatus": 200, "actualStatus": 200, "reasonCode": null },
    { "name": "创建用户", "status": "FAIL", "expectedStatus": 201, "actualStatus": 200, "reasonCode": "STATUS_MISMATCH" }
  ]
}
```

原因代码语义与 CLI 完全一致，见上文「JSON 测试报告」章节。

「下载 HTML 报告」生成一个独立 HTML 文件，显示执行时间、总用例数、PASS / FAIL / ERROR，以及每条用例的名称、结果、预期与实际状态码、耗时和中文结果说明。视觉样式与主页面一致，窄屏也可阅读。HTML 中的动态名称和说明均经过转义，并通过限制性 CSP 禁止脚本和联网加载；报告不包含请求凭据、请求体、完整 URL 或响应正文。

## CLI 使用

```bash
java -jar target/api-regression-lab-0.2.0.jar --url <URL> --expect-status <状态码> [--method GET|POST] [--body-json <JSON字符串>] [--expect-contains <文本>] [--header "名称: 值"]... [--query "名称=值"]...
java -jar target/api-regression-lab-0.2.0.jar --file <用例文件.json>
java -jar target/api-regression-lab-0.2.0.jar --suite <suite.json> [--report-json <报告文件.json>]
```

示例（目标服务必须实际启动后才能访问）：

```bash
java -jar target/api-regression-lab-0.2.0.jar --url http://localhost:8080/api/weather --expect-status 200 --expect-contains "多云"
```

带自定义请求头与查询参数（Windows PowerShell 使用双引号，值中的空格与中文无需自行转义）：

```powershell
java -jar target/api-regression-lab-0.2.0.jar --url "http://localhost:8080/api/weather?unit=c" --expect-status 200 --header "Accept: application/json" --header "X-Request-Id: demo-1" --query "city=北京" --query "q=hello world" --expect-contains "ok"
```

发送 POST 请求与 JSON 请求体。Windows PowerShell 5.1 向原生程序传参时会吞掉参数内的双引号，因此 JSON 里的双引号需要写成 `\"`，外层用单引号包裹（已实测服务端收到完整 JSON）：

```powershell
java -jar target/api-regression-lab-0.2.0.jar --url "http://localhost:8080/api/weather" --method POST --body-json '{\"name\":\"张三\",\"city\":\"北京\"}' --expect-status 201 --expect-contains "id" --header "Accept: application/json"
```

bash / zsh 中单引号会原样传递，直接写 JSON 即可：

```bash
java -jar target/api-regression-lab-0.2.0.jar --url 'http://localhost:8080/api/weather' --method POST --body-json '{"name":"张三","city":"北京"}' --expect-status 201 --expect-contains 'id'
```

bash 中使用单引号可以避免 `&`、`$`、空格被 shell 解释：

```bash
java -jar target/api-regression-lab-0.2.0.jar --url 'http://localhost:8080/api/weather?unit=c' --expect-status 200 --header 'Accept: application/json' --query 'city=北京' --query 'q=hello world' --expect-contains 'ok'
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
java -jar target/api-regression-lab-0.2.0.jar --file examples/weather-get.json
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
java -jar target/api-regression-lab-0.2.0.jar --suite examples/suite.json
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
java -jar target/api-regression-lab-0.2.0.jar --url "http://localhost:8080/api/weather" --expect-status 200 --expect-contains "ok"
if ($LASTEXITCODE -ne 0) { throw "API regression failed with exit code $LASTEXITCODE" }
```

各结果的真实命令示例：

```powershell
# 退出码 0：状态码与响应体断言均通过
java -jar target/api-regression-lab-0.2.0.jar --url "http://localhost:8080/api/weather" --expect-status 200 --expect-contains "ok"

# 退出码 1：状态码不匹配（例如目标返回 404）
java -jar target/api-regression-lab-0.2.0.jar --url "http://localhost:8080/missing" --expect-status 200

# 退出码 2：参数错误（非法请求头）
java -jar target/api-regression-lab-0.2.0.jar --url "http://localhost:8080/api/weather" --expect-status 200 --header "NoColonHere"
```

## JSON 测试报告

在 suite 模式下追加 `--report-json <输出文件路径>`，执行完成后生成一份 JSON 报告：

```bash
java -jar target/api-regression-lab-0.2.0.jar --suite examples/suite.json --report-json reports/result.json
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

测试使用 JDK 自带 `com.sun.net.httpserver.HttpServer` 在本机随机空闲端口启动模拟服务，结束后停止服务，不依赖公网或公司内部服务。除直接调用入口方法的单元测试外，还包含以子进程方式启动 CLI 的集成测试，用于校验真实的进程退出码 0 / 1 / 2，以及覆盖 Web 模式的测试（loopback 绑定、Origin 拒绝、GET/POST 执行、参数错误、网络错误与敏感信息不回显）。

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
- `--web` 启动本地 Web 页面（`com.sun.net.httpserver.HttpServer`，仅监听 `127.0.0.1:8765`），复用 CLI 请求与断言逻辑
- Web 页面支持 GET/POST、Params、Headers、JSON Body、状态码与响应包含断言，结果展示 PASS/FAIL/ERROR
- Web 执行接口校验 `Origin` 与 `Host`，拒绝跨站调用；错误只返回简短说明，不显示异常堆栈
- `start-web.bat` 双击启动，自动检测 Java 21、检查 JAR 并打开浏览器
- 顶部「单接口测试 / 批量回归测试」双工作区，切换保留配置和本次结果；批量用例复用同一个请求编辑器
- 用例创建、命名、断言审核、编辑保存、取消、删除与排序；支持从单接口结果确认创建，新增不会继承上一条断言
- Web 测试集导入/导出（单文件 JSON），导入做严格校验且失败时不部分替换；与 CLI suite.json 格式不同且明确区分
- 批量执行在前端按顺序调用 `/api/run`，每完成一条立即更新进度与状态，复用 CLI 请求/断言引擎
- 批量列表直接展示断言、状态与耗时，FAIL / ERROR 自动展开中文原因和仅内存保存的 64 KiB 响应预览
- 运行期间锁定测试集变更，使用执行快照按用例 id 关联结果；配置变更后旧报告自动失效
- JSON 报告沿用 CLI v1 结构；独立离线 HTML 报告对动态文本转义，均不含响应正文、请求头、请求体或完整 URL
- 导出时敏感请求头值写成 `***` 并明确提示 URL/查询参数/请求体可能仍含凭据；导入后未补全的 `***` 不会被当作真实凭据发送
- 含 POST 用例的批量执行需要用户确认；测试集仅存于页面内存，最多 20 个用例

## 尚未实现功能

- PUT、DELETE 等其他 HTTP 方法
- JSON 字段级断言（目前只支持状态码与响应文本包含）
- 并发执行、失败重试与用例间变量关联
- 历史测试结果比较
