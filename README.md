# ✦ XLRW 3D-PRINTER CLIENT (Fabric 1.21.1)

> **Client Mod mô phỏng Máy In 3D tự động: Tự động di chuyển (Nozzle), tự động chuyển tầng, tự leo/đặt dàn giáo (Scaffolding Elevator) và tự động đi tiếp tế nguyên liệu từ rương (Material Chest Refill).**

---

## 🌟 Tính năng mới: Hệ thống Máy In 3D Tự Động Toàn Diện

### 1. 🔄 Tự động chuyển tầng (Auto Layer Advance)
- Khi toàn bộ khối trên tầng hiện tại (`currentLayer`) được xây xong, hệ thống sẽ **tự động tăng tầng** (`currentLayer++`) và tiếp tục in tầng tiếp theo cho đến khi hoàn thành 100% công trình.
- Không còn tình trạng đứng im sau khi xong tầng 1!

### 2. 🧰 Hệ thống Rương Tiếp Tế Nguyên Liệu (Material Supply Chest)
- Người chơi có thể chỉ định rương nguyên liệu cực kỳ dễ dàng:
  - **Cách 1**: Đứng nhìn thẳng vào rương nguyên liệu trong game, bấm **`V`** mở giao diện, chuyển sang Tab **`⚡ 3D Printer & Chest`** và bấm **[Set to Target Block]**.
  - **Cách 2**: Đứng cạnh rương và bấm **[Set to My Position]**.
- **Cơ chế hoạt động**:
  - Khi túi đồ hết sạch loại khối cần thiết cho tầng đang xây, máy in sẽ tự động chuyển trạng thái `TRAVELING_TO_CHEST`.
  - Người chơi tự động đi đến rương, mở rương ra và tự động Shift-click các khối cần thiết cho công trình vào túi đồ.
  - Sau khi nạp đủ nguyên liệu, rương tự đóng lại và người chơi quay về tiếp tục in!

### 3. 🏗️ Trụ Dàn Giáo Thang Máy (Scaffolding Elevator)
- Khi tầng xây dựng lên cao hơn tầm với của người chơi:
  - Máy in sử dụng trụ dàn giáo (`minecraft:scaffolding`) như trục nâng Z của máy in 3D.
  - Tự động đặt thêm khối dàn giáo nâng độ cao của cột lên tầng tương ứng.
  - Tự động leo lên (`hold jump`) để đứng trên độ cao làm việc của tầng mới.
  - Khi cần đi về rương tiếp tế, người chơi tự động tụt xuống an toàn (`hold sneak`).

### 4. 🤖 Đầu Phun Tự Động Di Chuyển (Autonomous Nozzle Movement)
- Người chơi tự động di chuyển nhẹ nhàng tiếp cận các khối chưa xây trên tầng hiện tại với tính năng chống rơi (tự động Sneak khi đến mép sàn).
- Khi đã đến tầm với (reach distance), hệ thống dừng lại, xoay tâm ngắm mượt mà (Smooth Aim) và đặt khối.

### 5. 🧹 Tối giản màn hình (Đã bỏ HUD góc trái)
- Đã gỡ bỏ thanh hiển thị HUD ở góc trái phía trên màn hình theo yêu cầu. Màn hình game của bạn giờ đây hoàn toàn sạch sẽ, không bị che khuất tầm nhìn, chỉ hiển thị thế giới sắc nét và khung hologram 3D.

---

## 🎨 Hướng dẫn sử dụng Menu XLRW (Bấm `V`)

- **Tab 📁 Schematics**: Chọn và nạp công trình từ thư mục `schematics/`.
- **Tab 🎯 Position & Layer**: Căn tọa độ Anchor, xoay 90°, bật/tắt **Auto Layer Advance**, điều chỉnh tầng thủ công.
- **Tab ⚡ 3D Printer & Chest**:
  - Bật/tắt **Auto Move (Nozzle)**.
  - Bật/tắt **Scaffolding Elevator** và chọn vị trí trụ dàn giáo.
  - Bật/tắt **Auto Refill Chest** và đặt vị trí Rương Nguyên Liệu.
  - Điều chỉnh tốc độ in Delay (ms) và Jitter (±ms).
- **Tab 🔮 Visuals**: Bật/tắt Hologram 3D, viền khối mục tiêu (Target Box), viền rương xanh lá (Supply Chest Box) và viền trụ dàn giáo (Scaffold Pillar Box).

---

## ⌨️ Phím tắt mặc định

| Phím | Chức năng |
| :--- | :--- |
| **`B`** | Bật / Tắt chế độ Máy In 3D (Start / Stop) |
| **`V`** | Mở bảng điều khiển **XLRW 3D-Printer** |
| **`P`** | Đặt 1 khối tiếp theo (Manual Step) |
| **`PAGE UP`** / **`PAGE DOWN`** | Tăng / Giảm tầng đang xây |

---

## 🚀 Cài đặt

File JAR sẵn sàng:
👉 `build/libs/xlrw-1.0.0.jar`
Copy vào `.minecraft/mods` và chạy với **Fabric Loader 1.21.1** + **Fabric API**.

---

## 🛠️ Bản sửa lỗi điều hướng & giàn giáo (v1.1)

