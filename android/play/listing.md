# Google Play listing

The text of the store page, kept here so it changes with the app. Limits: title 30 characters, short description
80, full description 4000, release notes 500. Privacy policy:
<https://github.com/kennyfs/Mahjong-TW/blob/main/docs/privacy.md>.

Images: `icon-512.png` and `feature-zh.png` (1024 x 500) come from `images.py`, drawn with the app's own tiles. The
phone screenshots (eight at 1920 x 1080, sideways, in store order) come from the emulator scripts' store mode,
`STORE=1 ./device-tests.sh` (android/README.md, "The store's screenshots"); they are not kept in the repository.

The app is in Traditional Chinese (Taiwan) and US English (since 2026-11-15), so is the listing; the words are the
app's own (`values/strings.xml`, `values-en/strings.xml`). Written by the session that makes the changes (it knows
what changed); the release session may edit it. The store text keeps every line break as written, so each
paragraph and bullet is a single line here, however long; the release notes are blocks to copy whole (below). The game scores in 台 (tai) and points and shows no money, chips or
bets: keep it that way here (no 賭, 籌碼, 錢, 現金, 贏錢; no gambling, betting, cash, chips), and answer the content
rating to match (no real-money and no simulated gambling).

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
• 聽牌提示：標出打了會聽牌的牌，聽牌時把跟聽牌有關的牌標成藍底
• 顯示分析：練習時先看每張牌打出去的損失與危險、對手可能聽的牌（開過的那一局不計分）

回顧與分析
• 每一局都能回顧：神經網路標出值得看的決定，告訴你損失多少台、最佳是什麼
• 解說：有損失的決定按「解說」，用一兩句話說明該攻還是該守、哪張比較安全，再附一條打牌原則；打牌要訣和術語表隨時可查
• 期望的變化圖，點一下就跳到那個決定
• 每個選擇的期望、胡牌、放槍與被自摸的機率，以及打出去當下的危險
• 失誤門檻分入門、中級、高手
• 分支：從自己的任何一個決定改打，看結果有什麼不同，每一步都有分析
• 分析頁：擺出任何局面（手牌、副露、牌河），看每個選擇怎麼樣，還能「從這裡打」接著打下去
• 分析統計：損失來自選牌、放槍風險、吃碰槓還是胡不胡
• AI 講評：用你自己的 API 金鑰（Claude、Gemini、ChatGPT 或相容服務），讓 AI 讀同一套規則最近的牌局，找出你一再出現的習慣並解說；點代號就跳到那一手

規則
• 台數表：簡單1（南部打法）、簡單2、標準、大師（2025 麻將大師賽的台數表）
• 花牌：無花、見花見台、正花
• 每一條規則都可以調整，並附上說明；可選天聽、地聽與報聽
• 規則說明：幾頁就學會台灣麻將，還有「考考你：聽哪些牌？」
• 導覽：一分鐘認識每個畫面

連線對戰
• 開一桌：一到四人同桌，空位由神經網路電腦補上
• 同牌賽：每人各打一桌、牌都一樣，比誰得分多
• 可以透過網路上的對戰伺服器連線，也可以用「附近」功能以藍牙和 Wi-Fi 面對面連線，不需要網路
• 對戰時不顯示分析和聽牌提示；打完可以在紀錄裡回顧與分析

其他
• 每一局都存在手機上，可以收藏、看統計，也能分享或匯出
• 配色：牌桌綠、簡約，或跟隨桌布（Android 12 以上）
• 介面語言：繁體中文、英文（在系統設定的「應用程式語言」切換）
• 練習和分析完全在手機上離線運算；只有 AI 講評會連到你選的 AI 服務
• 沒有廣告、沒有內購、不需要帳號（AI 講評要用你自己在 AI 服務的金鑰，費用記在你的帳號）
• 也有網頁版，電腦也能玩，還能和 app 的玩家連線對戰：https://mahjong.1li.tw
• 開放原始碼（GPLv3）：https://github.com/kennyfs/Mahjong-TW

本遊戲不涉及任何金錢或賭博。

## en-US

**Title:** Taiwan Mahjong: AI Analysis

**Short description:**

16-tile Taiwanese mahjong against neural-network AI. Review every hand you play.

**Full description:**

Taiwanese 16-tile mahjong: practice against three computer players, review and analyze every hand you play, and play friends online or face to face.

