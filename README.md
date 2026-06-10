# MotionClip Android 測試 App — 開發者 README

> 對象：接手本專案的**工程師**（非一般使用者）。
> 目的：從零 clone 專案到成功 build、執行的完整指引，並記錄已知的環境坑。
> 專案性質：SBIR 案，ESP32-S3 (MotionClip) 透過 Wi-Fi UDP 發送 IMU 資料，本 App 為接收端測試工具。

---

## 1. 這個 App 是什麼

接收 ESP32-S3 透過 Wi-Fi AP 以 UDP 發送的 IMU 感測資料（加速度三軸、陀螺三軸、溫度），即時顯示數值、累計封包數與到達頻率。內建「Demo 模式」可在無實體裝置時以模擬資料展示。

技術棧：Kotlin + Jetpack Compose，MVVM（ViewModel + StateFlow）。

主要檔案（位於 `app/src/main/java/com/example/motioncliptest/`）：
- `ImuData.kt`：一筆 IMU 資料的資料類別（7 欄）。
- `UdpViewModel.kt`：UDP socket 收發、Wi-Fi 綁定、解析、狀態管理、Demo 模式。
- `MainActivity.kt`：Compose UI。

---

## 2. 環境需求

| 項目 | 版本 / 需求 |
|---|---|
| Android Studio | Koala 2024.1.1 或更新（本專案以此版開發） |
| JDK | 17（Android Studio 內建即可） |
| Android Gradle Plugin (AGP) | **8.5.0**（勿任意升級，見第 5 節） |
| Gradle | 對應 AGP 8.5.0 的版本（隨專案 wrapper，勿手動改） |
| compileSdk / targetSdk | **34** |
| minSdk | **29（Android 10）**，因雙網路綁定 API 需要 |
| 測試裝置 | Android 10 以上實機（實機測試必須），或模擬器（僅能跑 Demo 模式） |

---

## 3. 從零 build 的步驟

1. 安裝 Android Studio（developer.android.com/studio），首次啟動會引導安裝 SDK。
2. clone 專案：
   ```
   git clone https://github.com/WeCatchUp-tw/<本專案 repo 名稱>.git
   ```
   （實際 repo 名稱請向交接窗口確認；舊的 TCP 版 repo 為 `imu-demo-sbir`，已廢棄，非本專案。）
3. Android Studio → File → Open，選擇 clone 下來的專案根目錄。
4. 等待第一次 Gradle Sync 完成（會從網路下載依賴，需數分鐘，需穩定網路）。
5. Sync 成功後，選擇模擬器或實機，按 Run（▶）。

---

## 4. 如何驗證 App 正常

**模擬器（無實機時）**：
- App 啟動後按「Demo 模式（無裝置）」。
- 7 欄數值應開始跳動、累計封包數上升、到達頻率顯示約 400 Hz 上下。
- 按「停止接收」數值停住。
- 註：模擬器無實體 Wi-Fi，**無法**連 ESP32，「開始接收」在模擬器上不會有資料，此為正常。

**實機（真實連線）**：
1. 手機 Wi-Fi 連上 ESP32 的 AP（SSID `MotionClip_xxxx`，密碼 `motionclip`）。
2. 手機需有 SIM 卡且行動網路開啟（AP 不通外網，見第 6 節）。
3. App 按「開始接收」，應看到真實 IMU 數值與頻率。

---

## 5. 已知環境坑（務必先讀，可省下數小時）

這些是開發過程實際踩過的雷：

**坑 1：依賴版本不可任意拉新**
本專案 compileSdk 34 / AGP 8.5.0。若把 androidx 依賴升到要求 compileSdk 35/36 的新版（如 Compose 1.9.x、activity 1.12.x、core 1.17.x、lifecycle 2.10.x），Gradle 會在 `checkDebugAarMetadata` 報「requires compile against version 35/36」而 build 失敗。
→ 解法：維持 `gradle/libs.versions.toml` 現有版本（Compose BOM 2024.09.00、core-ktx 1.13.1、activity-compose 1.9.2、lifecycle 2.8.6）。若要升級依賴，須同步升 compileSdk 與 AGP，會牽動較多，非必要勿動。

**坑 2：build 出現「檔名含空格」錯誤**
若報 `' ' is not a valid file-based resource name`（例如 `ic_launcher 2.xml`），多為 macOS 產生的重複副本檔。
→ 解法：先 Build → Clean Project 再 Rebuild。若仍存在，於專案目錄執行 `find app/src -name "* *"` 找出含空格的檔並刪除多餘副本（保留正本）。

**坑 3：deprecated 警告非錯誤**
`Divider`（建議改 `HorizontalDivider`）、`ConnectivityManager.allNetworks` 等會出現 deprecated 黃色警告，但功能正常、不影響 build 與執行，可暫不處理。

---

## 6. 連線與封包規格（對接 ESP32 用）

| 項目 | 值 |
|---|---|
| ESP32 AP IP | 192.168.4.1 |
| ESP32 Port | 12345 |
| Client 本地接收 Port | **12345（必須固定，不可用隨機 port）** |
| SSID / 密碼 | MotionClip_xxxx / motionclip |
| 編碼 | UTF-8 |
| 開始指令 | start（5 bytes） |
| 停止指令 | stop（4 bytes） |
| 封包格式 | UTF-8 逗號分隔 7 欄：accel.x, accel.y, accel.z, gyro.x, gyro.y, gyro.z, temperature |
| 小數位數 | 六軸 4 位、溫度 2 位 |

**實作關鍵（已驗證的眉角）：**
- 本地 port 必須固定綁 12345，否則只收得到確認包、收不到資料串流。
- UDP socket 必須 `bindSocket` 綁定 Wi-Fi 網路介面，否則手機在雙網路下可能將封包從行動網路送出，**無聲收不到資料**（最易誤判的坑）。
- 高頻接收（實測 ~500Hz）須在背景執行緒，不可阻塞主執行緒。

---

## 7. 待辦與已知限制（交接重點）

- **實機測試未完成**：開發期間無 Android 實機，真實連線、雙網路綁定、500Hz 效能均未在實機驗證。Demo 模式邏輯已驗。
- **去重（dedup）尚未實作**：實測約每 3 包有 1 個重複值（真實資料率 ~336Hz）。本版顯示「到達頻率」（含重複）符合 PRD；若需真實資料率，需加 dedup（重複週期勿寫死，根因尚未與硬體端對齊）。
- **收包率無法精算**：封包無序號，且硬體端（律動 Vic）不願加序號欄位。收包率僅能估計。若驗收需收包率，建議改以「實測可達頻率」為主指標。
- **對接窗口**：律動 Vic，目前由 Penny 代為聯繫。

---

## 8. 相關文件

- 技術確認文件、PRD、Python 桌機測試腳本（udp_test.py）與實測結果（udp_test_result.md）、硬體架構圖：見交接文件與 Notion。
