# API Regression Lab v0.3.0

Java 21 · CLI + 本地 Web UI · 单接口和批量回归测试

## Highlights

- **单接口测试工作区**：可视化发送 GET / POST 请求，配置 URL、Headers、Params、JSON Body 和断言，查看状态码、耗时与有限长度的响应预览。
- **批量回归测试**：创建、编辑、排序、删除及导入/导出 JSON 测试集；按顺序执行用例，实时显示 PASS / FAIL / ERROR 和中文原因。
- **智能断言基线**：单接口响应确认后，建议以实际 HTTP 状态码建立预期；对于较短、未截断的非 JSON 文本可建议响应包含断言。需要用户审核并确认，不能替代业务正确性判断。
- **断言与过期结果保护**：切换接口 URL 时避免沿用旧断言；表单修改后阻止利用过期响应创建基线；区分仅验证状态码的 PASS。
- **双格式报告**：下载 JSON Report v1 或独立离线 HTML 报告；HTML 报告默认不包含原始断言文本、完整 URL、请求头/体和响应正文。
- **本地优先**：Web Server 仅监听 loopback；测试集只保留在页面内存，刷新后需手动导入；敏感请求头在测试集导出时脱敏并在导入后要求补全。

## Requirements

- JDK 21
- Windows 可使用根目录 `start-web.bat`
- Maven 本地构建：`mvn package`
- JAR：`target/api-regression-lab-0.3.0.jar`
- Web UI：`java -jar target/api-regression-lab-0.3.0.jar --web`，打开 `http://127.0.0.1:8765/`

## Quick verification

1. 使用本地天气接口或其他可控测试服务确认 GET 请求 PASS。
2. 根据短文本响应创建并审核一条回归基线；再创建至少两条用例。
3. 点击「运行全部」，检查汇总与单条结果、导出 JSON/HTML 报告。
4. 修改一条预期响应文本，确认 FAIL·响应内容。
5. 确认测试集导出时不会误保存 Authorization/Cookie 明文。

## Release checklist

- [ ] Release-prep PR 审查通过并合并到 main。
- [ ] GitHub Actions 在 release-prep PR 和 main 构建、测试及 JAR 校验通过。
- [ ] 本地 main 同步，JDK 21 下 `mvn package` 生成 `0.3.0` JAR。
- [ ] Windows `start-web.bat` 能正常打开页面，天气接口人工回归通过。
- [ ] 在上述检查完成后，由用户确认创建 Git Tag `v0.3.0` 和 GitHub Release。

> 注意：本文件为发布准备说明；创建正式 Tag 和 Release 之前不得将其视为已发布。
