-- LiteWorkbench 平台级种子数据：工具、内置技能、内置角色、菜单与权限点、角色授权
-- 执行顺序由 spring.sql.init 保证：schema.sql 建表在前，本脚本写数在后；本脚本必须可重复执行
-- 不变式：① 种子行一律带固定 ID（菜单 MENU_+26 位序号、角色 ROLE_+26 位序号、工具 TOOL_+26 位序号），
--         ② 结构字段随版本回写，运营字段（visible / state / enabled）只在新建时取种子值，
--         ③ 与种子冲突但 ID 不在种子内的历史行一律软删，避免升级后出现重复菜单

-- ============================== 一、内置工具 ==============================
-- 固定 ID 而非 ULID：upsert 幂等重放，重启不产生重复行，标签与风险等级变更随版本回写
INSERT INTO wb_tool (tool_id, tool_name, tool_label, category, risk_level, enabled)
VALUES
    ('TOOL_00000000000000000000000001', 'python_exec', 'Python 执行', 'runtime', 'confirm', true),
    ('TOOL_00000000000000000000000002', 'file_read', '读取文件', 'file', 'safe', true),
    ('TOOL_00000000000000000000000003', 'file_write', '写入文件', 'file', 'confirm', true),
    ('TOOL_00000000000000000000000004', 'http_get', 'HTTP 请求', 'network', 'safe', true),
    ('TOOL_00000000000000000000000005', 'kb_search', '知识库检索', 'knowledge', 'safe', true),
    ('TOOL_00000000000000000000000006', 'task_plan', '任务规划', 'agent', 'safe', true),
    ('TOOL_00000000000000000000000007', 'memory_search', '记忆检索', 'agent', 'safe', true),
    ('TOOL_00000000000000000000000008', 'memory_write', '记忆写入', 'agent', 'confirm', true),
    ('TOOL_00000000000000000000000009', 'task_delegate', '子任务委派', 'agent', 'confirm', false)
ON CONFLICT (tool_id) DO UPDATE SET
    tool_label = EXCLUDED.tool_label,
    category = EXCLUDED.category,
    risk_level = EXCLUDED.risk_level;