Practice
• Computer players: a neural network, or tile efficiency (shanten and tile acceptance)
• Play one hand, one round, or a full game (four rounds)
• Slow, normal or fast pace, or manual: the computer moves only when you press Next
• Chows, pungs, kongs and wins are animated with sounds; the sounds can be turned down or off
• Ready hint: marks the discards that make your hand ready, and the tiles of a ready hand
• Show analysis: while practicing, see each discard's loss and danger and what the opponents may be waiting on (a hand played this way is not scored)

Review and analysis
• Review any hand: the neural network marks the decisions worth a look, how many tai each lost, and the best play
• Explain: on a decision that lost points, a sentence or two on whether to push or fall back and which tile is safer, with a rule of thumb; Table tips and a Glossary are always at hand
• A graph of your expected result through the hand; tap it to jump to a decision
• For each choice: the expected result, the chances of winning, dealing in and another's self-draw, and its danger
• Mistake thresholds for beginner, intermediate and expert
• Branch: play on from any of your decisions differently and see what changes, with the analysis at every step
• Analysis: set up any position (hand, melds, discards) and see how each choice does, then play it on against the computer
• Statistics: whether your losses come from tile choice, dealing in, calls, or wins passed up
• AI coach: with your own API key (Claude, Gemini, ChatGPT or a compatible service), an AI reads your recent hands of one set of rules, finds the habits that keep coming back and explains them; tap a hand's id to open that moment

