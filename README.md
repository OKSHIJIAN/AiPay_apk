# AiPay转发器

旧手机挂机转发程序，用于监听微信、支付宝到账通知并上报到 Ai Pay 服务器。仅供个人学习使用。

## 构建

```powershell
./gradlew.bat assembleDebug
```

或在 Android Studio 中打开本目录，等待 Gradle 同步后运行 `app`。

需要本机已安装 JDK 17 和 Android SDK。命令行构建前请确认 `JAVA_HOME` 指向 JDK 17。

## 授权通知监听

1. 安装并打开「AiPay转发器」。
2. 首页点击「去授权」。
3. 在系统的「通知使用权」页面中开启「AiPay转发器」。
4. 回到应用，打开首页总开关，启动前台保活服务。

Android 13 及以上还需要授予通知权限，否则前台服务通知可能无法正常显示。

## 配置 API Key

1. 进入「设置」页。
2. 确认 API Base URL，默认值为：
   `https://qlsmtkqdvbionwpmhoyu.supabase.co/functions/v1/make-server-41dc007f`
3. 填写格式为 `aip_xxxxxxxx` 的 API Key，也可以点击扫码按钮扫描包含 API Key 的二维码。
4. 点击「测试连接」，接口返回 `{ "status": "ok" }` 即表示连接正常。

## 保活建议

请在系统设置中允许本应用后台运行、自启动，并加入电池优化白名单。不同品牌手机入口不同，一般在「电池」「应用管理」「自启动管理」中配置。

## 上报接口

通知匹配到金额后会调用：

```http
POST {API_BASE}/notify
X-Api-Key: aip_xxxxxxxx
Content-Type: application/json
```

```json
{ "amount": 9.88, "channel": "wechat" }
```

失败会写入本地 Room 日志，并通过 WorkManager 按 30 秒、2 分钟、10 分钟最多重试 3 次。
