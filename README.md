# UltraOne

LSPosed 模块：在 **Google One** 进程里直接调用 Play Billing，把官方界面不展示的 **隐藏 $0 免费试用 / 折扣 offer** 列出来，并一键拉起 Play 收银台。

全程手机操作，不需要电脑、adb、frida。

> 仅用于自有账号的技术研究。订阅产生的任何费用与后果自负。

---

## 一、它做了什么

Google One 的界面只展示当前区域/账号"正常"的套餐，但 Play 结算库里还存在大量隐藏 SKU 与免费试用档。这些档位不在 UI 上暴露，但 `BillingClient.queryProductDetailsAsync` 能查到、`launchBillingFlow` 能直开。

模块做三件事：

1. **注入** `com.google.android.apps.subscriptions.red`（Google One）进程
2. 从模块 apk 的 `assets/billing.dex`（官方 Play Billing 5.x）**内存加载**一个 `BillingClient`，反射调用查询与下单
3. 用一个**悬浮窗**显示查询结果、承载按钮，通过广播把命令送进 Google One 进程

```
UltraOne 进程 ──CMD 广播──▶ Google One 进程（注入的 BillingClient）
UltraOne 进程 ◀──EVT 广播── Google One 进程
```

---

## 二、安装（手机端，5 步）

1. 安装 `ultraone.apk`
2. **LSPosed** → 模块 → 启用 **UltraOne** → 作用域勾选 **Google One**（`com.google.android.apps.subscriptions.red`）
3. 设置 → 应用 → Google One → **强行停止**（让模块在下次启动时注入）
4. 打开 **UltraOne**，点「授予悬浮窗」→「省电无限制」→「启动悬浮窗」
5. 打开 **Google One**，确认当前登录的是你要操作的账号

> **小米 / 澎湃 / HyperOS 必读**：系统会冻结后台进程，自动拉起悬浮窗会失败（日志出现 `PolicyMaker: reason=freeze_able`，`dumpsys` 显示进程 NO_PROC）。
> 所以必须先在 UltraOne 应用里手动点一次「启动悬浮窗」，并把省电策略设为 **无限制**。
> 应用界面里另放了一整套同样的按钮，悬浮窗被系统杀掉时可以直接在应用内操作。

---

## 三、使用

### 3.1 先查询，再开档

悬浮窗（或应用内）点 **「查询全部 offer」**。模块会把 `Skus.CATALOG` 里 **143 个候选商品**（从 `sku-catalog.json` 的 124 个实测命中 + 19 个 gemini/acq 命名空间探测项）分 20 个一批提交给 `queryProductDetailsAsync`，命中的档位会带着 offerId、价格、首期时长打出来：

```
■ Google AI Pro 5TB · SKU 自带 12 个月免费
   g1.2tb.ai.12months_eft  (Google AI Pro (5 TB))
   ★ eft-12months   $0.00 · 首期 12 个月   [免费]
```

★ 表示首期价格 $0。

查询覆盖的档位：

| 分组 | 商品数 | 含免费试用 |
|---|---|---|
| AI Pro 5TB（`g1.2tb.ai.*` / `g1.play.2tb.ai.*`） | 20 | 12M / 6M / 3M / 2M / 学生 12M |
| AI Pro Helium（`g1.helium.*`） | 11 | `g1.play.helium` 1/3/6/12M |
| gemini 命名空间探测（`com.google.gemini.*`） | 9 | 1M / 3M / 12M |
| Google One 2TB | 29 | 1M / 3M / 6M / 1Y |
| Google One 100GB / 200GB | 40 | 1M / 2M / 3M / 1Y |
| Google One 30GB | 14 | 1M |
| Ultra 20TB / 30TB | 8 | 1M（acq 遗留） |
| YouTube Premium | 1 | 1M |

同一 SKU 下的多个 offer（如 `subs_variant` 的四段）会全部列出，按"免费优先 + 月数"排序。

### 3.2 「这个账号能白拿什么」汇总

扫描完成后，模块不再只堆 SKU 列表，而是**按档位归并**告诉你结论。悬浮窗或应用里点 **「能拿什么」** 随时重看：

```
╔══════════════════════════════════
║ 这个账号现在能拿到的免费订阅
║ 共 6 个档位 · 41 个免费 offer
╚══════════════════════════════════

【Google AI Pro 5TB】
     18 个月  2tb.ai.18months_eft  [eft-18months]
     15 个月  2tb.ai.15months_eft  [eft-15months]
     12 个月  2tb.ai.12months_eft  [eft-12months]
     12 个月  2tb.ai.subs_variant  [12months-eft]
   …还有 8 个 SKU
   ▶ 最长 18 个月

────── 结论 ──────
最划算：Google AI Pro 5TB · 18 个月 $0
```

应用主界面会把同一份数据渲染成**卡片**：每行显示「几个月 / SKU / offerId」，右侧「开这个」直接拉起收银台。

归并规则：