Rules
• Scoring tables: Simple 1, Simple 2, Standard, Masters (the 2025 Mahjong Masters' table)
• Flowers: none, flowers count when shown, or seat flowers
• Every rule can be changed, each explained; declared ready hands (天聽, 地聽, 報聽) can be turned on
• A short guide to Taiwanese mahjong, with a quiz: which tiles does this hand wait on?
• A one-minute tour of every screen

Online play
• Open a table for one to four players; the computer fills the empty seats
• Duplicate contest: everyone plays the same tiles at their own table; the best score wins
• Play through the online server, or face to face with Nearby (Bluetooth and Wi-Fi, no internet needed)
• No analysis or hints during online play; review the hands afterwards in Records

More
• Every hand is kept on your phone: star it, see statistics, share or export it
• Colors: table green, minimal, or from your wallpaper (Android 12 and up)
• Languages: Traditional Chinese and English (switch in the system's App languages)
• Practice and analysis run entirely on your phone, offline; only the AI coach connects, to the AI service you choose
• No ads, no in-app purchases, no account (the AI coach uses your own key from an AI service, billed to you)
• A web version too, for computers, which plays online with the app: https://mahjong.1li.tw
• Open source (GPLv3): https://github.com/kennyfs/Mahjong-TW

The tiles show their traditional Chinese faces. No money or gambling of any kind is involved.

## What's new (release notes, 500 characters each)

Each version's notes are one block in Google Play's own multi-language form: copy the whole block into the release's
notes box. Inside a language, a paragraph is one line; a blank line between paragraphs is kept as written.

### 2.1.0

```text
<zh-TW>
修正部分手機在螢幕縮放或顯示大小調大時，牌桌和選單太大、最下面的「設定」被切掉的問題。電腦吃碰你打的牌，不再比吃碰其他電腦的牌等得久。神經網路的分析稍微更準：胡牌、放槍、聽牌等機率和期望得分都更貼近實際。

從回顧返回紀錄時停在原本的位置，並標出剛看過的那局；回顧顯示這局的規則。分析頁清空或隨機發牌後可以復原。出牌速度改成由慢到快排列。牌效率電腦不再為了加槓拆掉順子，向聽數不變但進張能超過兩倍時也會吃碰；術語表寫清楚牌效率怎麼吃碰槓，解說不再把跟牌效率一樣好的選擇算成失誤或好手。
</zh-TW>
<en-US>
Fixed an oversized table and menu, with Settings cut off, on some phones with a larger display size. Computer players call your discard as quickly as another's. The neural network's analysis is a little more accurate.

Back from a review, the records keep your place and mark the hand; reviews show its rules. Undo after clearing the analysis. Speeds go slowest first. The efficiency bot no longer breaks a run to kong and calls when that more than doubles its useful tiles; see the glossary.
</en-US>
```

### 2.0.0

```text
<zh-TW>
回顧新增「解說」：有損失的決定（或你比牌效率打得好的地方）按「解說」，用一兩句話說明該攻還是該守、該留哪個對子、哪張比較安全，再附一條打牌原則；解說、打牌要訣和規則說明裡有底線的詞，點了就能看定義；比牌效率打得好的地方，折線圖上也有星星。設定新增「打牌要訣」（對手聽牌了沒、什麼時候該退、哪張比較不會放槍的統計表）和「術語表」（從牌牆、搭子到向聽、筋牌，由淺到深說明）。牌河看得出摸切（橘色底）和吃碰後打的第一張（藍條），規則說明多一頁介紹。

電腦不會再在發牌完成前就出牌；音效跟畫面更同步，Android 的音效也更快出聲；發牌和胡牌的動畫更順；回顧時牌桌不再隨下方文字跳動，牌桌中間的框也不再移動。「胡」「自摸」按鈕加大；攤牌時胡的那張牌分開放；回顧不再把被別家搶先吃碰或胡走的機會算成你的失誤。
</zh-TW>
<en-US>
Reviews explain a decision: tap Explain where you lost points or beat tile efficiency (starred) for the lesson in a sentence or two. Underlined words open the new glossary. Table tips in the settings. The river marks draw-discards and the first discard after a call.

Computer players wait for the deal; sounds keep in step; smoother animations; bigger win buttons; the winning tile stands apart; a call another player beat you to is no longer your mistake.
</en-US>
```

### 1.3.0

```text
<zh-TW>
新增「AI 講評」：用你自己的 API 金鑰（Claude、Gemini、ChatGPT 或相容服務，可選模型與思考強度），讓 AI 讀同一套規則最近的牌局，找出常犯的習慣並解說，可點代號跳到那一手。紀錄頁可以匯入從別的手機、別的版本或網頁匯出的紀錄檔。修正回顧時連續快速點「上一個決定」會讓 app 當掉的問題。修正筒子的圓圈有時看起來不同心（例如七筒、八筒的內圈）。
</zh-TW>
<en-US>
New AI coach: with your own API key (Claude, Gemini, ChatGPT or a compatible service; the model and its thinking can be chosen), an AI reads your recent hands of one set of rules, finds your recurring habits and explains them; tap an id to open that moment. Records can be imported from a file exported on another phone, version or the web page. Fixed a crash when going back quickly through a review. The Dots tiles' rings are now concentric (7 and 8 Dots' inner rings were off).
</en-US>
```

### 1.2.0

```text
<zh-TW>
新增英文介面（系統設定的「應用程式語言」）。練習顯示分析時，點對手名牌上的聽牌機率會列出詳細數字和最可能放槍的牌。失誤門檻的說明更正為實際數值（約 0.5、0.25、0.1 台）。摸牌表裡會留下的牌都亮起，表格排整齊，較好的四分之一加框；中發白依習慣順序排列。吃、碰、槓動畫開始時多了提示音，所有音效稍微大聲一些。系統的顯示大小或字型調大時，有牌的畫面都不再縮小牌、字也不再重疊；窄螢幕上的副露和攤開的手牌不再被切掉。分析還沒算好時，先以灰色方塊標出位置。設定的說明改成條列、更精簡。修正胡牌動畫中間的黃點，放槍等字樣不再超出畫面。
</zh-TW>
<en-US>
English is here (in the system's App languages). With the analysis on, tap an opponent's chance of being ready to see the tiles most likely to deal in. Mistake levels show their real sizes. The draw table has columns and rings the best quarter; dragons go 中發白. Calls chime; sounds are louder. Tiles stay large at larger display or font sizes and fit narrow phones. Grey shapes stand in while the analysis loads. Shorter settings help. Fixed a dot in the win animation; call words stay on screen.
</en-US>
```

### 1.1.0

```text
<zh-TW>
練習時可以顯示分析：每張牌打出去的損失與危險、吃碰胡的損失、對手可能聽的牌，開過的那一局不計分。聽牌提示會把跟聽牌有關的牌標成藍底，自己的名牌上顯示聽牌或幾向聽。回顧的分支和分析頁的「從這裡打」每一步都有分析。設定重新整理，較長的說明收進 ⓘ。連線對戰的電腦改用神經網路，節奏和練習一樣。窄螢幕上的文字會先縮短，名字不再被截掉。
</zh-TW>
```

### 1.0.0

```text
<zh-TW>
第一個正式版。和神經網路或牌效率的電腦練習台灣十六張麻將，每一局都能回顧與分析，看哪裡打錯、損失幾台；分析頁可以擺出任何局面。台數表有簡單1、簡單2、標準、大師，花牌有無花、見花見台、正花。可以連線開一桌或比同牌賽，也能用「附近」面對面對戰。網頁版在 https://mahjong.1li.tw。
</zh-TW>
```
