# 构建、测试与安全边界

## 构建

```bash
mvn clean package
```

产物：`target/api-regression-lab-0.3.0.jar`（Maven Shade Plugin 打包，已内置 Jackson，可直接 `java -jar` 运行）。

需要 JDK 21：`pom.xml` 的 `maven.compiler.release` 已固定为 21。如果默认 Maven 使用的 JDK 版本不对，在当前终端临时切换即可（不要改系统全局变量）。

PowerShell：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -v   # 输出中的 Java version 必须为 21
```

bash：

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
mvn -v
```

## 运行测试

```bash
mvn clean test
```

当前 131 项自动化测试全部通过（`MainTest` 94 + `WebServerTest` 37）。测试用 JDK 自带 `com.sun.net.httpserver.HttpServer` 在本机随机空闲端口启动模拟服务，结束后自动停止，不依赖公网或公司内部服务。

测试覆盖：

- 入口方法单元测试与子进程集成测试（真实退出码 0 / 1 / 2）
- Web 模式：loopback 绑定、Origin/Host 校验、GET/POST 执行、参数错误、网络失败、敏感信息不回显
- JSON 报告 v1 结构与原子写入
- 断言基线建议、URL 切换保护、过期结果防误用、批量报告隐私安全

## GitHub Actions CI

仓库内置 [.github/workflows/ci.yml](../.github/workflows/ci.yml)：

- 在 `main` 分支 push 及所有 Pull Request 上自动运行。
- Ubuntu + Temurin JDK 21，缓存 Maven 依赖。
- 执行 `mvn -B clean package`（编译 + 测试 + 打包）。
- 额外校验 JAR 内含 `dev/yik/apilab/Main.class` 与 Jackson，并用不可达地址运行 JAR，确认退出码为 2。
- 不使用 GitHub Secrets，不自动创建 Release 或合并 PR。

## Web 安全边界

- 仅监听 `127.0.0.1:8765`（loopback），局域网其他机器无法访问。
- `--web` 不能与其他 CLI 参数混用，混用退出码 2。
- 执行接口同时校验 `Origin` 与 `Host`，跨站网页返回 `403`，且不设置 CORS 响应头。
- 页面不写入 localStorage / sessionStorage；刷新后批量测试集丢失。
- 出错时只返回简短说明，不回显 Java 异常堆栈。
- 响应内容只在浏览器内存中预览，不写入报告、不写入标准输出、不落盘。

### 批量测试集敏感信息

- 导出 JSON 测试集时，`Authorization`、`Cookie`、`Set-Cookie`、`Proxy-Authorization` 请求头的值会写成 `***`。
- **URL、查询参数和请求体不会自动脱敏**，其中可能仍含 token、密钥或其他凭据，需要用户自行判断。
- 导入后值为 `***` 的敏感头会标记「待补全凭据」，运行批量前必须编辑补全，否则被拒绝。

### 报告隐私

- JSON 报告（CLI 与 Web 版）只记录用例名称、状态码和原因代码，**不包含**请求头值、请求体或响应正文。
- 离线 HTML 报告不含脚本、外部 CDN、联网资源；动态名称与说明均经 HTML 转义；不包含请求凭据、请求体、完整 URL 或响应正文。

## 已知限制

- 仅支持 `GET` / `POST`，不支持 PUT、DELETE 等其他 HTTP 方法。
- 断言只支持状态码和响应文本包含判断，不支持 JSON 字段级断言、正则或 JSONPath。
- 批量执行顺序调用，不支持并发、失败重试或用例间变量关联。
- 无历史结果比较或趋势统计。
- Web 批量测试集上限 20 个用例；CLI suite 无硬上限，但建议单批不超过 50 个。
- Web 页面不持久化状态，刷新即丢失批量测试集。
