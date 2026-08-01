# 原始需求与算法输入

本目录保存外部输入的只读副本：

- `测试软件需求V1.0.docx`：产品/测试需求基线。
- `requirement_mockup.png`：从 Word 内嵌对象提取的测试页草图。
- `protocol_frame_requirement.jpg`：CUP 批量线协议截图。
- `sqi_template_match.py`：模板匹配 SQI 参考实现。

## 审阅记录

- Word 已在 2026-07-30 渲染为 2 页并逐页检查。
- headless 渲染环境缺少部分中文字体，页面中文字出现缺字方框；正文另通过 DOCX 结构化
  提取逐段核对，界面草图可正常查看。
- 原文把 SQI 写成“百分制”同时又写“0～1”，规划中将其保留为待产品确认的显示问题。
- 原文“新增 soft_version、alg_version 三个字段”只列出两种 version；结合上下文按
  `sqi + soft_version + alg_version` 三项解释。
- SQI 脚本依赖 NumPy/SciPy，且引用的 full preprocessing/CPE 源码不在输入中。

## SHA-256

| 文件 | SHA-256 |
|---|---|
| `测试软件需求V1.0.docx` | `c5530d20bbd0e58d0cb82622dcf6c10d5d16860da491b11367236393a2eeab11` |
| `requirement_mockup.png` | `202eb52ff43ef797aaa8b43cee738da4879b448f776c62128cde58ab00fa5a77` |
| `protocol_frame_requirement.jpg` | `244975ef166f1bd19d66b9e8c52984f7db88de664ead7de7ae247bec62011816` |
| `sqi_template_match.py` | `ac69e4e3884bae9d655af0c117a0ab58e06987973b8cda9883839bc6db86e95c` |

不要为适配 Swift 直接修改上述原始文件。若外部输入更新，保留旧版本、登记新 hash，并
说明需求/protocol/algorithm version 的影响。

