# 09 · 文件存储

## 定位

文件是 Agent 的工作区，也是用户的知识库入口。元数据进 PostgreSQL，二进制进 MinIO。

边界：本模块管元数据与对象操作，不解析文件内容，不做向量化。

## 设计

| 项 | 规则 |
|---|---|
| 桶 | `liteworkbench`，启动时幂等创建 |
| 对象键 | `user/{userId}/{category}/{yyyyMMdd}/{fileId}/{fileName}` |
| 单文件上限 | 100MB |
| 下载地址 | 预签名 URL，有效期 15 分钟 |

分类：

| category | 用途 |
|---|---|
| `upload` | 用户主动上传 |
| `artifact` | Agent 产出的制品 |
| `log` | 归档日志 |

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/file/page` | 分页，支持 `sessionId` `category` |
| POST | `/api/v1/file/upload` | multipart 上传 |
| GET | `/api/v1/file/url/{fileId}` | 取预签名下载地址 |
| POST | `/api/v1/file/delete/{fileId}` | 删对象与元数据 |

## 关键流程

```
上传
  ├─ 1 校验大小（≤100MB）与文件名合法性
  ├─ 2 生成 fileId 与对象键
  ├─ 3 putObject 到 MinIO
  ├─ 4 落 wb_file 元数据
  └─ 5 对象写入失败抛 FILE_UPLOAD_FAILED；元数据写入失败则删对象后同抛

删除
  ├─ 1 删 MinIO 对象，失败抛 FILE_DELETE_FAILED（6004）
  └─ 2 逻辑删除 wb_file 元数据

下载
  └─ 取预签名 URL（15 分钟），前端直连 MinIO
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 预签名直连 | 大文件不穿过应用进程 | 多一次取 URL 往返 |
| 对象键带用户前缀与 fileId | 同名文件不互相覆盖，可按用户整体迁移 | 键较长 |
| 元数据与对象分离 | 列表查询快，不用打存储 | 可能不一致：先写对象后写元数据，删除先删对象再标记，失败留孤儿对象由清理任务兜 |
| 不做向量化 | 实现简单，依赖少 | 检索只能按名与分类 |

## 约束

- 单次上传不超过 100MB，超出抛 `FILE_TOO_LARGE`。
- 删除对象失败抛 `FILE_DELETE_FAILED`（6004），元数据删除失败记 error 并抛同码。
- 对象键禁止包含 `..` 与控制字符。