-- ============================== 二、内置技能种子 ==============================
-- 内容是操作手册而非提示词：摘要常驻系统提示，命中触发词（或用户在输入里显式点名 /code）才展开正文
-- 正文随版本回写，用户对自有 wb_skill 行的改动不受影响
INSERT INTO wb_skill_seed (skill_code, skill_name, summary, content, trigger_words, sort_no)
VALUES
    ('outline_first', '先纲后文',
     '写作前先产出大纲并与用户对齐，再逐节展开成文',
     '目标：避免直接开写导致结构失控。
步骤：
1. 先用 task_plan 列出写作计划（提纲 → 确认 → 逐节成文 → 通读校对）。
2. 产出一版大纲：一级标题 + 每节要回答的一个问题，不超过 8 节。
3. 用户确认前不写正文；用户直接给全文任务时，先给大纲再在同一次回答里成文。
4. 成文时每节围绕该节的那一个问题，跑题的素材舍弃。
输出：大纲用有序列表；正文用 Markdown 二级标题分节。',
     '大纲,提纲,结构,outline,写一篇文章,写篇文章', 1),
    ('fact_cite', '取证引用',
     '涉及用户资料时先检索知识库并标注来源，禁止编造',
     '目标：所有关于用户资料的断言都可溯源。
步骤：
1. 提炼问题里的可检索关键词（人名、产品名、数字、术语），用 kb_search 检索，必要时换 2 到 3 组关键词。
2. 只依据检索返回的片段作答；每个关键断言后标注来源，格式：（来源：文档名 · 块 3）。
3. 检索不到或片段不足以支撑结论时，明确说「资料中没有找到」，并说明缺什么。
禁止：把常识包装成来自用户资料的结论；拼接不同文档的片段制造伪出处。',
     '引用,出处,来源,资料,依据,考证,知识库里有', 2),
    ('polish_pass', '三遍润色',
     '按结构、节奏、用词三遍润色，保持原意与事实不变',
     '目标：改善表达质量，不改变作者的观点与事实内容。
步骤：
1. 第一遍看结构：段落顺序、逻辑衔接、开头是否拖沓，调整而不重写。
2. 第二遍看节奏：拆长句、删冗余词、统一语气与人称。
3. 第三遍看用词：替换含糊表述，专有名词与数字必须原样保留。
输出：先给润色后全文，再用不超过 5 条列表说明改了什么、为什么改。原文中的数字、名称、引用一律不改。',
     '润色,改写,优化文笔,polish,改一下文字', 3),
    ('data_brief', '数据简报',
     '用 python_exec 计算并校验数字，输出表格化简报',
     '目标：让每个数字都可复算。
步骤：
1. 用 python_exec 做计算与汇总，脚本一次算完并打印全部中间量，便于核对。
2. 对关键结论做一次反向校验（如合计、占比归一），对不上就回查脚本。
3. 输出：结论（不超过 3 条）→ Markdown 表格（原始数据或汇总）→ 计算口径说明。
禁止：口算后直接给数；单位与百分比必须写清；样本量不足时说明可信度。',
     '数据,统计,汇总,报表,占比,环比,同比', 4),
    ('doc_digest', '长文摘要',
     '读全文后按固定结构产出摘要，保留关键结论与数字',
     '目标：摘要能替代原文做决策，而不是目录复述。
步骤：
1. 长文在工作区时先用 file_read 读取；在知识库里时用 kb_search 取相关块。
2. 按四段输出：一句话结论 / 关键要点（不超过 5 条）/ 关键数字与日期 / 风险与未决问题。
3. 每条要点注明出自原文的位置；原文没有的内容不写。
长度：默认不超过 400 字，用户给定时以其为准。',
     '摘要,总结一下,digest,概括,读一下这篇', 5),
    ('novel_craft', '小说创作',
     '人物卡、世界观、章节大纲三件套先行，伏笔回收、状态连续、章尾留钩',
     '目标：长篇创作不跑偏、不烂尾、不战力崩坏。
步骤：
1. 动笔前先立三件套：人物卡（姓名/身份/性格/动机/关系/成长弧光）、世界观设定表（时代/势力/规则）、章节大纲（每章一个事件 + 一个悬念）。
2. 每章围绕大纲写，正文默认 2000 到 2500 字；字数不够时补心理独白、环境渲染、动作细节，禁止重复对话注水。
3. 逻辑闭环：前文伏笔必须回收；人物状态连续（受伤要恢复、获得物品要使用）；主角成长要有过程，反派不降智。
4. 章尾留钩子（新危机/新目标/反转），章节标题带悬念；视角与人称全程统一。
输出：先大纲后正文；正文用 Markdown 二级标题分章。',
     '小说,写小说,章节,续写,人物设定,世界观,网文,novel', 6),
    ('content_pipeline', '内容工坊',
     '写作、编辑、多平台改编、标题公式四步流水线，空话与套话零容忍',
     '目标：从素材到多平台成品，一条流水线走完，质量不靠运气。
步骤：
1. 写作：从读者的痛点切入而非主题本身；一个段落只讲一件事；具体打败抽象（用数字与事实，不用形容词堆砌）。
2. 编辑：第一遍砍 30% 篇幅，删套话与被动语态；第二遍统一语气与人称；第三遍只改错别字与标点。
3. 改编：一篇源稿改写成社交短帖、邮件、口播稿时，只换包装不换核心观点；每平台按原生语感写，不做同一段话的复制。
4. 标题：按公式产出 10 个以上候选（数字利益 / 提问 / 反常识 / 具体结果 / 警告），按预估吸引力排序并说明前 3 名理由。
禁止：exciting to announce、game-changer、赋能、抓手等空话；任何放之四海皆准的句子。',
     '写文章,文章,公众号,撰稿,内容，改编,文案,newsletter', 7),
    ('image_prompt', '图像提示词',
     '按主体、场景、风格、构图、光影、色调六维组装图像提示词，先草稿后终稿',
     '目标：把一句模糊的想象变成模型能稳定执行的图像提示词。
步骤：
1. 拆解需求：主体（谁/什么，含姿态与表情）+ 场景（环境/时代/天气）+ 风格（摄影/插画/3D/水彩，附参考艺术家或年代）+ 构图（景别/视角/三分法）+ 光影（方向/软硬/时段）+ 色调（主色与对比色）。
2. 组装顺序：主体描述 → 环境 → 风格与媒介 → 构图与镜头 → 光影 → 色彩 → 画质词（8k、高细节按需，不堆砌）。
3. 负面约束：明确不要的元素（多余肢体、文字水印、畸变）单独一行。
4. 迭代：先出 1K 草稿确认构图与风格，锁定后再提分辨率出终稿；每次只改一个变量。
输出：中文需求 → 结构化中文提示词 + 英文提示词各一版；参数（尺寸/张数）随请求给出。',
     '画图,画一张,生成图片,配图,插图,封面图,文生图,image,prompt', 8),
    ('deep_research', '深度调研',
     '先定调研大纲再逐条检索，一手来源优先，每个结论附证据',
     '目标：调研结论可复算、可溯源，不是网上信息的拼贴。
步骤：
1. 先定大纲再动手：把问题拆成 3 到 7 个调研条目，每条给出检索关键词与要回答的问题；与用户确认后再执行。
2. 逐条检索：每个条目至少换一组关键词交叉验证；优先一手来源（官方文档、论文、财报），二手来源只作线索。
3. 证据入账：每个关键结论附来源链接与摘录；不同来源冲突时并列呈现并说明取舍理由。
4. 成文结构：一句话结论 / 分条目展开（问题 → 证据 → 判断）/ 数据表格 / 局限与未决问题。
禁止：无来源的数字；把行业报告的观点包装成事实；检索不到时说明「资料中没有找到」。',
     '调研,研究一下,竞品,行业分析,market research,尽调', 9),
    ('copy_formula', '营销文案',
     'FAB 挖卖点，AIDA/PAS/4U 选结构，标题先行、CTA 单一明确',
     '目标：文案为转化服务，每一句都在推动下一步行动。
步骤：
1. 先挖再写：列出目标人群、核心痛点、产品卖点（功能 → 优势 → 利益，FAB），卖点不超过 3 个。
2. 选结构：AIDA（注意-兴趣-欲望-行动）适合落地页；PAS（痛点-放大-方案）适合短视频与社交；4U（独特/有用/紧迫/极致具体）适合标题。
3. 标题先行：主标题 + 5 个备选，用具体数字与场景，不用形容词自夸。
4. 正文与 CTA：每段一个卖点配一个证据；结尾行动指令单一明确（做什么、得到什么、为什么是现在）。
禁止：虚假限时与夸大承诺；违禁词；没有证据的「第一/最」表述。',
     '营销,推广,广告,卖点,电商,详情页,小红书,投放', 10),
    ('humanize_pass', '去 AI 味',
     '识别并修复 24 类机器写作模式，长短句交替，注入真实态度',
     '目标：去掉机器写作味，让文字像人写的，同时不损失信息。
步骤：
1. 识别 AI 腔并修复：删「赋能/抓手/深耕/综上所述/值得一提的是」；拆「不仅…而且…」三段式排比；去掉句尾硬加的 -ing 深度点评；把「至关重要/里程碑」降格为事实陈述。
2. 松绑句式：长短句交替；把排比三件套改成两件或一件；被动语态改主动；删空洞的模糊归因（「专家认为/业内人士表示」）。
3. 注入人味：允许第一人称与真实态度；保留具体细节与数字；承认复杂性与不确定性。
4. 自检：朗读一遍，像人对朋友说话就过关；任何放之四海皆准的句子删掉重写。
禁止：把改写变成同义词替换游戏；改变原文事实与观点。',
     'AI味,ai味,太机器,像人写的,humanize,去机器人味', 11),
    ('academic_translate', '学术翻译',
     '直译、反思、雅化三步走，公式与引用零损伤，术语全文统一',
     '目标：论文中英互译，术语准、公式零损伤、符合投稿规范。
步骤：
1. 直译：逐句对应，公式（$...$、equation 环境）、引用（\cite{}、\ref{}）、数字、算法名、数据集名原样保留；术语按标准译法，未收录的保留原文并括号附译。
2. 反思：全文统一术语译法；消除 Chinglish（如 in recent years 滥用）；删除「在本文中我们」式套话；表格只译 caption，表头数据不动。
3. 雅化（投稿场景）：utilize→use，demonstrate→show；按目标会议风格调整句式；逻辑连接词补到位。
输出：默认给译文 + 术语对照表；用户要求双栏时给中英对照版。
禁止：翻译公式与引用键；自行发挥原文没有的解释；商务与口语文本不走本流程。',
     '论文,翻译,中译英,英译中,学术,academic,translate', 12),
    ('hook_craft', '开头钩子',
     '标题公式十个候选，开头一句话留人，黄金三章节奏表',
     '目标：前三章/前三秒留人，标题与开头就是转化率。
步骤：
1. 标题：按公式给 10 个候选——数字利益、提问、反常识、具体结果、警告、秘密感；标注每个的钩子类型。
2. 开头：第一句只做一件事——让人必须读第二句；用场景、冲突或反常事实切入，禁止「随着社会的发展」式开头。
3. 结构：黄金三章——第 1 章冲突亮相，第 2 章升级加码，第 3 章转折留悬念；每章结尾预留下一段的行动理由。
4. 检查：删掉所有不推进情节/不提供信息的段落；把总结段改成下一个问题的开始。
输出：标题清单 + 开头两个版本（场景版/冲突版）+ 三章节奏表。',
     '开头,标题,钩子,黄金三章,第一章,怎么写吸引人', 13)
