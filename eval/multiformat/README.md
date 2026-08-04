# 多格式检索质量评测集

该评测集直接利用检索诊断候选，检查最终答案之外的解析和召回质量。

## 覆盖指标

| 类型 | 样本文档 | 硬性判定 |
|---|---|---|
| PDF 页码 | `quality-page.pdf` | 引用来源和检索候选都必须是第 2 页 |
| DOCX 标题 | `quality-heading.docx` | 标题路径必须保留为 `平台架构 > 故障恢复` |
| HTML 清洗 | `quality-clean.html` | 候选包含正文，且不得包含导航、推荐和广告词 |
| OCR 召回 | `quality-ocr.png` | 问题使用正确编码 `C-17`，样本固定保留识别型错误 `C-I7`，仍需命中编号与周期 |
| 表格关联 | `quality-table.html` | 表头、订单服务行及对应值必须出现在同一个候选分块 |

## 使用方式

1. 运行 `eval/multiformat/generate-fixtures.sh` 生成 PDF、DOCX 和 OCR PNG 样本。
2. 在同一个知识空间上传 `eval/multiformat/fixtures` 下的五个文件并等待索引完成。
3. 在“质量评测”中导入 `eval/multiformat/questions.json`。
4. 选择导入的五条题目，分别运行标准检索和增强检索。

`expectedRetrievalKeywords` 中的所有词必须在同一个候选分块出现。`forbiddenRetrievalKeywords` 会检查该来源的全部诊断候选，任一噪音词出现即失败。
