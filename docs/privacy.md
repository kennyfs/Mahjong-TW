# Privacy policy / 隱私權政策

Taiwan Mahjong (台灣麻將): the Android app `io.github.kennyfs.mahjong` and the web page <https://mahjong.1li.tw>.
Last updated 2026-11-18.

## 中文

本 app 和網頁沒有帳號、廣告或當機回報，也不蒐集任何個人資料。App 沒有任何分析工具；網頁的例外見下面「網頁版」。

**手機上的資料。** 設定、暱稱和對局紀錄都只存在你的手機上，不會自動傳出去。如果你開啟了 Google 備份，Android 可能會把這些資料一起備份，大部分 app 都是這樣。只有你自己按「分享」或「匯出」時，紀錄檔才會交給你選的 app。解除安裝 app 時，這些資料會一併刪除。

**練習和分析** 完全在手機上運算，不會傳送任何資料。

**AI 講評。** 這個功能只在你自己填入 API 金鑰、並按下「送出」時才會傳送資料：
- 傳給你選的服務（Anthropic 的 Claude、Google 的 Gemini、OpenAI 的 ChatGPT，或你自己填網址的服務），用你的金鑰、記在你的帳號，資料依該服務的條款處理（例如 Gemini 的免費方案會用內容改進 Google 的產品，付費方案不會）。本專案沒有任何伺服器經手。
- 傳送的內容是你選的那些牌局的摘要：規則、每局結果、值得一提的決策（手牌、打了什麼、神經網路的數字），以及你另外打的問題；不含任何名字或暱稱。送出前可以先看完整內容。
- App：金鑰用 Android 系統的金鑰庫加密，只存在這支手機上，不會備份，也不會顯示出來；在設定裡可以隨時刪除。AI 的回答存在手機上（最近幾次）。
- 網頁版：直接從你的瀏覽器傳給該服務。金鑰和 AI 的回答（最近幾次）存在這個瀏覽器的 localStorage，金鑰沒有加密，所以公用電腦請不要存；在設定裡可以隨時刪除。

**連線對戰。** 在伺服器上開桌或加入房間時，app 會把下列資料傳給該伺服器：
- 你取的暱稱；
- 對戰協定的版本；
- 房間的 PIN 碼，或你開桌時選的規則；
- 你打牌的每個決定。

伺服器需要這些資料才能進行遊戲。資料只在房間存在期間保存在記憶體中，伺服器也不會記錄牌或暱稱。跟所有伺服器一樣，它看得到你的 IP 位址，但只在記憶體中用來限制連線數與 PIN 碼的錯誤次數。本專案的伺服器 `mahjong.1li.tw` 架在 Cloudflare 和反向代理之後，這兩者可能會跟一般網站服務一樣，保留基本的連線紀錄（IP 位址、時間）。連線全程加密（TLS）。如果你輸入的是其他伺服器的位址，該伺服器會保存什麼資料，由它的管理者決定。連上區域網路裡的伺服器時，Android 17 以上會要求「區域網路」權限，只用在這個連線上。

**附近對戰** 透過 Google 的 Nearby Connections，以藍牙和 Wi-Fi 讓手機直接互相連線。
- 你的暱稱和每個決定只會傳給同一個房間裡的其他手機。
- 它要求的權限（藍牙、附近的裝置；Android 12 以下的位置權限是 Nearby 的要求）只用在這個功能上，不會用來取得你的位置。

**網頁版** 的設定和對局紀錄存在瀏覽器的 localStorage，分析用的引擎和神經網路存在瀏覽器的快取，都不會上傳（AI 講評見上面）；連線對戰和 app 一樣。網頁由 Cloudflare 提供，Cloudflare Web Analytics 可能會在網頁中加入它的統計程式，記錄不含 cookie 的造訪資料（例如網址、來源網站、瀏覽器種類、載入時間），只用來統計流量。App 不受影響。

**兒童。** 本遊戲是麻將遊戲，不涉及任何金錢、賭博或購買，也不會刻意向任何人蒐集資料。

**聯絡方式。** 有任何問題或要求，請到 <https://github.com/kennyfs/Mahjong-TW/issues> 開 issue，或使用 Google Play 頁面上的開發者聯絡方式。本政策如有變更，會更新在這個頁面上。

## English

The app and the web page have no accounts, no ads and no crash reporting, and they collect no personal data. The
app has no analytics at all; for the web page, see "The web page" below.

**On your phone.** Your settings, your nickname and the records of your games stay on the phone and are never sent
anywhere by themselves. Android may include them in your device backup (Google's backup, if you turned it on), as it
does for most apps. A records file leaves the phone only when you tap Share or Export, to the app you choose.
Uninstalling the app deletes them.

**Practice and analysis** run entirely on the phone. They send nothing anywhere.

**The AI coach.** It sends anything only once you have entered an API key of your own and tap Send:
- It goes to the service you chose (Anthropic's Claude, Google's Gemini, OpenAI's ChatGPT, or a service at an address
  you entered), with your key, billed to your account, and is handled under that service's terms (Gemini's free
  tier, for one, uses it to improve Google's products; its paid tier does not). No server of this project is
  involved.
- What is sent is a digest of the hands you chose: the rules, each hand's result, the decisions worth a note (the
  hand, what was played, the neural network's figures), and any question you type; no names or nicknames. You can
  read all of it before sending.
- In the app, the keys are encrypted with Android's keystore, kept on this phone only, never backed up and never
  shown; the settings remove them at any time. The coach's answers are kept on the phone (the last few).
- On the web page, it goes straight from your browser to the service. The keys and the coach's answers (the last
  few) are kept in this browser's localStorage, the keys not encrypted, so don't keep one on a shared computer; the
  settings remove them at any time.

**Online battles.** When you open a table or join a room on a server, the app sends that server:
- the nickname you chose;
- the battle protocol's version;
- the room's PIN, or the rules you chose for your table;
- each of your decisions in the game.

The server needs these to run the game. It keeps them only in memory, while the room exists, and logs neither
hands nor nicknames. Like any server, it sees your IP address, and it uses it only in memory, to limit connections
and failed PIN attempts. The project's server, `mahjong.1li.tw`, is behind Cloudflare and a reverse proxy. These
may keep ordinary connection logs (IP address, time), as web services do. The connection is encrypted (TLS). If you
enter another server's address, that server's operator decides what it keeps. To reach a server on your local
network, Android 17 and later ask for the local network permission, which is used only for that connection.

**Nearby battles** connect phones directly with Google's Nearby Connections, over Bluetooth and Wi-Fi.
- Your nickname and decisions go only to the other phones in the room.
- The permissions it asks for (Bluetooth and nearby devices; on Android 12 and earlier, location, which Nearby
  requires) are used only for this, not to find your location.

**The web page** keeps its settings and records in your browser's localStorage, and the analysis engine and neural
network in the browser's cache; none of it is uploaded (for the AI coach, see above). Online battles work as in the app. The page is served through
Cloudflare, and Cloudflare Web Analytics may add its script to the page, which records cookie-free visit data (such
as the address, the referring site, the browser type and load times) only to count traffic. The app is not affected.

**Children.** This is a mahjong game with no money, gambling or purchases. It does not knowingly collect anything
from anyone.

**Contact.** Questions or requests: open an issue at <https://github.com/kennyfs/Mahjong-TW/issues>, or use the
developer contact on the app's Google Play page. If this policy changes, the change will appear on this page.
