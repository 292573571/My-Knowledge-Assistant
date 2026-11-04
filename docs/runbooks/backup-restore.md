# 数据备份与恢复

PostgreSQL 里的东西（用户、空间、文档索引、切片、笔记、会话、Wiki、评测题）**不可重建**，
Chroma 向量和解析结果可以重建。所以备份的第一优先级是数据库，其次是上传的原始文件。

备份由服务器上的 `/usr/local/sbin/deploy-shihai backup` 完成，以 root 运行，自带恢复演练。

## 备份内容

| 内容 | 来源 | 是否关键 | 说明 |
|---|---|---|---|
| PostgreSQL 全库 | `docker exec my-pgvector pg_dump -Fc` | **关键** | schema `my_knowledge_assistant` 下 39 张表 |
| 上传文件 | `/opt/shihai/docs`（workspaces、learning-records） | **关键** | 原始文件，重建索引的唯一来源 |
| 用户资产 | `/opt/shihai/data`（avatars、eval-imports） | 重要 | 头像、评测导入件 |
| 环境变量 | `/etc/shihai/shihai.env` | 重要 | 含数据库口令与模型密钥，包内权限 600 |
| Chroma 向量库 | Docker 卷 `zhihai_chroma_data` | 可重建 | 占包体约 87M；`BACKUP_INCLUDE_CHROMA=0` 可跳过 |

产物：`/opt/shihai/backups/data/shihai-backup-<UTC时间>.tar.gz`，同目录有 `.sha256`，
包内 `manifest.txt` 记录时间、版本、库大小、包含项，`SHA256SUMS` 是各分件校验和。
目录权限 700、包 600（含口令，不要外传）。

## 日常操作

```bash
# 立即备份（含恢复演练：把转储还原到临时库并比对表数/行数）
sudo /usr/local/sbin/deploy-shihai backup --verify

# 只备份，不演练（定时任务用这个）
sudo /usr/local/sbin/deploy-shihai backup

# 看已有备份与最新清单
sudo /usr/local/sbin/deploy-shihai backup-list
```

默认保留 **14 天**，且始终保留最新一份；改保留期用 `BACKUP_RETENTION_DAYS`（写进
`/etc/shihai/shihai.env` 后重启服务，或直接在命令前加环境变量）。

## 定时任务

```bash
sudo /usr/local/sbin/deploy-shihai install-backup-timer 03:30   # 每天 03:30（带 5 分钟随机延迟）
systemctl list-timers shihai-backup.timer --no-pager            # 下次执行时间
journalctl -u shihai-backup.service -n 50 --no-pager            # 最近一次输出
```

`Persistent=true`：机器在 03:30 关机/重启，开机后会补跑一次。

## 恢复步骤

> 恢复会覆盖线上数据，务必先停服务；操作前把当前库再 dump 一份留底。

### 1. 停服务并解包

```bash
sudo systemctl stop shihai
WORK=$(mktemp -d /tmp/shihai-restore.XXXXXX)
BACKUP=/opt/shihai/backups/data/shihai-backup-20261104T025753Z.tar.gz
sudo tar -xzf "$BACKUP" -C "$WORK"
sudo ls -l "$WORK"          # postgres.dump / files.tar.gz / shihai.env / manifest.txt
```

### 2. 还原数据库

```bash
# 二次留底（可选，但强烈建议）
docker exec my-pgvector pg_dump -U postgres -d postgres -Fc > /root/pre-restore-$(date +%s).dump

# 还原（--clean --if-exists 会先删同名对象；--no-owner 避免角色不存在报错）
sudo docker exec -i my-pgvector pg_restore -U postgres -d postgres \
  --clean --if-exists --no-owner --no-privileges < "$WORK/postgres.dump"

# 校验：表数量与关键表行数
sudo docker exec my-pgvector psql -U postgres -d postgres -Atc \
  "select count(*) from pg_tables where schemaname='my_knowledge_assistant'"
sudo docker exec my-pgvector psql -U postgres -d postgres -Atc \
  "select 'documents='||count(*) from my_knowledge_assistant.document_indexes"
```

`pg_restore` 对扩展（如 `vector`）可能输出 NOTICE，不影响结果；出现 `error:` 单独看。

### 3. 还原文件与配置

```bash
sudo tar -xzf "$WORK/files.tar.gz" -C /opt/shihai
sudo install -m 600 -o root -g root "$WORK/shihai.env" /etc/shihai/shihai.env
sudo chown -R deploy:deploy /opt/shihai/docs /opt/shihai/data 2>/dev/null || true
```

### 4. Chroma（可选）

向量可重建，只有想省掉重建时间时才还原。**必须先停 Chroma 容器**，否则 SQLite 会写坏。

```bash
docker stop <chroma容器名>
sudo tar -xzf "$WORK/chroma-data.tar.gz" -C /var/lib/docker/volumes/zhihai_chroma_data/
docker start <chroma容器名>
```

> 卷内是 `_data` 目录：包内结构是 `_data/...`，解到卷根目录即可覆盖。
> 若向量与数据库不一致（例如恢复了更早的库），检索会命中已删除文档 —— 此时应清空集合，
> 让文档重新入库，而不是继续用旧向量。

### 5. 启动与验证

```bash
sudo systemctl start shihai
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/health   # 期望 200
curl -s http://127.0.0.1:8080/api/health | head -c 400                      # 看 documentCount
```

再登录页面确认：知识库文档列表、Wiki 页面、历史会话都在；随便问一个能命中本地文档的问题。

### 6. 收尾

```bash
rm -rf "$WORK"
```

## 验证备份可用（不改动线上）

`backup --verify` 已经是这个作用：新建临时库 `shihai_restore_check` → 还原 → 比对
「表数量 + 总行数」与线上一致 → 删除临时库。任何一步不符就 `exit 1`，定时任务里会记为失败。

## 注意事项

- 备份落在**同一块盘**上，防不了磁盘损坏。建议同时在 Lighthouse 控制台开**定期快照**
  （整机级、可回滚），两层互补。
- `backups/data` 是 `700 root`，`deploy` 用户看不到内容，用 `backup-list` 查看；这是为了避免
  含口令的备份被普通账号读到。
- 备份期间会短暂占用 Docker 与磁盘 IO，`pg_dump` 是逻辑导出，不锁表，业务可继续读。
- 单份约 89M（Chroma 占大头）。若要压缩体积，在 `/etc/shihai/shihai.env` 加
  `BACKUP_INCLUDE_CHROMA=0`，代价是恢复后需要重建向量。