- 同一档位下十几个 SKU 只列最优的 3 条，避免刷屏
- `freetrial` / 基础档这类不带数字的 offer，月数从 SKU 名里读（`g1.2tb.1year_eft` → 12 个月）
- 月数推断优先级：offerId 数字 → SKU 名数字 → `billingPeriod`（P1Y / P6M）
- 档位排序：AI Pro → Ultra → 2TB → 200GB → 100GB → 30GB

汇总结果存进 `SharedPreferences`，进程被杀、重装模块都不丢，下次打开应用直接显示上次扫描的结论。日志同样落盘到 `Android/data/com.star.ultraone/files/ultraone.log`。

### 3.3 一键方案

悬浮窗底部是一条横向滚动的方案条，点一下 = 一次 `launchBillingFlow`，直接拉起 Play 收银台。

| 方案 | SKU | offer | 结果 |
|---|---|---|---|
| ① Pro 5TB · 12 个月 $0 | `g1.2tb.ai.subs_variant` | `12months-eft` | 首年免费，到期 $19.99/月 |
| ② Pro · 叠加 +6 个月 | `g1.2tb.ai.subs_variant` | `6months-eft` | 订阅升级，剩余试用期折算顺延 |
| ③ Pro · 叠加 +6 个月 | `g1.2tb.ai.subs_variant` | `6months-eft-2` | 叠到 24 个月 |
| ④ Pro · 叠加 +6 个月 | `g1.2tb.ai.subs_variant` | `6months-eft-ss-2026` | 常被 Play 侧拒绝，留档 |
| ⑤ Pro 5TB · 18 个月 $0 | `g1.2tb.ai.18months_eft` | 基础档 | 单 SKU 自带 18 个月免费 |
| ⑥ Pro 5TB · 15 个月 $0 | `g1.2tb.ai.15months_eft` | 基础档 | 15 个月 |
| ⑦ Pro 5TB · 12 个月 $0 | `g1.2tb.ai.12months_eft` | 基础档 | 12 个月 |
| ⑧ AI Pro 学生 · 12 个月 | `g1.play.2tb.ai.student` | `eft-12mo` | 需通过学生验证 |
| ⑨ Ultra 20TB · 1 个月 $0 | `g1.play.20tb` | 基础档 | 真 Ultra 5x |
| ⑩ Ultra 30TB · 1 个月 $0 | `g1.play.ai.pro` | 基础档 | 真 Ultra 20x |
| ⑪ Pro · 3 个月 0.05 折 | `com.google.gemini.2tb.ai.m.3month_ip_99p` | 基础档 | 美区 $0.29/月 |
| ⑫ 100GB · 12 个月 $0 | `g1.100gb.annual.1year_eft` | `freetrial` | 小容量，用来验证链路 |

SKU 是否对当前账号/区域开放由 Play 服务端决定，查不到就是没开，方案按钮会先补查一次再拉起。

### 3.3 叠加续期（Pro 可到 24 个月）

`g1.2tb.ai.subs_variant` 的免费期由四段 offer 组成，按顺序各能用一次：

| 顺序 | offerId | 时长 |
|---|---|---|
| 1 | `12months-eft` | 12 个月 |
| 2 | `6months-eft` | 6 个月 |
| 3 | `6months-eft-2` | 6 个月 |
| 4 | `6months-eft-ss-2026` | 6 个月（实测 Play 侧拒绝） |

实测链路：

```
12months-eft  → 2027-11-17
+6months-eft  → 2028-05-18   （收银台写「546 天，含当前方案剩余 364 天折算」，US$0.00）
+6months-eft-2 → 2028-11-18  （共 24 个月，首次扣款 2028-11-18）
```

叠加用的是 `setReplaceProrationMode(5)`，实测语义是 **DEFERRED** —— Play 把旧订阅剩余时间折算顺延，不是立即换档补差价，所以每段都是 $0.00。

### 3.4 叠加时的 token 怎么来（全程手机，不需要电脑）

叠加后 `purchaseToken` 会变，用旧 token Play 会报「我们这边出了点问题」。**v3.1 起模块自己解决，不用再插电脑跑脚本。**

三级回退，按顺序自动试：

| 顺序 | 来源 | 需要 root | 说明 |
|---|---|---|---|
| ① | **app 回调捕获** | 否 | hook Google One 自己的 `PurchasesUpdatedListener` / `queryPurchasesAsync` / `Purchase.getPurchaseToken`，它每次进「管理方案」页都会查订阅，回调里的 `Purchase` 就带 token。购买完成后 Play 也会回调，所以叠加完自动就有新 token。 |
| ② | **模块进程 su 读库** | 是 | UltraOne 自己 `su` 把 Play 的 `library.db`（连同 `-wal` / `-shm`）拷进私有目录再解析 `ownership.inapp_purchase_data`。SELinux 不让写时退回 `su cat` 走 stdout 搬字节。 |
| ③ | Google One 进程内查询 | 视情况 | `queryPurchasesAsync(subs)` → `queryPurchaseHistoryAsync`，在注入的 BillingClient 里常常返回空，所以只当兜底。 |

