# 03 · 工作台资产：技能与文件

## 定位

两类用户可维护的资产，共同点是"用户自己的、可增删改的复用单元"：技能沉淀固定流程，文件承载 Agent 工作区与上传原件。

边界：技能只管存储、检索与提示词注入，不执行技能内部流程；文件只管元数据与对象操作，不解析内容。

不回答：接口路径与请求 / 响应形状（见 docs/API-CONTRACT.md §10、§11、§14）；`wb_skill` / `wb_file` / `wb_skill_seed` 字段（见 docs/DATA-MODEL.md §5.5、§5.6、§5.12）；对象存储与桶的配置项（见 docs/DEPLOYMENT.md）；技能在系统提示里的装配时机与顺序（见 specs/01、specs/02）。

## 设计

### 技能：两段式渐进披露

| 部分 | 字段 | 注入时机 |
|---|---|---|
| 摘要 | `summary` | 常驻系统提示词 |
| 正文 | `content` | 命中 `trigger_words` 后展开 |

把所有技能正文常驻提示词会随技能数量线性膨胀，而绝大多数技能在一次对话里用不上，故分成两段。装配入口是 `SkillService.assembleSystemPrompt(userId, basePrompt, userInput, forcedCodes)`：`AgentProperties.skill.enabled` 关闭时原样返回；否则先列"可用技能"摘要清单，再对命中技能的正文追加 `[已展开技能：code]` 段落。命中两条路径：一是用户在输入框用 `/code` 显式点名（`forcedCodes`，前端发起时作为 `skillCodes` 传给 `ChatStreamREQ`），二是输入包含任一触发词（忽略大小写、按中英文逗号切分）。单次对话最多展开 3 个（`MAX_EXPANDED`），点名的技能优先占位；技能无触发词且未被点名，或未启用，则不参与展开。

| 字段 | 约束 |
|---|---|
| `skill_code` | 用户内唯一，`^[a-z][a-z0-9_]{1,63}$`（小写字母开头），重名抛 `SKILL_CODE_CONFLICT` |
| `skill_name` | 必填，不超过 128 字 |
| `summary` | 不超过 200 字 |
| `content` | 不超过 8000 字符，超出校验失败 |
| `trigger_words` | 逗号分隔，最多 20 个（服务层校验，超出抛 `PARAM_INVALID`），字段本身不超过 512 字 |
| `enabled` | 关闭则不参与匹配；新建默认 true |

**内置技能种子**：13 条定义存 `wb_skill_seed`（由 `db/data.sql` 按 `skill_code` upsert 写入，正文是操作手册而不是提示词），按创作工作台场景覆盖：基础五件套（`outline_first` 先纲后文 / `fact_cite` 取证引用 / `polish_pass` 三遍润色 / `data_brief` 数据简报 / `doc_digest` 长文摘要）+ 创作八件套（`novel_craft` 小说创作 / `content_pipeline` 内容工坊 / `image_prompt` 图像提示词 / `deep_research` 深度调研 / `copy_formula` 营销文案 / `humanize_pass` 去 AI 味 / `academic_translate` 学术翻译 / `hook_craft` 开头钩子）。种子表是平台级只读定义，用户副本在启动期由 `SkillService.seedBuiltinForUser` 复制进 `wb_skill`，按 `(userId, skill_code)` 全量判重（含已删行，Mapper `selectAnyIdByCode`）——用户改过的以用户为准，删过的不会在下次启动复活。内置技能与自建技能同模型、同表、同装配路径，不引入平台级"不可删"语义。

`wb_skill.builtin` 标记平台种子（启动补种时置位）。内置技能可改可删，页面列出"内置"标签与「恢复默认」入口：`POST /skill/restore/{skillId}` 只回写种子内容字段（`restoreBuiltin`），**保留用户的启用状态**，非内置技能拒绝恢复。

### 文件：元数据与对象分离

| 项 | 规则 |
|---|---|
| 桶 | 按工作区解析：`workspace_id` 命中其绑定桶；为空回落用户默认工作区（无工作区时懒建「默认工作区」并建桶）。桶名 `lwws-` + ULID 小写派生，全局唯一，不接受用户输入 |
| 平台主桶 | `liteworkbench.storage.bucket-name`（默认 `liteworkbench`），启动时幂等创建；只承载知识库文档原件等平台级对象，不归任何工作区 |
| 对象键 | `user/{userId}/{category}/{yyyyMMdd}/{fileId}/{fileName}` |
| `category` 段 | 固定为 `upload`（用户主动上传），分段结构保留以便按类别归档 |
| 单文件上限 | 100MB（超出抛 `FILE_TOO_LARGE`） |
| 下载地址 | 预签名 URL，有效期 15 分钟 |
| 文件名清洗 | 去路径分隔符与控制字符，超 200 字截断 |

元数据进 PG（`wb_file`，含 `workspace_id` 与 `bucket_name` 双列），二进制进 MinIO；对象键带用户前缀与 `fileId`，同名文件不互相覆盖，可按用户整体迁移。上传先写对象后写元数据，元数据失败按实际桶回删对象。

### 工作区：资产容器与桶绑定