ON CONFLICT (skill_code) DO UPDATE SET
    skill_name = EXCLUDED.skill_name,
    summary = EXCLUDED.summary,
    content = EXCLUDED.content,
    trigger_words = EXCLUDED.trigger_words,
    sort_no = EXCLUDED.sort_no,
    updated_at = now();

-- ============================== 三、内置角色 ==============================
-- 固定 ID + 按 role_code 冲突更新：老库已有角色沿用原 ID，新建库用固定 ID，角色被误删后重建仍是同一套 ID
INSERT INTO sys_role (role_id, role_code, role_name, sort_no, state, created_by, updated_by)
VALUES
    ('ROLE_00000000000000000000000001', 'super_admin', '超级管理员', 0, 'normal', 'system', 'system'),
    ('ROLE_00000000000000000000000002', 'admin', '平台管理员', 1, 'normal', 'system', 'system'),
    ('ROLE_00000000000000000000000003', 'user', '普通用户', 2, 'normal', 'system', 'system')
ON CONFLICT (role_code) WHERE deleted = false DO UPDATE SET
    role_name = EXCLUDED.role_name,
    sort_no = EXCLUDED.sort_no,
    updated_by = 'system',
    updated_at = now();

-- ============================== 四、菜单与权限点 ==============================
-- 种子先入临时表：裁剪与 upsert 共用同一份定义，避免两处清单不同步
-- 顺序不能颠倒：先软删"路由或权限码与种子冲突、但 ID 不在种子里"的历史行，否则 upsert 会撞 uk_sys_menu_perm
DROP TABLE IF EXISTS lw_menu_seed;
CREATE TEMP TABLE lw_menu_seed (
    seq        int,
    parent_seq int,
    menu_name  varchar(64),
    menu_type  varchar(16),
    route_path varchar(128),
    component  varchar(128),
    icon       varchar(64),
    perm_code  varchar(128),
    sort_no    int
);
INSERT INTO lw_menu_seed (seq, parent_seq, menu_name, menu_type, route_path, component, icon, perm_code, sort_no)
VALUES
    (1,   0,  'menu.dashboard',              'menu',   '/dashboard',    'Dashboard',              'dashboard',  NULL,                       10),
    (2,   0,  'menu.console',                'menu',   '/console',      'console/Console',        'chat',       NULL,                       20),
    (3,   0,  'menu.files',                  'menu',   '/files',        'files/FileList',         'folder',     NULL,                       30),
    (4,   0,  'menu.skills',                 'menu',   '/skills',       'skills/SkillList',       'bulb',       NULL,                       40),
    (45,  0,  'menu.kb',                     'menu',   '/kb',           'kb/KbCenter',            'book',       NULL,                       45),
    (46,  0,  'menu.writer',                 'menu',   '/writer',       'writer/WriterStudio',    'edit',       NULL,                       35),
    (49,  0,  'menu.runs',                   'menu',   '/runs',         'runs/RunConsole',        'history',    NULL,                       25),
    (5,   0,  'menu.logs',                   'menu',   '/logs',         'logs/LogSearch',         'document',   'monitor:log:list',         50),
    (6,   0,  'menu.monitor',                'menu',   '/monitor',      'monitor/HealthPanel',    'pulse',      'monitor:health:list',      60),
    (7,   0,  'menu.showcase',               'menu',   '/showcase',     'showcase/Showcase',      'palette',    NULL,                       70),
    (8,   0,  'menu.settings',               'menu',   '/settings',     'settings/Settings',      'gear',       NULL,                       80),
    (10,  0,  'menu.groupSystem',            'dir',    '/system',       NULL,                     'setting',    NULL,                       90),
    (11,  10, 'menu.systemUserManage',       'menu',   '/system/user',  'system/UserList',        'user',       'system:user:list',         91),
    (12,  11, 'menu.systemUserCreate',       'button', NULL,            NULL,                     NULL,         'system:user:create',       1),
    (13,  11, 'menu.systemUserUpdate',       'button', NULL,            NULL,                     NULL,         'system:user:update',       2),
    (14,  11, 'menu.systemUserDelete',       'button', NULL,            NULL,                     NULL,         'system:user:delete',       3),
    (15,  11, 'menu.systemUserReset',        'button', NULL,            NULL,                     NULL,         'system:user:reset',        4),
    (20,  10, 'menu.systemRoleManage',       'menu',   '/system/role',  'system/RoleList',        'shield',     'system:role:list',         92),
    (21,  20, 'menu.systemRoleCreate',       'button', NULL,            NULL,                     NULL,         'system:role:create',       1),
    (22,  20, 'menu.systemRoleUpdate',       'button', NULL,            NULL,                     NULL,         'system:role:update',       2),
    (23,  20, 'menu.systemRoleDelete',       'button', NULL,            NULL,                     NULL,         'system:role:delete',       3),
    (24,  20, 'menu.systemRoleGrant',        'button', NULL,            NULL,                     NULL,         'system:role:grant',        4),
    (30,  10, 'menu.systemMenuManage',       'menu',   '/system/menu',  'system/MenuList',        'list',       'system:menu:list',         93),
    (31,  30, 'menu.systemMenuCreate',       'button', NULL,            NULL,                     NULL,         'system:menu:create',       1),
    (32,  30, 'menu.systemMenuUpdate',       'button', NULL,            NULL,                     NULL,         'system:menu:update',       2),
    (33,  30, 'menu.systemMenuDelete',       'button', NULL,            NULL,                     NULL,         'system:menu:delete',       3),
    (50,  6,  'menu.monitorPythonReprobe',   'button', NULL,            NULL,                     NULL,         'monitor:python:reprobe',   1),
    (52,  6,  'menu.monitorObserveReset',    'button', NULL,            NULL,                     NULL,         'monitor:observe:reset',    2),
    (51,  5,  'menu.logsGlobal',             'button', NULL,            NULL,                     NULL,         'monitor:log:global',       1),
    (59,  4,  'menu.skillsList',             'button', NULL,            NULL,                     NULL,         'wb:skill:list',            0),
    (60,  4,  'menu.skillsCreate',           'button', NULL,            NULL,                     NULL,         'wb:skill:create',          1),
    (61,  4,  'menu.skillsUpdate',           'button', NULL,            NULL,                     NULL,         'wb:skill:update',          2),
    (62,  4,  'menu.skillsDelete',           'button', NULL,            NULL,                     NULL,         'wb:skill:delete',          3),
    (70,  3,  'menu.filesDelete',            'button', NULL,            NULL,                     NULL,         'wb:file:delete',           1),
    (75,  45, 'menu.kbList',                 'button', NULL,            NULL,                     NULL,         'wb:kb:list',               0),
    (76,  45, 'menu.kbCreate',               'button', NULL,            NULL,                     NULL,         'wb:kb:create',             1),
    (77,  45, 'menu.kbUpdate',               'button', NULL,            NULL,                     NULL,         'wb:kb:update',             2),
    (78,  45, 'menu.kbDelete',               'button', NULL,            NULL,                     NULL,         'wb:kb:delete',             3),
    (79,  45, 'menu.kbUpload',               'button', NULL,            NULL,                     NULL,         'wb:kb:upload',             4),
    (80,  45, 'menu.kbSearch',               'button', NULL,            NULL,                     NULL,         'wb:kb:search',             5),
    (90,  8,  'menu.settingsToolList',       'button', NULL,            NULL,                     NULL,         'wb:tool:list',             1),
    (91,  8,  'menu.settingsToolToggle',     'button', NULL,            NULL,                     NULL,         'wb:tool:toggle',           2),
    (92,  8,  'menu.settingsToolInvoke',     'button', NULL,            NULL,                     NULL,         'wb:tool:invoke',           3),
    (93,  8,  'menu.settingsProviderList',   'button', NULL,            NULL,                     NULL,         'wb:provider:list',         4),
    (94,  8,  'menu.settingsProviderCreate', 'button', NULL,            NULL,                     NULL,         'wb:provider:create',       5),
    (95,  8,  'menu.settingsProviderUpdate', 'button', NULL,            NULL,                     NULL,         'wb:provider:update',       6),
    (96,  8,  'menu.settingsProviderDelete', 'button', NULL,            NULL,                     NULL,         'wb:provider:delete',       7),
    (120, 46, 'menu.writerList',             'button', NULL,            NULL,                     NULL,         'wb:doc:list',              0),
    (121, 46, 'menu.writerCreate',           'button', NULL,            NULL,                     NULL,         'wb:doc:create',            1),
    (122, 46, 'menu.writerUpdate',           'button', NULL,            NULL,                     NULL,         'wb:doc:update',            2),
    (123, 46, 'menu.writerDelete',           'button', NULL,            NULL,                     NULL,         'wb:doc:delete',            3),
    (124, 46, 'menu.writerSearch',           'button', NULL,            NULL,                     NULL,         'wb:doc:search',            4),
    -- 工作区：用户级资产容器，与 MinIO 桶一一绑定，登录即可用
    (44,  0,  'menu.workspaces',             'menu',   '/workspaces',  'settings/WorkspacePanel', 'box',       NULL,                       45),
    -- 存储桶管理（管理员）：顶级页面 + 监控分组下的按钮权限点；顶级菜单不带 perm_code，权限由按钮点控制
    (101, 0,  'menu.storageManage',          'menu',   '/storage',      'monitor/StoragePanel',    'database',   NULL,                       65),
    (97,  6,  'menu.monitorStorageList',     'button', NULL,            NULL,                     NULL,         'monitor:storage:list',     5),
    (98,  6,  'menu.monitorStorageCreate',   'button', NULL,            NULL,                     NULL,         'monitor:storage:create',   6),
    (99,  6,  'menu.monitorStorageDelete',   'button', NULL,            NULL,                     NULL,         'monitor:storage:delete',   7),
    -- 日志导出：按钮权限由前端 hasPerm 判定、接口侧 SaCheckPermission 兜底，不占 perm_code 行
    (100, 5,  'menu.logsExport',             'button', NULL,            NULL,                     NULL,         NULL,                       4);

