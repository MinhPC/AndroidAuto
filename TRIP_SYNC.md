# Hành trình xe: launcher gửi dữ liệu lên Firebase, Home Assistant đọc

Launcher ghi lại mỗi chuyến đi (vị trí GPS, quãng đường, tốc độ, thông số động cơ từ OBD-II) và gửi lên
**Firebase Firestore**. Integration **Car Trips** cho Home Assistant (thư mục [home_assistant/](home_assistant/))
đọc dữ liệu đó và hiện xe trên bản đồ, km theo **ngày / tuần / tháng / năm**, chuyến gần nhất và thông số động cơ.

Code đã sẵn sàng; còn lại phần cần tài khoản Google của bạn (một lần):

## 1. Tạo project Firebase

1. Vào <https://console.firebase.google.com> → **Add project**.
2. Tên gợi ý (chọn một): `Hành trình Audi` (ID `minhphan-audi-trips`), `Car Trips` (ID `minhphan-car-trips`),
   hoặc `CarLauncher` (ID `minhphan-carlauncher`). ID phải chưa ai dùng; nếu trùng Firebase sẽ đề xuất ID khác.
3. Tắt Google Analytics (không cần).

## 2. Bật đăng nhập Google (cho launcher)

**Build → Authentication → Get started → Sign-in method → Google → Enable**, chọn email hỗ trợ, **Save**.

## 3. Tạo Firestore và đặt quy tắc bảo mật

1. **Build → Firestore Database → Create database**, chế độ **production**, vị trí `asia-southeast1` (Singapore).
2. Tab **Rules**, dán nội dung file [firestore.rules](firestore.rules), **Publish**.
   Quy tắc này chỉ cho đúng tài khoản Google mà launcher đăng nhập đọc và ghi dữ liệu của nó. Home Assistant đọc
   bằng service account (bước 6), không đi qua quy tắc này.

Không cần tạo index.

## 4. Đăng ký app launcher và tải google-services.json

**Project settings (bánh răng) → Your apps → Add app → Android**:

| App | Package name | SHA-1 |
|---|---|---|
| Launcher | `com.minhphan.launcher` | `70:03:3D:6E:21:55:DE:CE:42:55:1E:9D:1A:D7:C0:BE:95:00:9D:26` |

SHA-1 này là của khoá debug trên máy này (`~/.android/debug.keystore`), khoá đang dùng ký launcher. Ai build bằng
khoá khác thì phải thêm SHA-1 của khoá đó vào Firebase; xem bằng
`keytool -list -v -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey`.

Tải **google-services.json** và đặt vào `app/`. File này nằm trong `.gitignore`, không đưa lên GitHub. Nếu bạn bật
Google sign-in *sau* khi tải file thì phải tải lại, vì file cũ chưa có web client ID.

Không có file này, launcher vẫn build và chạy, chỉ là phần đồng bộ báo "chưa gắn với Firebase".

## 5. Build và dùng trên xe

1. Build và phát hành launcher như [RELEASING.md](RELEASING.md) (nhớ tăng `appVersionCode` / `appVersionName`).
2. Trên màn hình xe: **Cài đặt → Hành trình & đồng bộ → Đăng nhập Google** bằng tài khoản đã bật ở bước 2.
   (Cần Google Play Services trên màn hình xe.)
3. Cho phép quyền vị trí khi launcher hỏi. Khi đang ghi, có thông báo nhỏ "Đang ghi hành trình".
4. **Kiểm tra khi lái**: các dòng trạng thái ngay dưới công tắc **Ghi và gửi hành trình** cho biết dịch vụ có đang
   chạy không, lần nhận GPS cuối, lần Firebase xác nhận cuối và lý do khi Firebase từ chối (thường là
   `PERMISSION_DENIED` khi rules chưa được Publish).

## 6. Cài integration cho Home Assistant

