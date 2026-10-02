# 银龄专线 · 可信圈子服务端（M1）

老人手机上报"发生了什么"，家人/社区/邻居**打开一条链接**就能看到、并能接手。老人手机上
不需要额外权限（不再需要读短信），家人也不需要装任何东西。

## 本地跑起来

```bash
cd server
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8787
```

网页入口：<http://127.0.0.1:8787/>（配对码在老人手机的「设置 → 家人与社区」里）。

测试：

```bash
cd server && .venv/bin/python -m pytest tests -q
```

环境变量（都有默认值，测试会覆盖）：

| 变量 | 默认 | 含义 |
|---|---|---|
| `HOTLINE_DB` | `server/hotline.db` | SQLite 文件位置 |
| `HOTLINE_HEARTBEAT_SECONDS` | 300 | 手机应多久报到一次 |
| `HOTLINE_SILENCE_SECONDS` | 21600（6 小时）| 安静多久算异常，通知圈子 |
| `HOTLINE_PAIR_TTL_SECONDS` | 3600（1 小时）| 配对码有效期；过期后在老人手机上重新配对 |
| `HOTLINE_WATCH_TOKEN` | empty (disabled) | Independent Bearer token for `/api/watch/check` |

## 接口

| 方法 | 路径 | 谁用 | 作用 |
|---|---|---|---|
| POST | `/api/device/pair` | 家人装机时 | 换回设备令牌 + 配对码 |
| POST | `/api/device/heartbeat` | 老人手机 | 报到（证明活着）+ 取回要显示的消息 |
| POST | `/api/device/events` | 老人手机 | 上报 `peace` / `help` / `done` / `alert` |
| GET | `/` `/family` | 圈子 | 加入页 / 情况页 |
| POST | `/join` | 圈子 | 用配对码加入（记住身份与角色）|
| POST | `/family/claim/{id}` | 圈子 | 接手一条求助（同时给老人手机回一句"我来处理"）|
| POST | `/family/message` | **仅家人** | 给老人留一句话（老人手机大字 + 语音）|
| POST | `/api/watch/check` | 定时/测试 | 手动触发"失联"检查 |

设备端用 `Authorization: Bearer <token>`；圈子成员用会话 Cookie。

## 三条设计规矩

1. **角色决定能看到什么**（服务端过滤，不是前端隐藏）：家人看全部；社区看求助与异常；
   邻居只在需要人上门时看到求助。**"老人在做什么"这类上下文只给家人**——页面文字里可能有
   姓名、地址、验证码。
2. **只有家人能给老人留话**。那个渠道在老人手机上读起来是"熟悉的人在说话"，不能让名单外
   的人借用（社区成员的留言请求返回 403）。
3. **失联是服务端唯一的独占职责**：手机关机、没电、被杀，它自己说不出话；"一直没心跳"
   只有这里能发现。一次中断只提醒一次，且措辞是"可能只是没电/关机"，不是"出事了"。

## M1 的边界（故意不做的）

- **不发短信**：`notify.py` 现在只写日志。真实短信需要服务商、签名与模板审核（国内要几天），
  接口形状已经留好，M2 换实现即可
- **没有账号密码**：设备用令牌，家人用配对码换会话；没有找回密码、没有多设备管理
- **没有派单/值班**：社区的路由、认领超时升级属于 M3
- **不做远程控制**：只给状态和请求。远程协助/屏幕共享正是我们要防的诈骗手法