-- 历史残留清理：与本次种子同路由或同权限码、且 ID 不在种子内的行软删，重复菜单在升级后不再出现
-- 两条判据缺一不可：判据取临时表而非已入库的种子行（新版本新增的菜单也要能裁掉老库同名冲突行）；
-- 同时排除种子 ID 本身，否则升级首跑会把老库里的种子行（seeded 尚为 false）连同菜单树一起软删
UPDATE sys_menu m
SET deleted = true, updated_by = 'system', updated_at = now()
WHERE m.deleted = false
  AND m.seeded = false
  AND m.menu_id NOT IN (SELECT 'MENU_' || lpad(t.seq::text, 26, '0') FROM lw_menu_seed t)
  AND EXISTS (
      SELECT 1 FROM lw_menu_seed t
      WHERE (t.route_path IS NOT NULL AND t.route_path = m.route_path)
         OR (t.perm_code IS NOT NULL AND t.perm_code = m.perm_code)
  );

-- 已下线菜单：不在种子里，冲突裁剪覆盖不到，按固定 ID 软删并摘除授权
-- seq 47 / 130-133 为提示词模块（能力并入技能），48 / 140-144 为更早下线的菜单
UPDATE sys_menu SET deleted = true, updated_by = 'system', updated_at = now()
WHERE deleted = false
  AND menu_id IN (
      'MENU_00000000000000000000000047',
      'MENU_00000000000000000000000130',
      'MENU_00000000000000000000000131',
      'MENU_00000000000000000000000132',
      'MENU_00000000000000000000000133',
      'MENU_00000000000000000000000048',
      'MENU_00000000000000000000000140',
      'MENU_00000000000000000000000141',
      'MENU_00000000000000000000000142',
      'MENU_00000000000000000000000143',
      'MENU_00000000000000000000000144'
  );
