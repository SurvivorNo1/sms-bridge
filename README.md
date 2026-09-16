# SMS Bridge

自用的极简安卓 app：把手机变成一个**只能在 WiFi 局域网内、带密钥才能访问**的短信查询服务，
让电脑上的脚本 / agent 自己去拿验证码，不用人看手机。

**只做这一件事。** 不读历史收件箱、不联网上传、不发短信、不读通讯录、零第三方依赖。

## 接口

三个 GET，全部要签名：

| 路径 | 参数 | 说明 |
| --- | --- | --- |
| `/ping` | — | 连通性；返回缓存条数与手机时间 |
| `/sms/range` | `from`,`to`（epoch ms）或 `minutes=N` | 时间范围查询，默认最近 30 分钟 |
| `/sms/search` | `q`=关键词，`re=1` 按正则，`minutes=N` | 关键词/正则查询（匹配正文或发件人） |

- 请求头：`X-Timestamp`（ms）、`X-Sign = hex(HMAC-SHA256(secret, "<ts>\nGET\n<path?query>"))`，时间偏差 > 5 分钟拒绝
- 响应体：`base64(iv[12] + AES-256-GCM(key=SHA256(secret), json))`，json 形如 `{"ok":true,"items":[{"ts":..,"from":"..","body":".."}]}`
- 只接受来自 `wlan*` / `ap*` / `swlan*` 网卡、且对端为私网地址的连接；其他一律 403

电脑端参考实现：`../sms_code.py`。

## 权限

`RECEIVE_SMS`（接收新短信）、`INTERNET`（开本地端口）、`FOREGROUND_SERVICE`（常驻）、`POST_NOTIFICATIONS`。
没有 `READ_SMS`，所以拿不到历史短信。

## 数据

开关打开期间收到的短信进缓存：最多 24 小时 / 200 条，存 app 私有目录（其他 app 读不到），
关开关即清空；`allowBackup=false`，密钥和缓存都不会进云备份。

## 构建

推到 `main` 自动由 GitHub Actions 构建并发布 Release（`sms-bridge.apk`）。
`app/release.jks` 是固定签名用的 keystore（密码 `smsbridge`），只为每次构建签名一致、能覆盖安装。
