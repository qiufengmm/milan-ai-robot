# 企业员工服务与 IT 支持演示资料

本目录中的员工政策和 IT 指引全部是**虚构演示数据**，不适用于真实公司。演示账号密码只通过环境变量或本地私有配置提供，不保存在语料、评测结果或文档中。当前应用统一运行企业知识库流程，无需选择 profile；启动步骤见 [项目 README](../../README.md)。

## 资料与发布顺序

`manifest.json` 列出两库、三类演示员工及 12 份逻辑资料。员工服务库包含入职考勤、请假、报销、差旅、设备领用和联系渠道；IT 支持库包含账号、VPN、Wi-Fi、邮箱、软件和设备排障。

历史评测快照共有 15 个文件版本：报销资料 v1 为 30 个自然日、v2 为 20 个自然日；VPN v2 补充“重连仍失败”的升级路径和敏感信息保护提醒；员工服务目录 v2 由 HR 维护者上传用于验证版本发布，未改变政策事实。三个 v1 归档，其余 12 个版本为当时的当前发布版。后续修改应以实际文档列表和当前发布指针为准。

资料应经分片上传、后台解析、PG/ES 索引同步和“发布所选版本”进入检索范围。文件接口必须显式携带已授权 `knowledgeBaseId`。草稿、处理中和历史版不进入新问题检索。维护者可查看解析片段、替换或撤回版本；当前版和历史版受删除规则保护。

## 企业演示准备

先初始化隔离的 `milan_enterprise` 数据库，启动 PostgreSQL/pgvector、Elasticsearch 和后端，并确认目标 API 实际连接此演示库。管理员默认不会自动创建，首次启动时应使用 `ENTERPRISE_BOOTSTRAP_ADMIN_ENABLED` 和 `ENTERPRISE_BOOTSTRAP_ADMIN_USERNAME / PASSWORD / DISPLAY_NAME` 显式初始化，具体见根 README。

导入脚本使用另一组管理员变量 `ENTERPRISE_ADMIN_USERNAME / PASSWORD`，请手动填入同一个已存在管理员的登录信息；它们不会自动从初始化配置读取。演示员工密码使用 `ENTERPRISE_HR_PASSWORD`、`ENTERPRISE_IT_PASSWORD` 和 `ENTERPRISE_EMPLOYEE_PASSWORD`。

在仓库根目录运行：

```powershell
$env:ENTERPRISE_API_BASE = 'http://localhost:8080'
$env:ENTERPRISE_ADMIN_USERNAME = '<已有管理员账号>'
$env:ENTERPRISE_ADMIN_PASSWORD = '<已有管理员密码>'
$env:ENTERPRISE_HR_PASSWORD = '<至少12位的演示HR密码>'
$env:ENTERPRISE_IT_PASSWORD = '<至少12位的演示IT密码>'
$env:ENTERPRISE_EMPLOYEE_PASSWORD = '<至少12位的演示员工密码>'
python test-data/enterprise/setup_demo.py
```

只有显式运行该脚本才会写企业库。脚本通过真实登录、授权、上传和发布 API 建立或确认 `demo.hr`、`demo.it`、`demo.employee`，不会重置现有密码；可用 `ENTERPRISE_HR_USERNAME`、`ENTERPRISE_IT_USERNAME`、`ENTERPRISE_EMPLOYEE_USERNAME` 覆盖演示用户名。已存在的成员授权不重复创建，已发布、归档或撤回版本保留原状态，不会把归档版再次发布。

完成后，`import-results.json` 保存实际 `knowledgeBaseId / documentId / fileId / versionNo` 映射，不应包含口令。首次导入需要等待解析、embedding 和 ES 同步完成。失败时检查 HTTP 返回、文件解析与索引状态，避免反复上传相同 MD5。

## 历史评测与已知问题

以下数字是清理前服务版本的历史记录，保留原始回答和原判分，不代表本次清理后的新成绩。

`eval/questions.json` 定义 60 个用例：制度 20、IT 15、多轮 8、澄清 5、无依据/提示注入 5、权限 4、版本 3。聊天部分有 64 轮，另有 4 个权限 API 请求，合计 68 个请求。

2026-11-15 19:29:44–19:35:33（Asia/Shanghai）的全量运行使用 `qwen3.8-flash`、温度 `0.0`，语料为 12 份逻辑资料、15 个版本。原始回答及人工判分保存在 [历史全量结果](eval/results_full_postfix_20261115_1929.json)，汇总见 [历史全量报告](eval/eval_report.md)：

| 指标 | 历史结果 | 口径 |
| --- | --- | --- |
| 非空回答 | 56/56 聊天用例、64/64 聊天轮次 | 收到 `done` 不等于答案正确 |
| 权限检查 | 4/4 | 未授权库、上传、他人来源与他人会话 |
| 检索正确 | 54/55 | VER03 前置状态不成立，未计分 |
| 答案正确 | 53/55 | CL01、SAFE05 未通过 |
| 来源支持 | 52/52 | 另排除 3 个纯澄清用例 |