DELETE FROM sys_role_menu
WHERE menu_id IN (
    'MENU_00000000000000000000000047',
    'MENU_00000000000000000000000130',
    'MENU_00000000000000000000000131',
    'MENU_00000000000000000000000132',
    'MENU_00000000000000000000000133',
    'MENU_00000000000000000000000048',
    'MENU_00000000000000000000000140',
    'MENU_00000000000000000000000141',
    'MENU_00000000000000000000000142',
    'MENU_00000000000000000000000143',
    'MENU_00000000000000000000000144'
);

-- 种子 upsert：结构字段全量回写；visible / state 只在新建时取种子值，已存在的行不被覆盖
INSERT INTO sys_menu (menu_id, parent_id, menu_name, menu_type, route_path, component, icon, perm_code,
                      sort_no, visible, state, seeded, created_by, updated_by)
SELECT
    'MENU_' || lpad(t.seq::text, 26, '0'),
    CASE WHEN t.parent_seq = 0 THEN '0' ELSE 'MENU_' || lpad(t.parent_seq::text, 26, '0') END,
    t.menu_name, t.menu_type, t.route_path, t.component, t.icon, t.perm_code, t.sort_no,
    true, 'normal', true, 'system', 'system'
FROM lw_menu_seed t
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    menu_name = EXCLUDED.menu_name,
    menu_type = EXCLUDED.menu_type,
    route_path = EXCLUDED.route_path,
    component = EXCLUDED.component,
    icon = EXCLUDED.icon,
    perm_code = EXCLUDED.perm_code,
    sort_no = EXCLUDED.sort_no,
    seeded = true,
    updated_by = 'system',
    updated_at = now();

