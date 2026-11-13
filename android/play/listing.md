# Google Play listing

The text of the store page, kept here so it changes with the app. Limits: title 30 characters, short description
80, full description 4000, release notes 500. Privacy policy:
<https://github.com/kennyfs/Mahjong-TW/blob/main/docs/privacy.md>.

Images: `icon-512.png` and `feature-zh.png` (1024 x 500) come from `images.py`, drawn with the app's own tiles. The
phone screenshots (eight at 1920 x 1080, sideways, in store order) come from the emulator scripts' store mode,
`STORE=1 ./device-tests.sh` (android/README.md, "The store's screenshots"); they are not kept in the repository.

The app is in Traditional Chinese (Taiwan) only, so is the listing. The words are the app's own (`strings.xml`).
The game scores in 台 and points and shows no money, chips or bets: keep it that way here (no 賭, 籌碼, 錢, 現金,
贏錢), and answer the content rating to match (no real-money and no simulated gambling).

## zh-TW

**Title:** 台灣麻將：AI 分析與連線對戰

**Short description:**

和神經網路電腦打台灣十六張麻將，每一局都能回顧，看哪裡打錯、損失幾台。

**Full description:**

台灣十六張麻將：和三位電腦玩家練習，打完每一局都能回顧與分析，也能和朋友連線或面對面對戰。

練習
• 三位電腦玩家可以選神經網路或牌效率（向聽數與進張）
• 打一局、一圈或一將（四圈）
• 速度分慢、中、快和手動，手動時每按一次「下一步」電腦才動
• 吃、碰、槓、胡都有動畫和音效，音效可以調小或關掉
• 聽牌提示：輪到你時，標出打了會聽牌的牌

回顧與分析
• 每一局都能回顧：神經網路標出值得看的決定，告訴你損失多少台、最佳是什麼
• 期望的變化圖，點一下就跳到那個決定
• 每個選擇的期望、胡牌、放槍與被自摸的機率，以及打出去當下的危險
• 失誤門檻分入門、中級、高手
• 分支：從自己的任何一個決定改打，看結果有什麼不同
• 分析頁：擺出任何局面（手牌、副露、牌河），看每個選擇怎麼樣
• 分析統計：損失來自選牌、放槍風險、吃碰槓還是胡不胡

規則
• 台數表：簡單1（南部打法）、簡單2、標準、大師（2025 麻將大師賽的台數表）
• 花牌：無花、見花見台、正花
• 每一條規則都可以調整，並附上說明；可選天聽、地聽與報聽
• 規則說明：幾頁就學會台灣麻將，還有「考考你：聽哪些牌？」
• 導覽：一分鐘認識每個畫面

連線對戰
• 開一桌：一到四人同桌，空位由電腦補上
• 同牌賽：每人各打一桌、牌都一樣，比誰得分多
• 可以透過網路上的對戰伺服器連線，也可以用「附近」功能以藍牙和 Wi-Fi 面對面連線，不需要網路
• 對戰時沒有任何提示；打完可以在紀錄裡回顧與分析

其他
• 每一局都存在手機上，可以收藏、看統計，也能分享或匯出
• 配色：牌桌綠、簡約，或跟隨桌布（Android 12 以上）
• 練習和分析完全在手機上離線運算
• 沒有廣告、沒有內購、不需要帳號
• 也有網頁版，電腦也能玩，還能和 app 的玩家連線對戰：https://mahjong.1li.tw
• 開放原始碼（GPLv3）：https://github.com/kennyfs/Mahjong-TW

本遊戲不涉及任何金錢或賭博。

## What's new (release notes, 500 characters each)

### 1.0.0

zh-TW:

第一個正式版。和神經網路或牌效率的電腦練習台灣十六張麻將，每一局都能回顧與分析，看哪裡打錯、損失幾台；分析頁可以擺出任何局面。台數表有簡單1、簡單2、標準、大師，花牌有無花、見花見台、正花。可以連線開一桌或比同牌賽，也能用「附近」面對面對戰。網頁版在 https://mahjong.1li.tw。