**Bot bị lạc / chạy lòng vòng**
- Bộ lập kế hoạch giờ quét *một lần* toàn bộ khu vực đi tới được (Dijkstra, cùng luật di chuyển với lúc đi thật), rồi chọn chỗ đứng theo **chi phí đi bộ thực tế** chứ không theo khoảng cách đường chim bay. Không còn chọn chỗ đứng mà đi không tới.
- Phần đi bộ đi đúng đường đã tính: chỗ đường đi bảo nhảy xuống (1–3 block) thì nhảy, mép còn lại thì **giữ Shift** để vanilla giữ người lại (thay vì đứng đơ rồi huỷ kế hoạch).
- Phát hiện vật cản theo hướng đang đi thay vì hướng camera; phím di chuyển được chiếu theo góc nhìn nên vừa xoay vừa đi vẫn đúng hướng.
- Biết leo xuống giàn giáo (giữ Shift khi điểm tiếp theo nằm ngay bên dưới).

**Giàn giáo sai / spam**
- Chỉ dựng trụ khi **không có block nào** của tầng đặt được từ mặt đất.
- Vị trí trụ được chọn sát block cần đặt (bán kính 3), và **đỉnh trụ được kiểm tra trước** là đặt được block đó. Độ cao trụ thấp nhất có thể.
- Ưu tiên dùng lại trụ cũ đã dựng sẵn; trụ không tiến triển trong 5 giây sẽ bị bỏ và đánh dấu xấu.
- Bỏ giai đoạn "bắc cầu giàn giáo ngang" hay lỗi; giờ đứng thẳng trên đỉnh trụ.

**Đặt glass / block tạm vô ích**
- Chỉ bắc cầu (sneak-bridge) khi thật sự không có đường, khe 1–2 block, mục tiêu không thấp hơn, và không đè lên ô của công trình.
- "Gỡ kẹt" không còn đặt block bừa dưới chân hay phá tường của công trình; chỉ kê chân khi kẹt trong hố thật sự.
- Mọi block tạm (cầu, kê chân) được ghi lại và **tự dọn khi xong một tầng** (`cleanupTempBlocks` trong `config/xlrw.json`).
- Block tạm/giàn giáo nằm ngoài công trình không còn bị coi là "đặt sai" (trước đây có thể phá cả đất).


---

## 🧱 v1.2: Gom việc theo chỗ đứng, đặt xuyên tường, chạy thử

**Hết cảnh "leo trụ đặt 1 block rồi xuống dựng trụ khác"**
- Khi chọn chỗ đứng (dưới đất hoặc trên đỉnh trụ), bot đếm xem từ đó **với tới thêm bao nhiêu block khác**. Chỗ làm được cả một mảng sẽ thắng chỗ gần hơn một chút mà chỉ làm được đúng 1 block.
- Đứng trên mặt tầng vừa xây, bot giờ **nối dài tầng sang ngang** được (click vào mặt bên của block dưới chân), thay vì leo xuống dựng trụ mới.

**Đặt xuyên tường ở mọi nơi trong tầm với** (`throughWallAnywhere`, nút **WALL** trong tab Engine)
- Server vanilla chỉ kiểm tra khoảng cách và điểm click nằm trên block, không kiểm tra tầm nhìn hay hướng của mặt block.
- Bot vẫn ưu tiên cách đặt có tầm nhìn; chỉ dùng xuyên tường khi không còn cách nào khác.
- **Tắt WALL trên server có anti-cheat kiểm tra tầm nhìn.** Khi tắt, bot quay về chế độ cũ (chỉ xuyên tường ở ô bị bao kín, cách nhau 1,5 giây).

**Chạy thử / Dry run** (phím **`N`** hoặc nút **◎** trong tab Engine)
- Mô phỏng toàn bộ phần còn lại của công trình trên bản sao thế giới, **không gửi gói tin nào** lên server, không làm game bị khựng (chia nhỏ qua từng tick).
- In ra chat: thời gian ước tính, số lần đặt, số chỗ đứng, quãng đường đi bộ, số trụ và số khối giàn giáo (so với số đang có), vật liệu còn thiếu trong túi.
- Đánh dấu trong thế giới: 🟥 block bot không với tới được, 🟧 block lơ lửng cần cột đỡ, 🟨 vị trí trụ giàn giáo, 🟦 các chỗ đứng.
- Bấm `N` lần nữa để tắt khung đánh dấu. Chi tiết từng block bị kẹt được ghi vào `logs/xlrw_debug.log` (mục `DRYRUN/STUCK`).


---

## 🧩 Hỗ trợ cả Minecraft 1.21.1 và 1.21.11 (Stonecutter)

Một bộ code, mỗi lần build ra **hai file JAR**:

| Phiên bản game | File JAR |
| :--- | :--- |
| 1.21.1 (Fabric API 0.116.17) | `versions/1.21.1/build/libs/xlrw-1.2.0+1.21.1.jar` |
| 1.21.11 (Fabric API 0.141.6) | `versions/1.21.11/build/libs/xlrw-1.2.0+1.21.11.jar` |

- Build cả hai: `gradlew build` (cần JDK 21). Hoặc `gradlew buildAndCollect` để gom hai file vào `build/libs/1.2.0/`.
- Code trong `src/` viết cho 1.21.1. Chỗ nào API khác nhau thì dùng chú thích Stonecutter (`//? if >=1.21.11 { ... }`), phần lớn gom trong `com.xlrw.compat` (`MC`, `InputCompat`, `NbtCompat`, `GuiCompat`, `RenderCompat`).
- Mỗi lần đẩy code lên GitHub, Actions tự build cả hai bản; log build nằm ở nhánh `ci-logs`.

**Khác biệt trên 1.21.11** (do Mojang viết lại bộ vẽ):
- Bảng điều khiển 3D (nút 3D UI) nghiêng kiểu 2D (co + xiên theo chuột) vì GUI mới không còn phối cảnh thật.
- Bản xem trước 3D trong menu vẽ bằng các ô màu (vẫn xoay được), thay vì mô hình khối thật.
- Mũi tên kéo/xoay của trình chỉnh vị trí có thể bị khối che khuất.