工作区是用户级资产容器（表结构见 docs/DATA-MODEL.md §5.14），回答"一个用户的资产分几堆、各放哪个桶"：

| 项 | 规则 |
|---|---|
| 创建 | 名称用户内唯一；桶名由 ULID 派生，幂等 `makeBucket`；单用户上限 20 个 |
| 默认 | 首个工作区即默认；设默认先摘存量再置位（同事务）；默认工作区不可删 |
| 删除 | 删非默认工作区；删后无默认时最早创建者顶上，保证回落目标恒存在 |
| 解析 | `resolve(userId, workspaceId)`：空 id 回落默认，无任何工作区时懒建；会话 `workspace_id` 为空按默认归拢 |
| 隔离 | 上传与 `@` 引用按会话绑定的工作区取桶，工作区之间对象互不可见 |

输入框顶部即点即换模型与工作区（`MessageComposer`），切换即持久化到会话：下一条消息的模型参数与上传落桶按新绑定走。管理端「存储桶管理」（`monitor:storage:list` / `create` / `delete`）给管理员全平台桶清单（含对象数与占用）、建桶与删桶；平台主桶与 `lwws-` 前缀桶标记保护禁删，非空桶 MinIO 侧拒绝（转 `BUCKET_NOT_EMPTY`）。

## 契约

```java
public final class SkillService {

    /** 装配系统提示词：原提示词 + 启用技能摘要；点名或命中触发词的正文一并列出 */
    public String assembleSystemPrompt(String userId, String basePrompt, String userInput, List<String> forcedCodes);
}

public final class FileService {

    /** 预签名下载地址，前端直连 MinIO */
    public String presignedUrl(String userId, String fileId);

    /** 读取文本类文件正文供 @ 引用注入，非文本类型返回 null；超长按字符数截断 */
    public String readText(String userId, String fileId, int maxChars);
}
```

接口路径与出入参见 docs/API-CONTRACT.md §10、§11、§14（技能、文件、工作区与存储）。

## 关键流程

```
技能命中
  ├─ 1 skill.enabled 关闭 → 原样返回 basePrompt
  ├─ 2 加载全部 enabled 技能，拼「可用技能」摘要清单（跳过为空时只返回原提示）
  ├─ 3 forcedCodes 点名命中 → 优先展开对应正文，最多 3 个
  ├─ 4 其余技能按触发词命中 → 补足到 3 个
  └─ 5 都没命中 → 只有摘要，正文不占用上下文

文件上传
  ├─ 1 校验非空与大小（≤100MB）
  ├─ 2 生成 fileId 与对象键 → putObject 到 MinIO；失败抛 FILE_UPLOAD_FAILED
  ├─ 3 落 wb_file 元数据；写入失败则删对象后同抛 FILE_UPLOAD_FAILED
  └─ 4 返回元数据 VO

文件删除
  ├─ 1 归属校验（查不到即 FILE_NOT_FOUND）
  ├─ 2 删 MinIO 对象，失败抛 FILE_DELETE_FAILED
  └─ 3 逻辑删 wb_file 元数据

文本文件引用注入
  ├─ 1 按 (fileId, userId) 取元数据，查不到抛 FILE_NOT_FOUND
  ├─ 2 非文本类型（内容类型与扩展名都不在白名单）→ 返回 null，注入侧给出「无法读取正文」提示
  └─ 3 流式读取并截断到上限，交给 RunRefService 拼进系统提示
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 技能两段式披露 | 上下文占用与技能数量解耦 | 触发词没写好会漏召 |
| 触发词简单包含匹配 | 零依赖、可预测 | 无法做语义召回 |
| 技能存库而非文件 | 页面可直接增删 | 版本管理交给 `updated_at` |
| 不做技能内子流程编排 | 实现简单，行为可预测 | 复杂流程需拆成多个技能 |
| 预签名直连 MinIO | 大文件不穿过应用进程 | 多一次取 URL 往返 |
| 元数据与对象分离 | 列表查询快，不打存储 | 可能不一致，失败留孤儿对象由清理任务兜 |
| 对象键固定 category 段 | 键结构稳定，可按前缀清扫孤儿 | 引入新类别要改键规则 |
| 文件不做向量化 | 实现简单，依赖少 | 检索只能按名与分类 |
| 技能种子单独成表 | 种子内容随版本升级自动回写，恢复默认不必回读 Java 常量 | 多一张平台级只读表 |
| 文本文件按需读正文注入 | `@` 引用即刻可用，不依赖模型主动读文件 | 大文件要截断，二进制给不出内容 |

## 约束

- 技能正文不得超过 8000 字符；技能内容不得包含密钥等敏感信息。
- 单次上传不超过 100MB（超出抛 `FILE_TOO_LARGE`）；对象键禁止包含 `..` 与控制字符。
- 删除对象失败抛 `FILE_DELETE_FAILED`，元数据删除失败记 error 并抛同码。
- 两类资产的查询一律带 `user_id`，单资源按 `(主键, user_id)` 查；`wb_skill_seed` 是平台级只读定义，不参与归属过滤。
- 技能种子只由 `db/data.sql` 写入，业务代码只读；页面上的「恢复默认」把种子内容回写到用户副本，用户的启用状态保持不变。
