# API Regression Lab

一个轻量级 Java API 回归测试工具。当前版本（MVP-03）支持发送单个 GET 请求，可附带自定义请求头与查询参数，并对 HTTP 状态码和响应体字符串包含关系进行断言。

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

构建成功后生成可执行 JAR：`target/api-regression-lab-0.1.0.jar`。

## CLI 使用

```bash
java -jar target/api-regression-lab-0.1.0.jar --url <URL> --expect-status <状态码> [--expect-contains <文本>] [--header "名称: 值"]... [--query "名称=值"]...
```

示例（目标服务必须实际启动后才能访问）：

```bash
java -jar target/api-regression-lab-0.1.0.jar --url http://localhost:8080/api/weather --expect-status 200 --expect-contains "多云"
```

带自定义请求头与查询参数：

```bash
java -jar target/api-regression-lab-0.1.0.jar --url http://localhost:8080/api/weather --expect-status 200 --header "Accept: application/json" --header "X-Request-Id: demo-1" --query "city=北京" --query "unit=c"
```

参数：

- `--url`：目标 URL，必须是绝对 http 或 https 地址
- `--expect-status`：期望的 HTTP 状态码，取值 100-599
- `--expect-contains`：可选，期望响应体中包含的文本；使用区分大小写的普通字符串包含判断，不支持 JSONPath、正则或 JSON 字段解析。不提供时只断言状态码
- `--header`：可选，可重复，格式为 `"名称: 值"`，在第一个冒号处分割名称与值，值中可以包含冒号
- `--query`：可选，可重复，格式为 `"名称=值"`，在第一个等号处分割名称与值，值可以包含 `=` 或为空

### 请求头规则

- 名称必须是合法的 HTTP token 字符，名称或格式非法时输出 `ERROR` 并以退出码 2 结束
- JDK HttpClient 限制的请求头（`connection`、`content-length`、`expect`、`host`、`upgrade`）会被拒绝，并输出明确的 `ERROR` 提示
- 工具不会把请求头的值写入标准输出或错误输出，`Authorization`、`Cookie` 等敏感值不会出现在日志中；请求头解析失败时也只提示名称或格式问题，不回显原始值

### 查询参数规则

- 名称不能为空，缺少 `=` 或格式非法时输出 `ERROR` 并以退出码 2 结束
- 名称与值按 UTF-8 做百分号编码，中文、空格、`&`、`=`、`?` 等字符都会被正确编码（空格编码为 `%20`）
- URL 中已有的查询参数会被完整保留，新参数追加在其后
- 输入中已经合法的 `%XX` 百分号编码不会被重复编码

输出与退出码：

| 情况 | 输出 | 退出码 |
| --- | --- | --- |
| 状态码匹配，且未提供 `--expect-contains` 或响应体包含预期文本 | `PASS expected=200 actual=200` | 0 |
| 状态码不匹配 | `FAIL expected=200 actual=404` | 1 |
| 响应体不包含预期文本 | `FAIL expected=200 actual=200` 及 `reason: response body does not contain expected text: 多云` | 1 |
| 状态码与响应体断言都失败 | `FAIL expected=200 actual=404` 及内容缺失原因 | 1 |
| 参数错误或请求执行失败 | `ERROR <原因>` | 2 |

程序不会默认打印完整响应体，避免无意泄露响应数据。响应体按 UTF-8 解码。

## 运行测试

```bash
mvn test
```

测试使用 JDK 自带 `com.sun.net.httpserver.HttpServer` 在本机随机空闲端口启动模拟服务，结束后停止服务，不依赖公网或公司内部服务。

## 已实现功能

- Maven 项目结构与可执行 JAR 打包
- 使用 Java 原生 `java.net.http.HttpClient` 发送单个 GET 请求
- `--url`、`--expect-status` 与可选 `--expect-contains` 命令行参数
- 可选 `--expect-contains` 参数对响应体执行区分大小写的普通字符串包含断言
- 可重复的 `--header` 参数，支持自定义请求头，拒绝非法格式与 JDK HttpClient 限制的请求头
- 可重复的 `--query` 参数，按 UTF-8 编码并保留 URL 原有查询参数
- 请求头与查询参数的值不会输出到日志
- 响应体按 UTF-8 读取，不默认打印完整响应体
- HTTP 状态码与响应体包含断言，输出 PASS / FAIL / ERROR
- 退出码 0（PASS）、1（FAIL）、2（ERROR）
- 基于本机 HttpServer 的 JUnit 5 自动化测试

## 尚未实现功能

- POST 等其他 HTTP 方法
- JSONPath、正则或 JSON 字段级响应断言
- JSON 测试用例读取
- 批量测试
- JSON / HTML 测试报告
- 历史测试结果比较
- Web GUI
