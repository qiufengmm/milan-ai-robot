# Hybrid evaluation grading: A01–A30

按 `eval_questions.json` 的前 30 条记录（A01–A30）逐条核对。检索命中依据实际 `finalDocuments` 前三条；候选命中独立核对 fused candidates 前十条。答复评分只依据问题所问内容及其必要条件、例外和范围，不能由检索命中推导答复正确。

| 模式 | Retrieval hit | Candidate hit | Answer grade |
|---|---:|---:|---|
| Hybrid | 30/30 yes | 30/30 yes | 29 correct, 1 partially correct, 0 incorrect, 0 no_answer |
| Vector baseline | 30/30 yes | 30/30 yes | 29 correct, 1 partially correct, 0 incorrect, 0 no_answer |

## Notable miss

- **A14 (两种模式)**：回答了工作日 15:00 前现货当日发以及周末/预售顺延，但没有明确回答“15:00 后次日发”，因此判为 partially correct。相关发货文档已进入 final top 3，属于答复覆盖遗漏，不是检索缺失。

## Limitations

- 本报告只覆盖前 30 条记录 A01–A30；不代表剩余问题或总体性能。
- `retrievalHit` 仅表示 top 3 含有回答该问题所需的相关证据；`candidateHit` 表示 fused top 10 含相关证据。文档 ID 和标题列在 JSON 的 `evidenceIds`。
- 按用户要求未调用模型 API、未改动源数据或历史评测文件。