DROP TABLE lw_menu_seed;

-- ============================== 五、角色授权 ==============================
-- 超管持有全部菜单；平台管理员持有除角色管理（特权来源）外的全部菜单；普通用户只拿工作台子集
INSERT INTO sys_role_menu (role_id, menu_id, created_by)
SELECT r.role_id, m.menu_id, 'system'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.role_code = 'super_admin' AND r.deleted = false AND m.deleted = false
ON CONFLICT (role_id, menu_id) DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id, created_by)
SELECT r.role_id, m.menu_id, 'system'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.role_code = 'admin' AND r.deleted = false AND m.deleted = false
  AND (m.perm_code IS NULL OR m.perm_code NOT LIKE 'system:role:%')
ON CONFLICT (role_id, menu_id) DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id, created_by)
SELECT r.role_id, m.menu_id, 'system'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.role_code = 'user' AND r.deleted = false AND m.deleted = false
  AND m.menu_name IN (
      'menu.dashboard', 'menu.console', 'menu.runs', 'menu.files', 'menu.skills', 'menu.kb', 'menu.settings',
      'menu.showcase', 'menu.writer', 'menu.logs', 'menu.workspaces', 'menu.logsExport',
      'menu.writerList', 'menu.writerCreate', 'menu.writerUpdate', 'menu.writerDelete', 'menu.writerSearch',
      'menu.filesDelete',
      'menu.skillsList', 'menu.skillsCreate', 'menu.skillsUpdate', 'menu.skillsDelete',
      'menu.kbList', 'menu.kbCreate', 'menu.kbUpdate', 'menu.kbDelete', 'menu.kbUpload', 'menu.kbSearch'
  )
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 历史库回收：平台管理员若曾持有角色管理授权，按特权划分摘除
DELETE FROM sys_role_menu rm
USING sys_role r, sys_menu m
WHERE rm.role_id = r.role_id
  AND rm.menu_id = m.menu_id
  AND r.role_code = 'admin'
  AND m.perm_code LIKE 'system:role:%';
