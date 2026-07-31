# 生产发布

只有推送 `master` 或手动运行 GitHub Actions 中的 `Deploy production`，工作流才会执行后端测试、构建前端、上传发布包、替换服务器产物并检查 `/api/health`。其他分支的推送和 Pull Request 不会触发生产发布。健康检查失败时，服务器脚本会自动恢复上一版 JAR 和前端文件。

自动发布只更新以下内容：

```text
/opt/zhihai/app/zhihai.jar
/opt/zhihai/frontend/dist
```

不会上传或修改 `/etc/zhihai/zhihai.env`、`/opt/zhihai/docs`、PostgreSQL 和 Chroma 数据。

## 服务器初始化

在服务器创建发布用户：

```bash
useradd --create-home --shell /bin/bash deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
```

在本地生成仅供 GitHub Actions 使用的密钥，不要给私钥设置口令：

```bash
ssh-keygen -t ed25519 -C "github-actions-zhihai" -f ./zhihai_deploy_key
```

把 `zhihai_deploy_key.pub` 的整行内容写入服务器的 `/home/deploy/.ssh/authorized_keys`，然后在服务器设置权限：

```bash
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys
```

从本地上传仓库中的发布脚本：

```bash
scp deploy/deploy-zhihai root@175.178.229.209:/tmp/deploy-zhihai
```

在服务器安装脚本：

```bash
install -o root -g root -m 750 /tmp/deploy-zhihai /usr/local/sbin/deploy-zhihai
```

使用 `visudo -f /etc/sudoers.d/zhihai-deploy` 创建以下规则：

```sudoers
deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-zhihai *
```

校验 sudoers：

```bash
chmod 440 /etc/sudoers.d/zhihai-deploy
visudo -cf /etc/sudoers.d/zhihai-deploy
```

确认服务、环境文件和部署目录已经存在：

```bash
systemctl status zhihai --no-pager
test -f /etc/zhihai/zhihai.env
mkdir -p /opt/zhihai/app /opt/zhihai/frontend/dist
```

## GitHub Secrets

在 GitHub 仓库的 `Settings -> Environments -> production` 创建生产环境，并添加以下 secrets：

| Secret | 内容 |
| --- | --- |
| `DEPLOY_HOST` | 服务器 IP，例如 `175.178.229.209` |
| `DEPLOY_PORT` | SSH 端口，默认是 `22` |
| `DEPLOY_USER` | SSH 发布用户，未配置时默认使用 `deploy` |
| `DEPLOY_PRIVATE_KEY` | `zhihai_deploy_key` 私钥的完整内容 |
| `DEPLOY_KNOWN_HOSTS` | 服务器 SSH host key 的完整一行 |

在可信网络中获取 host key，并与服务器真实指纹核对后保存：

```bash
ssh-keyscan -p 22 -t ed25519 175.178.229.209
```

在服务器查看真实指纹：

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

不要把数据库密码、模型 API Key 或 `/etc/zhihai/zhihai.env` 放进 GitHub Secrets。它们已保存在服务器，自动发布不会替换该文件。

## 首次验证

先从本地验证发布用户：

```bash
ssh -i ./zhihai_deploy_key -p 22 deploy@175.178.229.209
```

确认发布脚本可通过 sudo 执行但不会要求密码：

```bash
sudo -n /usr/local/sbin/deploy-zhihai
```

该命令因为缺少参数应输出用法并返回状态码 `2`，但不应提示输入 sudo 密码。

完成后，在 GitHub 的 `Actions -> Deploy production -> Run workflow` 手动执行第一次发布。发布成功后检查：

```bash
systemctl status zhihai --no-pager
curl -fsS http://127.0.0.1:8080/api/health
journalctl -u zhihai -n 100 --no-pager
```

## 发布与回滚

日常发布只需将经过验证的代码合并并推送到 `master`。同一时间只允许一个生产发布，新的提交不会中断正在执行的发布。

服务器保留 14 天内的发布解包目录和发布前备份：

```text
/opt/zhihai/releases
/opt/zhihai/backups/releases
```

自动回滚只恢复应用产物，不回滚数据库结构。当前生产配置仍使用 `spring.jpa.hibernate.ddl-auto=update`，涉及 JPA 实体变更时应先备份 PostgreSQL；后续应迁移到 Flyway 并在生产环境使用 `ddl-auto=validate`。
