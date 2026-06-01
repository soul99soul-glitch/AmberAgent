<div align="center">
  <img src="docs/icon.png" alt="AmberAgent 應用圖示" width="100" />
  <h1>AmberAgent</h1>

  <p>
    一個個人 Android Agent Runtime，用來探索行動端優先的 AI 工作流、深度閱讀、SubAgent 與本地工具呼叫。
  </p>

  <p>
    <a href="README.md">English</a> | <a href="README_ZH_CN.md">简体中文</a> | 繁體中文
  </p>
</div>

<div align="center">
  <img src="docs/img/amberagent-home.jpg" alt="AmberAgent 首頁" width="240" />
  <img src="docs/img/amberagent-chat.jpg" alt="AmberAgent SubAgent 聊天介面" width="240" />
  <img src="docs/img/amberagent-board.jpg" alt="AmberAgent 今日看板" width="240" />
</div>

## AmberAgent 是什麼？

AmberAgent 是一個個人開源 Android 專案，目標是探索手機上的 AI Agent Runtime 應該是什麼樣子。它最初源自
[RikkaHub](https://github.com/rikkahub/rikkahub) 的深度 fork，目前圍繞 Agent 工作流獨立維護和演進，包括工具呼叫、
SubAgent、深度閱讀、本地優先狀態、行動端 UI，以及外部執行時實驗。

本專案不是 RikkaHub 官方版本，也不是官方繼任專案。專案會保留上游來源說明和授權義務，並保持個人非商業研究與學習專案的定位。

## 專案亮點

- **行動端 Agent Runtime**：聊天不是簡單的訊息列表，而是 Agent 執行介面，包含可見工具呼叫、可取消執行、生成物展示，以及真正保留在裝置上的狀態。
- **SubAgent 工作流**：固定角色和動態角色可以拆分任務、回報工具進度，並把協作結果回到同一段對話裡。
- **今日看板與深度閱讀**：熱點收集、來源抓取、結構規劃、分節寫作、證據記錄和報告式閱讀都作為一等 Android 流程來做。
- **工具與生成物 UI**：搜尋、檔案、本地裝置能力、瀏覽器式卡片、PPT 風格預覽和 live HTML 生成物都盡量以行動端原生互動承載。
- **外部執行時實驗**：Gemini CLI、Antigravity CLI、Codex CLI、Claude Code、Kimi CLI 等本地 CLI 會在可探測、可驗證執行時作為可選議會席位參與。
- **個人連續性**：設定、Provider、Prompt、工作區狀態、同步和備份流程，都圍繞長期個人 Agent 工作區設計。

## 專案狀態

AmberAgent 仍然是一個快速演進的實驗性程式碼庫。它既包含從 RikkaHub 繼承而來的基礎能力，也包含大量獨立重構和新的 Agent
Runtime 工作。它更適合作為個人研究應用和程式碼庫，而不是已經打磨完成的終端使用者發行版。

## 構建

使用 Android Studio，或在倉庫根目錄執行：

```bash
./gradlew :app:assembleNotion
```

`Notion` 是倉庫裡保留下來的歷史 build type 名稱；目前 AmberAgent 的目標包名是 `app.amber.agent`。
部分雲端能力需要本地私有配置檔案，例如 `app/google-services.json`。這些檔案不會提交到倉庫。缺少這些私有憑據時，應用仍可用於本地開發構建，
但 Firebase / Google 相關能力可能受限，取決於配置檔案是否包含目前構建包名對應的 client。

## 貢獻

歡迎小而聚焦的 issue 和 PR，尤其是可復現崩潰、bug 報告、文件和測試。由於專案仍在把 Agent 架構從繼承的聊天客戶端基礎中逐步分離，
大規模順手重寫會比較難審查。

技術棧：

- [Kotlin](https://kotlinlang.org/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Koin](https://insert-koin.io/)
- [Room](https://developer.android.com/training/data-storage/room)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [OkHttp](https://square.github.io/okhttp/)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
- [Coil](https://coil-kt.github.io/coil/)
- [Material You](https://m3.material.io/)

## 來源說明

AmberAgent 是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度 fork。程式碼庫中的部分程式碼、架構、資源和歷史設計來源於
RikkaHub，並繼續遵守原專案的授權和署名要求。AmberAgent 特有的 Agent 能力與後續重構由本倉庫獨立維護。

## 授權

請查看 [LICENSE](LICENSE)。本專案保留 RikkaHub 派生程式碼的上游授權義務。AmberAgent 目前作為個人非商業開源專案維護。