Xem [home_assistant/README.md](home_assistant/README.md): tạo một service account **chỉ đọc**
(vai trò *Cloud Datastore Viewer*), tải khoá JSON, lấy User UID của tài khoản trên launcher, rồi thêm
integration **Car Trips** trong Home Assistant.

## Dữ liệu được lưu thế nào

```
users/{uid}/days/{yyyy-MM-dd}             tổng km, số chuyến, thời gian lái, tốc độ tối đa của ngày
users/{uid}/trips/{tripId}                một chuyến: giờ đi/đến, km, tốc độ, nhiệt độ nước/khí nạp, điện áp
users/{uid}/trips/{tripId}/chunks/{seq}   lộ trình: điểm GPS 5 giây một lần kèm thông số động cơ
users/{uid}/live/car                      vị trí, tốc độ, thông số động cơ và xăng còn lại (ước tính) của xe
users/{uid}/refuels/{id}                  một lần đổ xăng: số lít, số tiền, đầy bình hay không, km và km/lít
```

Định nghĩa nằm ở `shared/` (`Schema.kt`). Integration Home Assistant đọc đúng các trường này (`models.py`).

- **Chuyến đi** bắt đầu khi xe chạy trên 5 km/h, kết thúc khi xe đứng yên 5 phút hoặc GPS mất 5 phút. Chuyến ngắn
  hơn 100 m (xê xe trong bãi) bị bỏ. Đèn đỏ không tách chuyến.
- **Quãng đường** cộng từ khoảng cách giữa các điểm GPS, bỏ điểm sai số trên 50 m và các bước nhảy quá 250 km/h.
  Chuyến qua nửa đêm được chia km cho hai ngày.
- **Tổng ngày** được gửi dạng cộng dồn (`FieldValue.increment`) nên xoá dữ liệu app hay dùng hai thiết bị cũng không
  làm mất số km đã có trên server.
- **Chuyến kết thúc thông minh**: nếu bộ đọc OBD đang kết nối, xe dừng mà động cơ báo 0 vòng/phút (hoặc bộ đọc ngừng
  trả lời vì đã tắt khoá) trong 20 giây thì chuyến kết thúc ngay, không phải chờ 5 phút. Xe đứng nhưng động cơ vẫn nổ
  (kẹt xe, chờ trả đồ) thì chuyến được giữ mở tới 15 phút. Không có bộ đọc OBD thì vẫn kết thúc sau 5 phút đứng yên.
- **Chuyến bị bỏ dở**: nếu màn hình xe tắt cùng lúc với xe (app bị tắt đột ngột), lần mở sau launcher đánh dấu chuyến đó
  là đã kết thúc trên Firebase thay vì để "đang đi" mãi.
- **Không có mạng**: Firestore giữ dữ liệu trên màn hình xe và tự gửi khi có mạng, kể cả sau khi khởi động lại.
- **Ước lượng chi phí**: mỗi phút lái tốn khoảng 7 lần ghi; 2 giờ mỗi ngày là khoảng 850 lần ghi, thấp hơn nhiều so với
  hạn mức miễn phí (20.000 lần ghi/ngày, 50.000 lần đọc/ngày). Home Assistant đọc 4 lần mỗi lượt, cộng các ngày đã qua trong năm mỗi giờ một lần (30 giây
  một lượt khi xe chạy, 5 phút khi đỗ).
- **Riêng tư**: lộ trình chi tiết và vị trí xe nằm trên Firebase của bạn. Tắt công tắc "Ghi và gửi hành trình" trong
  Cài đặt (hoặc đăng xuất) để dừng gửi. Xoá dữ liệu cũ trong Firebase Console → Firestore.

## Hành trình thủ công và đổ xăng (màn hình Home)

Nửa phải của Home có hai ô lớn thay cho hai đồng hồ tốc độ và vòng tua (đã có trên táp lô). Cả hai dựa vào cùng một
bộ đếm km từ GPS do dịch vụ ghi hành trình chạy, nên cần bật **Ghi và gửi hành trình** và đăng nhập Google.

