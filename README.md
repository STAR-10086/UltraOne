# UltraOne

LSPosed 模块：在 Google One 进程内调用 Play Billing，读取 Ultra 档位的 **\$0 试用 offer**，并可一键拉起 Play 收银台。

> 仅供技术研究与自有账号学习使用。使用后果自负。

## 它做什么

Google One 的界面上不展示 Ultra 的免费试用，但这些 offer 一直存在于 Play 结算库（BillingClient）的返回值里。本模块：

1. 注入 `com.google.android.apps.subscriptions.red`（Google One）进程
2. 用 `InMemoryDexClassLoader` 从模块 assets 内存加载官方 Play Billing 5.2.1 的 dex（不落盘，规避 Android 14+ 对可写目录 dex 的限制）
3. 通过反射调用：

```
BillingClient.newBuilder(ctx).setListener(...).enablePendingPurchases().build()
  → startConnection(...)
  → queryProductDetailsAsync(QueryProductDetailsParams, listener)
  → launchBillingFlow(activity, BillingFlowParams.setOfferToken(12 个月免费档的 token))
```

4. 查询结果通过广播显示在悬浮窗 / 应用界面上

## 查询的商品

| 商品 ID | 档位 | 月费 | 常见 offer |
|---|---|---|---|
| `g1.play.20tb` | Ultra 5x · 20TB | \$99.99 | `eft-1month` / `eft-3month` / `eft-6month` / `eft-12month` |
| `g1.play.ai.pro` | Ultra 20x · 30TB | \$199.99 | `eft-1mo` / `eft-3mo` / `eft-6mo` / `eft-12mo` |
| `g1.30tb` | 30TB 另一档 | \$149.99 | 通常无试用 |

注意 30TB 档在 App 里点「升级」用的是 `g1.ai.pro`（无试用），带试用的是 `g1.play.ai.pro`。

## 环境要求

- Android 9 ~ 17，已 root，已装 LSPosed（Zygisk / KernelSU）
- 已安装 Google One
- Play 商店已登录目标账号并绑好付款方式

## 构建

需要：JDK 8+、Android SDK（build-tools 36+、platforms android-36）、Xposed API 82。

```bash
mkdir -p libs
curl -fL -o libs/api-82.jar \
  "https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar"

# 放入 billing dex（见下）
mkdir -p assets
# assets/billing.dex  <-- Play Billing 5.2.1 的 classes.dex

bash build.sh
```

`assets/billing.dex` 不在仓库里（第三方库）。获取方式：从 Play Billing 5.2.1 的 AAR 中取出 `classes.jar`，
用 `d8` 转成 dex，或从任意集成了 billing 5.2.1 的 APK 中提取该库的 classes.dex。

## 使用

1. 安装 APK
2. LSPosed 启用模块，作用域勾选 **Google One**
3. 打开 UltraOne：授予悬浮窗权限 → 启动悬浮窗（小米/澎湃另需电池白名单、省电无限制）
4. 打开 Google One，点「查询 offer」，看日志
5. 点「开 20TB」/「开 30TB」拉起收银台，确认金额为 0 再点订阅

## 日志格式

```
■ Ultra 5x · 20TB · 后续 \$99.99/月
   g1.play.20tb
   ★ 12 个月免费  免费  ·  首期 12 个月
   ★ 6 个月免费   免费  ·  首期 6 个月
   ★ 3 个月免费   免费  ·  首期 3 个月
   ★ 1 个月免费   免费  ·  首期 1 个月
     无试用  \$99.99  ·  首期 1 个月
```

## 已知问题

- 小米/澎湃会冻结模块后台进程，导致自动拉起悬浮窗失败；从应用内点「启动悬浮窗」并加电池白名单
- 悬浮窗按钮对 adb 注入的触摸不响应（真人点击正常）；应用内按钮功能相同
- Play 收银台要求「验证购买交易」时需要指纹/密码，模块无法替代

## 风险

- 一个账号基本只有一次机会，试用发起后 offer 消失
- 开通后按月自动续费（20TB \$99.99、30TB \$199.99），需手动在 Play 里取消
- 绕过客户端正常展示逻辑，可能违反 Google 服务条款，存在封号风险

## 许可

MIT