**①② 都不需要电脑。** 只要把 app 回调那条路走通，纯无 root 也能叠加：

1. 打开 Google One → 头像 → **管理方案**，让 app 自己查一次订阅
2. 回到悬浮窗点 **「取 token」**，日志会打印 `已捕获现有订阅 token（165 字符 · g1.2tb.ai.subs_variant）`
3. 点「② 叠加 +6 个月」

> 没捕获到时日志会提示「进 Google One 的『管理方案』页逛一下就会自动捕获」。
> 捕获到之后，点叠加按钮会**直接用这个 token**，不用再点「取 token」。

模块还保留了两个旧路径做兼容：`/data/local/tmp/ultra_token.txt` 预置文件、以及注入进程里的 su 读库（旧版行为）。

---

## 四、源码结构

```
AndroidManifest.xml              LSPosed 模块声明 + 悬浮窗/前台服务权限
res/values/arrays.xml            xposedscope：只勾 Google One
res/drawable/ic_launcher.xml     图标
assets/xposed_init               入口类名
assets/billing.dex               Play Billing 5.x（反射加载用，259 KB）
libs/api-82.jar                  Xposed API stub（编译期）
src/com/star/ultraone/
  Main.java                      注入入口、BillingClient、查询、下单、token 提取
  Skus.java                      SKU 目录 + 一键方案表
  OverlayService.java            悬浮窗（前台服务）
  MainActivity.java              应用主界面（权限引导 + 同一套按钮 + 日志）
  LogBus.java                    进程内日志总线 + 命令发送 + 方案预取 token
  TokenVault.java                模块进程内 su 读 Play 库取 token（root 路径）
  EvtBridge.java                 动态注册 EVT 广播，收 Google One 回传
build.sh                         一键构建（javac → d8 → aapt2 → zipalign → apksigner）
```

### 关键实现点

- **dex 内存加载**：Android 14+ 不允许从可写目录加载 dex，所以用 `InMemoryDexClassLoader` 直接从 apk 里读 `assets/billing.dex`；API < 26 兜底落盘到 `codeCacheDir`。
- **找模块 apk 路径**：不依赖包可见性，直接从 LSPosed 注入的 `BaseDexClassLoader.pathList.dexElements[].path` 里挑出模块自己的 apk。
- **命令通道**：模块进程 `sendBroadcast(new Intent("com.star.ultraone.CMD").setPackage("...subscriptions.red"))`；Google One 进程用 `RECEIVER_EXPORTED` 注册。
- **Activity 取法**：`ActivityThread.currentActivityThread().mActivities` 反射遍历，取第一个未 finish 的；拿不到就退回 `Activity.onCreate` hook 记录的那个。
- **防重复下单**：3 秒内只允许一次 `launchBillingFlow`，避免 `Duplicate purchase`。

---

## 五、自己构建

需要 JDK 8+、Android SDK build-tools 36、platforms/android-36。

```bash
# Git Bash / MSYS
export ANDROID_SDK=/c/Users/<you>/.local/android-sdk
bash build.sh
# 产物：ultraone.apk
```

`build.sh` 做的事：`javac` → `d8`（min-api 24）→ `aapt2 compile/link`（`-A assets` 把 billing.dex 打进去）→ 手动塞 `classes.dex` → `zipalign` → `apksigner`（debug keystore，首次自动生成）。

---

## 六、排查

| 现象 | 原因 / 处理 |
|---|---|
| 悬浮窗没出现 | 没给悬浮窗权限；或 HyperOS 冻结了进程 → 应用内手动点「启动悬浮窗」+ 省电无限制 |
| 日志停在「正在连接 Play 计费服务…」 | Google One 没被强行停止过，模块还没注入 → 强行停止后重开 |
| 「计费服务 连接失败 code=3」 | Play 商店没登录 / 被冻结；打开 Play 商店确认账号可用 |
| 查询全空 | 该账号/区域没有这些隐藏 SKU；换账号或换节点 |
| 拉起收银台报「购买交易未完成」 | Play 侧已下架该 offer，换下一个方案 |
| 叠加时提示读不到 token | 先去 Google One「管理方案」页逛一圈让 app 查订阅；还不行再给 su 权限走 root 路径 |

日志同时在：悬浮窗、应用内、`logcat -s UltraOne`、LSPosed 日志。

---

## 七、致谢

SKU 与 offer 命名参考：

- [crhywun/g1-acq](https://github.com/crhywun/g1-acq) —— iOS Shadowrocket 侧原始实现
- [Dgkncn/g1-acq-plus](https://github.com/Dgkncn/g1-acq-plus) —— 全量 SKU 爆破扫描与 `sku-catalog.json`

本模块是 Android / LSPosed 侧的重写：不代理流量、不改 HTTP 响应，直接在 Play Billing 层操作。