- **Bắt đầu hành trình / Kết thúc**: đồng hồ hành trình như trên táp lô. Bấm Bắt đầu để đếm km, thời gian và tốc độ
  trung bình; bấm Kết thúc để giữ kết quả (hiện ở "Lần trước"). Việc này độc lập với chuyến tự động gửi Firebase, và
  không gửi lên Firebase.
- **Đổ xăng**: nhập số lít và số tiền (VND) mỗi lần đổ, chọn *Đổ đầy bình* nếu đổ đến đầy. Launcher tính
  **km/lít = km đã chạy từ lần đổ đầy trước ÷ số lít** (nhiều lần đổ dở dang ở giữa được cộng vào số lít). Lần đổ
  đầy đầu tiên chỉ làm mốc; km/lít có từ lần đổ đầy thứ hai. Số km lấy từ GPS và có thể sửa ngay trong khung nhập nếu
  khác đồng hồ km của xe. Mỗi lần đổ được lưu vào `users/{uid}/refuels/{id}` và hiện thành cảm biến trong Home
  Assistant (km/lít, trung bình, giá mỗi lít, tiền xăng tháng).
- **Xăng còn lại và quãng đường còn chạy được** (ô Đổ xăng và cảm biến `sensor.car_estimated_fuel_*`): xe không cho
  đọc mức xăng qua OBD, nên launcher **ước tính**: bình đầy ở lần đổ đầy gần nhất, rồi trừ dần theo km đã chạy chia
  km/lít trung bình (cộng thêm xăng của các lần đổ dở dang). Quãng đường còn lại = xăng còn lại × km/lít. Trước khi
  có km/lít đo được (cần đổ đầy hai lần) thì tạm dùng 12 km/lít và ghi rõ "tạm tính". Đặt **dung tích bình** ở
  Cài đặt → Nhiên liệu (mặc định 50 lít, đúng với Civic đời 8). Ô đổi sang màu vàng khi còn dưới 20% và đỏ dưới 10%.
  Đây là số ước tính, nên hãy đổ đầy bình thường xuyên và nhập đúng số lít để nó bám sát thực tế; đổ đầy lại là
  đặt lại về đúng.
- Đổ xăng khi chưa đăng nhập Google được giữ trên màn hình xe và tự gửi lên Firebase ngay khi có tài khoản (lần bật ghi hành trình kế tiếp).
- Bộ đếm km không nhận xe được chở đi hay lúc GPS tắt, nên nếu đã lái mà launcher không ghi (công tắc tắt) thì hãy
  sửa số km trong khung nhập.

## Lưu ý

- Khi công tắc bật và đã đăng nhập, launcher giữ kết nối với bộ đọc OBD cả khi bạn đang dùng app khác (để ghi thông
  số động cơ), nên app khác như Torque sẽ không kết nối được cùng lúc. Tắt công tắc để nhả bộ đọc.
- Nếu tiến trình launcher bị hệ thống tắt giữa chuyến, chuyến đó dừng ở điểm cuối cùng đã gửi và chuyến mới bắt
  đầu khi launcher mở lại. Home Assistant coi một chuyến im lặng quá 10 phút là đã kết thúc.
- Khi xe dừng lại (tốc độ GPS về dưới 2 km/h), launcher gửi ngay lộ trình, chuyến và tổng ngày đang chờ, nên tắt máy
  ngay khi tới nơi cũng không mất đoạn cuối. Dừng liên tiếp cách nhau dưới 10 giây (kẹt xe nhích từng chút) chỉ tính là một lần
  gửi. Nếu màn hình xe tắt trong lúc xe còn đang chạy thì vẫn có thể mất tối đa khoảng 1 phút lộ trình cuối (điểm chờ
  gửi nằm trong bộ nhớ). Dữ liệu đã giao cho Firebase được lưu trên máy và tự gửi khi có mạng, kể cả sau khi tắt máy.
