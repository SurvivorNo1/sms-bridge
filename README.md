# SMS Bridge

> **声明**：这是我（[@SurvivorNo1](https://github.com/SurvivorNo1)）2026 年秋招找工作期间写的个人工具，
> 用途只有一个：网申时短信验证码太多、每次都要掏手机，所以让电脑上的脚本 / AI agent
> 能在**我自己的局域网内**、凭**我自己设置的密钥**，读取**我自己手机**上的短信。
> 它不联网上传、不发短信、不读通讯录、没有任何第三方依赖，也不会对别人的手机做任何事。
> 公开源码是为了让用它的人（包括我自己）能看清它到底做了什么。请只装在你自己的手机上。

把手机变成一个**只能在 WiFi 局域网内、带密钥才能访问**的本机短信查询服务。

> ⚠️ **关于 ROM 的「验证码保护」**：小米 / 华为 / OPPO 等国产 ROM 开着这个功能时，验证码类短信对所有第三方 app 的
> **数据库读取**隐藏。v0.3 起本工具走**双通道**：收件箱查询 + `SMS_RECEIVED` 广播接收，广播通常不受该功能过滤，
> 所以**优先不要关「验证码保护」**，先开着试。只有你这台 ROM 连广播也拦时才需要关——那是降低整机安全性的操作
> （任何有短信权限的 app 都能读验证码），**用完请立刻重新打开**。

- 手机上一个开关：开 = 前台服务常驻监听端口 + 收集短信广播；关 = 端口关闭 + 清空广播缓存
- 短信两路合并去重：系统收件箱 ∪ 开关打开期间收到的广播（后者缓存 24 小时 / 200 条，存 app 私有目录）
- 电脑主动发起查询，两个接口：**时间范围** / **关键词（可正则）**
- 每个请求 HMAC-SHA256 签名 + 5 分钟时间窗；响应 AES-256-GCM 加密
- 只接受来自 `wlan*` / `ap*` / `swlan*` 网卡、且对端为私网地址的连接，其他一律 403
- 出错返回明文 JSON `{"ok":false,"error":..,"hint":..}`，agent 看一眼就知道怎么改
- `GET /` 是健康页 + 接口文档（含 Python 示例），`GET /health` 是 JSON 健康检查；两者免签名、不含短信

## 接口

| GET | 参数 | 签名 | 说明 |
| --- | --- | --- | --- |
| `/` | — | 否 | 健康页（HTML），接口用法与签名算法 |
| `/health` | — | 否 | `{"ok":true,"service":"sms-bridge","version":..}` |
| `/ping` | — | 是 | 验证密钥；返回手机当前时间（用于校时） |
| `/sms/range` | `minutes=30` 或 `from=<ms>&to=<ms>` | 是 | 时间范围内的短信，新的在前，最多 200 条 |
| `/sms/search` | `q=关键词`，`minutes=30`，`re=1` 按正则 | 是 | 正文或发件人匹配的短信 |

```
X-Timestamp: <毫秒 Unix 时间戳>
X-Sign:      hex( HMAC-SHA256( key=SECRET, msg="<ts>\nGET\n<path?query>" ) )
成功响应：   base64( iv[12] + AES-256-GCM( key=SHA-256(SECRET), json ) )
             json = {"ok":true,"count":N,"items":[{"ts":<ms>,"from":"..","body":".."}]}
错误响应：   {"ok":false,"error":"bad_signature","hint":"...","doc":"..."}   （明文）
```

错误码：`missing_auth` `bad_timestamp` `timestamp_skew` `bad_signature` `not_lan` `missing_q` `bad_regex` `bad_range` `no_sms_permission` `not_found` `method_not_allowed`

电脑端参考实现见 [`../sms_code.py`](../sms_code.py)（Python，依赖 `pycryptodome`）。

## 权限

`READ_SMS`（读本机收件箱）、`RECEIVE_SMS`（收短信广播，绕过验证码保护对数据库读取的过滤）、`INTERNET`（开本地端口）、`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`（常驻）、`POST_NOTIFICATIONS`。
没有发短信、通讯录、通话记录、位置权限。`allowBackup=false`，密钥不进云备份。

## 测试

CI 三层：

1. **JVM 单元测试**（`app/src/test`）：签名与 AES-GCM 的期望值由 Python `hmac` / `pycryptodome` 算出，
   逐字节比对，保证手机端和电脑端脚本一致；再起真实的 `HttpServer` 走完整 HTTP → 签名 → 解密链路，
   覆盖关键词 / 正则 / 时间窗 / 各类错误响应 / 篡改 query 被拒
2. **模拟器端到端**（`app/src/androidTest`）：CI 起 Android 30 模拟器，`adb emu sms send` 往系统收件箱
   注入一条真实短信，再通过 `InboxSource` → `HttpServer` 把它查出来，并验证主界面能启动
3. 真机联调：`sms_code.py --ping` / `--range` / `--search`

## 构建

推到 `main` 自动构建、跑测试并发布 Release（`sms-bridge.apk`）。
`app/release.jks` 是固定签名用的 keystore（密码 `smsbridge`），只为让每次构建签名一致、能覆盖安装；
正式包零第三方依赖，测试依赖不进 APK。

## 代码结构

```
app/src/main/java/io/github/survivorno1/smsbridge/
  MainActivity.kt   界面：开关 / 地址 / 密钥 / 本机自检
  BridgeService.kt  前台服务，托管 HttpServer
  HttpServer.kt     HTTP 解析、签名校验、路由、健康页、错误 JSON
  SmsSource.kt      短信来源接口 + InboxSource（content://sms/inbox）+ MergedSource（两路合并去重）
  Capture.kt        广播通道：SmsReceiver + 24 小时私有缓存
  Crypto.kt         HMAC 签名、AES-GCM 加解密（纯 JVM）
  Prefs.kt          密钥 / 端口
```

## License

MIT