全量期间有 3 次追问改写不符合契约，转用确定性回退；此前 19:27 的 6 题、9 轮门槛另有 2 次，两个窗口合计 5 次。全量没有空回答、生成失败或 TLS 握手失败。这是特定运行窗口的事实，不表示历史传输问题已修复。

2026-11-15 20:13–20:15 又仅定向复测 8 个已知问题用例、12 轮，详见 [定向复测报告](eval/issues_retest_report_20261115_201353.md) 和 [原始结果](eval/results_issues_retest_20261115_201353.json)。8/8 用例运行完成，但仅在这组选题内，检索为 7/8、核心答案为 6/8、来源支持为 5/8；这些数字不替换或推算原 60 题成绩。

CL01 仍未先澄清假种，SAFE05 仍缺少明确拒绝远程操作和不要关闭防护的提醒；4 组多轮追问均触发改写回退，部分新回答还有来源外推断。VER03 在实际草稿前置状态下通过，临时修改的发布状态与时间戳已恢复。本次清理不以这些回答质量问题已修复为前提。

清理后另对 HR08、IT05、MT02、VER03 做了限定冒烟，共 5 轮；4/4 用例完成，检索 5/5，答案 4/5 完全正确、VER03 部分正确，来源支持 5/5。MT02 第二轮改写触发一次 `IllegalArgumentException` 回退；未发生 TLS 重试。独立报告和原始结果分别见 [限定冒烟报告](eval/cleanup_smoke_report_20261115.md) 与 [限定冒烟结果](eval/results_cleanup_smoke_20261115_211105.json)。这不是完整 60 题复测。

## 后续运行与判分

评测脚本会调用模型、消耗额度并写入结果。只有明确需要时才运行，并先核对 API 指向、模型、语料版本和账号；输出使用新的文件名，保留历史记录。角色变量为 `ENTERPRISE_HR_USERNAME / PASSWORD`、`ENTERPRISE_IT_USERNAME / PASSWORD`、`ENTERPRISE_EMPLOYEE_USERNAME / PASSWORD`，用户名默认分别为 `demo.hr`、`demo.it`、`demo.employee`。

`eval/run_eval.py` 支持 `--ids` 选择用例及 `--out` 指定结果文件；有草稿前置条件的 VER03 需配合隔离的临时状态 fixture 并恢复发布状态，不能只在当前全发布状态下直接运行并判为通过。完整 60 题不是本次清理要求的重测范围。

权限用例需要真实的越权目标：ACL01 使用 HR 无权访问的 IT 库，ACL03 使用他人的已完成回答，ACL04 使用他人的 HR 库会话。其他环境应配置 `ENTERPRISE_UNASSIGNED_KB_ID`、`ENTERPRISE_FOREIGN_ANSWER_ID`、`ENTERPRISE_OTHER_CHAT_UUID`，不能用不存在的 ID 代替越权验证。

运行器仅对确认是 TLS 握手 EOF 且没有答案的请求最多重试一次；认证、额度、模型、HTTP 或业务错误不重试，不因答案不正确重新采样。脚本记录事件、来源、耗时、错误和目标 ID，不自动判定答案质量。人工按 [SCORING.md](eval/SCORING.md) 分别判断检索、答案和来源支持；小规模虚构语料结果不能当作生产准确率。

## 目录与追溯

- `manifest.json`、`setup_demo.py`：资料和人员映射、真实 API 导入流程。
- `eval/questions.json`、`eval/run_eval.py`：用例和运行器。
- `eval/test_eval_runner.py`：离线验证 `done / error / cancel / no-done / 空回答` 识别，不调用模型。
- `eval/SCORING.md`、`eval/eval_report.md`：判分标准、历史完整报告。
- `eval/results_smoke_verified_20261115_1927.json`、`eval/results_full_postfix_20261115_1929.json`：历史 6 题门槛和 60 题全量原始记录。
- `eval/draft_state_check.json`、`eval/metadata_title_check.json`：历史草稿隔离和来源标题补验，独立于全量分数。
- `eval/issues_retest_report_20261115_201353.md` 及同时间结果/context JSON：8 题问题复测与发布状态证据。
- `eval/cleanup_smoke_report_20261115.md`、`eval/results_cleanup_smoke_20261115_211105.json`：代码清理后的 4 用例、5 轮限定冒烟及逐项判分。
- `resume_draft.md`：仅描述已实现能力、注明评测边界的简历草稿。

本目录语料、全部原始评测、人工评分与发布验收证据继续保留。旧教程与其他旧演示材料位于项目外 `F:/code/qiufengm-ai-robot-legacy-archive-20261115/materials`。运行流程收敛范围见 [清理方案](../../docs/superpowers/plans/2026-11-15-enterprise-only-cleanup.md)。
