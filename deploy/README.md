# 生产发布

生产发布改为从 Mac 执行一键脚本，避免 GitHub 海外 Runner 向腾讯云跨境上传完整 JAR。GitHub Actions 只在 `master` 推送时运行后端测试和前端构建验证，不再连接生产服务器。

## 一键发版

在项目根目录执行：

```bash
./deploy/release-local.sh --yes
```

脚本会依次执行：

```text
校验当前分支为 master
校验工作区没有未提交内容
校验本地 master 与 origin/master 一致
运行后端测试并生成可执行 JAR
执行 npm ci 和前端生产构建
生成包含 Git 提交 ID 的发布包
通过 rsync 上传服务器
调用服务器发布脚本
检查内网及公网健康接口
```

无需交互确认时可执行：

```bash
./deploy/release-local.sh --yes
```

默认部署配置：

```text
服务器：175.178.229.209
SSH 端口：22
SSH 用户：deploy
SSH 私钥：~/.ssh/zhihai_deploy_key
公网健康检查：http://175.178.229.209/api/health
```

需要覆盖时使用环境变量：

```bash
DEPLOY_HOST=服务器地址 \
DEPLOY_PORT=SSH端口 \
DEPLOY_USER=deploy \
DEPLOY_KEY="$HOME/.ssh/zhihai_deploy_key" \
PUBLIC_HEALTH_URL=https://你的域名/api/health \
./deploy/release-local.sh
```

如果 `mvn` 不在 `PATH`，脚本会尝试 `$HOME/apache-maven-3.6.3/bin/mvn`，也可以显式指定：

```bash
MAVEN_BIN=/你的路径/bin/mvn ./deploy/release-local.sh
```

## 更新范围

发布只替换：

```text
/opt/zhihai/app/zhihai.jar
/opt/zhihai/frontend/dist
```

不会修改：

```text
/etc/zhihai/zhihai.env
/opt/zhihai/docs
/opt/zhihai/data
PostgreSQL
Chroma 数据
```

## 服务器前置配置

服务器需要安装 `rsync`：

```bash
dnf install -y rsync
```

发布用户必须能使用部署密钥登录：

```bash
ssh -i ~/.ssh/zhihai_deploy_key deploy@175.178.229.209
```

服务器应安装最新版发布脚本：

```bash
scp deploy/deploy-zhihai root@175.178.229.209:/tmp/deploy-zhihai
ssh root@175.178.229.209 \
  'install -o root -g root -m 750 /tmp/deploy-zhihai /usr/local/sbin/deploy-zhihai'
```

`/etc/sudoers.d/zhihai-deploy` 应包含：

```sudoers
deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-zhihai *
```

校验：

```bash
visudo -cf /etc/sudoers.d/zhihai-deploy
su - deploy -c 'sudo -n /usr/local/sbin/deploy-zhihai'
```

第二条命令应输出用法并返回状态码 `2`，但不能要求输入密码。

## 回滚

服务器部署脚本会在替换前备份当前 JAR 和前端。新后端在约 60 秒内未通过 `http://127.0.0.1:8080/api/health` 时，会自动恢复上一版并重新启动服务。

服务器保留 14 天内的发布目录和发布前备份：

```text
/opt/zhihai/releases
/opt/zhihai/backups/releases
```

自动回滚不回滚数据库结构。涉及 JPA 实体变更时应先备份 PostgreSQL；后续应使用 Flyway 管理生产数据库变更。
