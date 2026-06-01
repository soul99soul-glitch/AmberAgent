<div align="center">
  <img src="docs/icon.png" alt="AmberAgent 應用圖示" width="100" />
  <h1>AmberAgent</h1>

  <p>
    一個面向手機使用場景的個人 Android Agent 應用，重點是對話、深度閱讀、SubAgent 和本地工具呼叫。
  </p>

  <p>
    <a href="README.md">English</a> | <a href="README_ZH_CN.md">简体中文</a> | 繁體中文
  </p>
</div>

<div align="center">
  <img src="docs/img/amberagent-home-blue.jpg" alt="AmberAgent 首頁" width="240" />
  <img src="docs/img/amberagent-chat-blue.jpg" alt="AmberAgent SubAgent 聊天介面" width="240" />
  <img src="docs/img/amberagent-board-blue.jpg" alt="AmberAgent 今日看板" width="240" />
</div>

## AmberAgent 是什麼？

AmberAgent 是一個個人開源 Android 專案，用來探索 AI Agent 在手機上可以怎樣工作。它最初源自
[RikkaHub](https://github.com/rikkahub/rikkahub) 的深度 fork，現在主要圍繞工具呼叫、SubAgent、深度閱讀、本地狀態、
行動端 UI，以及本地開發工具接入這些方向繼續演進。

本專案不是 RikkaHub 官方版本，也不是官方繼任專案。專案會保留上游來源說明和授權義務，並保持個人非商業研究與學習專案的定位。

## 專案亮點

- **能看見過程的 Agent 對話**：工具呼叫、取消狀態、卡片結果和執行狀態都會留在對話裡，而不是只顯示一段最終回覆。
- **SubAgent 分工**：固定角色和動態角色可以拆分任務、回報進度，再把結果合回同一段對話。
- **今日看板與深度閱讀**：從熱點收集、來源抓取，到結構規劃、分節寫作和證據記錄，盡量把長文章閱讀做成手機上可用的流程。
- **適合手機的工具介面**：搜尋結果、檔案、本地裝置操作、瀏覽器式卡片、PPT 預覽和 live HTML，都盡量用更適合 Android 的方式展示。
- **可選的本地 CLI 席位**：Gemini CLI、Antigravity CLI、Codex CLI、Claude Code、Kimi CLI 等工具，會在可探測、可登入、可驗證執行時參與模型議會。
- **長期自用的工作區**：Provider、Prompt、設定、工作區狀態、同步和備份都按長期使用來設計，而不是一次性會話資料。

## 專案狀態

AmberAgent 仍然是一個快速變化的實驗性程式碼庫。它既有從 RikkaHub 繼承來的基礎，也有大量獨立重構和新的 Agent 能力。使用時請預期會有邊角問題、
本地配置要求和偶爾的破壞性變化。它更像個人研究應用和程式碼庫，還不是一個已經打磨完成的終端使用者發行版。

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
