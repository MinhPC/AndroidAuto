# Car Trips: xe của bạn trong Home Assistant

Custom integration đọc dữ liệu mà launcher trên xe gửi lên Firebase Firestore (xem [../TRIP_SYNC.md](../TRIP_SYNC.md))
và hiện lên Home Assistant. Cần Home Assistant 2025.2 trở lên. Không cài thêm thư viện nào.

## Thực thể

Mọi thực thể thuộc một thiết bị (tên bạn đặt lúc thêm, mặc định "Car").

| Thực thể | Nội dung |
|---|---|
| `device_tracker.car` | Vị trí xe trên bản đồ; HA tự tính "home / not_home" như với điện thoại. Có thuộc tính `speed`, `moving`, `last_seen` và `google_maps_url` (link mở Google Maps đúng vị trí xe). |
| `binary_sensor.car_driving` | Bật khi xe đang chạy. |
| `sensor.car_distance_today` / `_this_week` / `_this_month` / `_this_year` | Quãng đường (km). Tuần tính từ thứ Hai đến Chủ nhật. Dạng `total_increasing`: về 0 khi sang kỳ mới, dùng được cho thống kê dài hạn. |
| `sensor.car_trips_today`, `sensor.car_driving_time_today`, `sensor.car_top_speed_today` | Số chuyến, thời gian lái (phút), tốc độ tối đa của hôm nay. |
| `sensor.car_speed`, `_engine_speed`, `_coolant_temperature`, `_intake_temperature`, `_battery_voltage`, `_fuel_trim` | Tốc độ và thông số động cơ trong lần gửi gần nhất. Khi xe đỗ, launcher không gửi thông số động cơ nên các cảm biến này hiện *unknown*. |
| `sensor.car_engine_load`, `_throttle_position` | Như trên, mặc định tắt (bật trong trang thực thể). |
| `sensor.car_last_trip_distance`, `_driving_time`, `_average_speed`, `_top_speed`, `_start`, `_end` | Chuyến gần nhất: đang đi thì là chuyến hiện tại, đỗ thì là chuyến vừa xong. `_end` là *unknown* khi chuyến chưa kết thúc. Cảm biến quãng đường có thuộc tính toạ độ điểm đi/đến, `ongoing` và `google_maps_route_url` (link mở lộ trình trên Google Maps). |
| `sensor.car_last_trip_max_coolant`, `_max_intake` (mặc định tắt) | Nhiệt độ nước / khí nạp cao nhất của chuyến gần nhất. |
| `sensor.car_fuel_economy`, `_average_fuel_economy` | Mức tiêu hao (km/lít) của lần đổ đầy gần nhất và trung bình (tổng km chia tổng lít). Chỉ có sau khi đổ đầy bình hai lần trên launcher. |
| `sensor.car_last_fill_up_litres`, `_cost`, `_price_per_litre`, `_time` | Số lít, số tiền (VND), giá mỗi lít và thời điểm của lần đổ xăng gần nhất, do bạn nhập ở nút **Đổ xăng** trên launcher. *unknown* cho tới lần đổ đầu tiên. |
| `sensor.car_fuel_cost_this_month` | Tổng tiền xăng đã ghi trong tháng này (VND). |
| `sensor.car_last_update` | Lần cuối HA nhận được dữ liệu từ xe (chẩn đoán). |

## Cài đặt

1. Chép thư mục `custom_components/car_trips` vào `<thư mục cấu hình HA>/custom_components/` rồi khởi động lại HA.
2. **Tạo service account chỉ đọc** cho HA (khuyên dùng thay vì khoá quản trị mặc định của Firebase):
   - Vào <https://console.cloud.google.com/iam-admin/serviceaccounts>, chọn đúng project Firebase.
   - **Create service account**, tên `ha-car-trips`.
   - Ở bước cấp quyền, chọn vai trò **Cloud Datastore Viewer** (`roles/datastore.viewer`). Vai trò này chỉ cho đọc.
   - Mở service account vừa tạo → tab **Keys** → **Add key → Create new key → JSON** và lưu file.
3. Lấy **User UID**: Firebase console → **Authentication → Users**, cột *User UID* của tài khoản Google mà launcher
   đang đăng nhập.
4. Trong HA: **Cài đặt → Thiết bị & dịch vụ → Thêm tích hợp → Car Trips**. Dán toàn bộ nội dung file JSON, nhập User
   UID, đặt tên xe.

Nếu khoá bị xoá hoặc thay, HA sẽ hiện thông báo yêu cầu đăng nhập lại; dán khoá mới là xong.

## Thử ngay với dữ liệu mẫu (không cần Firebase)

`tools/mock_firestore.py` giả làm Firestore của Google và trả về một chiếc xe mẫu, để bạn xem toàn bộ thực thể trong
Home Assistant trước khi có xe thật hay project Firebase.

1. Trên một máy tính Home Assistant nối tới được (cùng mạng nhà), cài `aiohttp` và `cryptography`
   (`pip install aiohttp cryptography`) rồi chạy:

   ```
   cd home_assistant/tools
   python mock_firestore.py --scenario driving
   ```

   - `--scenario driving`: xe **đang chạy**. Nó thật sự di chuyển trên một vòng quanh Hà Nội, tốc độ lúc nhanh lúc
     chậm, vòng tua và nhiệt độ thay đổi; bản đồ HA và các cảm biến đổi theo mỗi 30 giây.
   - `--scenario parked`: xe **đang đỗ**, chuyến gần nhất vừa kết thúc cách đây 40 phút.
   - Cả hai có sẵn 200 ngày lịch sử (đi làm ngày thường, đi chơi cuối tuần) nên km tuần / tháng / năm đều có số.
   - Máy chủ ghi ra file `mock_service_account.json` và in địa chỉ của nó. Nếu tự đoán sai địa chỉ mạng, thêm
     `--public-host <địa chỉ IP của máy tính>`. Đổi `--port` nếu 8765 đã bị dùng.
   - Múi giờ mặc định là UTC+7; nếu HA dùng múi giờ khác, thêm `--utc-offset`.

2. Trong HA: **Thêm tích hợp → Car Trips**, dán **toàn bộ nội dung** `mock_service_account.json`, nhập User ID
   `sample-car` (hoặc giá trị `--uid`), đặt tên xe.

3. Sau khi thử xong: xoá tích hợp trong HA, Ctrl+C để dừng máy chủ. Máy chủ chỉ giữ dữ liệu trong bộ nhớ, không ghi gì
   lên đĩa hay Internet; khoá trong file là khoá giả chỉ dùng được với máy chủ này.

Ghi chú: khoá mẫu có project `car-trips-mock` và trường `api_endpoint`; integration chỉ theo `api_endpoint` cho đúng
project này, nên một khoá thật không thể bị chỉ sang máy chủ khác.

## Cách hoạt động

- HA đọc Firestore qua REST bằng chính khoá service account (tự ký JWT bằng PyJWT có sẵn trong HA), không dùng thư
  viện Google nào.
- Mỗi lượt đọc gồm: vị trí xe, tổng hôm nay, chuyến gần nhất và lần đổ xăng mới nhất (4 lần đọc). Khi có lần đổ xăng mới, HA đọc lại 20 lần đổ gần nhất để tính km/lít trung bình và tiền xăng tháng. Tổng tuần/tháng/năm được cộng từ các ngày
  đã qua: Firestore không cộng dồn được theo dải ngày nếu không có index mà nó không hỗ trợ, nên HA đọc các ngày từ đầu
  năm đến hôm qua (mỗi ngày một lần đọc, tối đa khoảng 365), giữ trong bộ nhớ và chỉ đọc lại mỗi giờ hoặc khi sang ngày mới.
- **Xe chạy: 30 giây một lượt; xe đỗ: 5 phút một lượt.** Xe chạy suốt tốn khoảng 11.500 lần đọc một ngày cho các lượt,
  cộng tối đa khoảng 8.800 lần đọc các ngày đã qua (cuối năm), tổng khoảng 20.000, thấp hơn hạn mức miễn phí
  50.000 lần/ngày.
- Nếu xe mất điện giữa chuyến, launcher không kịp báo "đã đỗ". HA coi vị trí im lặng quá 2 phút là *không chạy* và
  chuyến im lặng quá 10 phút là *đã kết thúc*.

## Ví dụ

Nút mở lộ trình chuyến gần nhất và vị trí xe trên Google Maps (thẻ Markdown):

```yaml
type: markdown
content: >
  {% set route = state_attr('sensor.car_last_trip_distance', 'google_maps_route_url') %}
  {% if route %}[Xem lộ trình chuyến gần nhất trên Google Maps]({{ route }}){% endif %}

  [Xe đang ở đâu?]({{ state_attr('device_tracker.car', 'google_maps_url') }})
```

Link lộ trình không cần khoá API. Nó gồm điểm đi, điểm đến và tối đa 8 điểm dọc đường lấy từ dữ liệu GPS của chuyến, rồi Google
tự vẽ đường đi giữa các điểm đó. Vì vậy đường vẽ bám theo đường phố và gần đúng, không trùng từng mét với đường xe đã chạy.
Mỗi lần tạo link tốn khoảng 10 lần đọc Firestore: một chuyến đã kết thúc chỉ đọc một lần, chuyến đang đi đọc lại mỗi 5 phút.
Muốn xem đường xe đã đi trên bản đồ của Home Assistant, dùng thẻ bản đồ bên dưới với `hours_to_show`.

Thẻ bản đồ:

```yaml
type: map
entities:
  - entity: device_tracker.car
hours_to_show: 24  # vẽ đường xe đã đi trong 24 giờ qua
```

Thống kê quãng đường theo tháng:

```yaml
type: statistics-graph
entities:
  - sensor.car_distance_today
stat_types: [sum]
period: day
days_to_show: 30
```

Thông báo khi xe bắt đầu chạy và khi đỗ:

```yaml
automation:
  - alias: Xe bắt đầu chạy
    triggers:
      - trigger: state
        entity_id: binary_sensor.car_driving
        to: "on"
    actions:
      - action: notify.notify
        data:
          message: "Xe đang chạy"
  - alias: Xe đã đỗ
    triggers:
      - trigger: state
        entity_id: binary_sensor.car_driving
        from: "on"
        to: "off"
    actions:
      - action: notify.notify
        data:
          message: >-
            Xe đã đỗ. Chuyến vừa rồi {{ states('sensor.car_last_trip_distance') }} km,
            {{ states('sensor.car_last_trip_driving_time') }} phút.
```

## Khắc phục sự cố

| Thông báo khi thêm | Nguyên nhân |
|---|---|
| Google không chấp nhận khoá | Service account hoặc khoá đã bị xoá; dán đúng file JSON. |
| Không được đọc Firestore | Service account chưa có vai trò *Cloud Datastore Viewer*. |
| Không đọc được Firestore | Mất mạng, hoặc project chưa tạo Firestore (Firebase console → Firestore Database). |

- Mọi thứ *unknown* dù đã cấu hình xong: xe chưa gửi gì. Kiểm tra launcher đã đăng nhập, công tắc "Ghi và gửi hành
  trình" bật, và UID nhập đúng.
- Km trong ngày lệch: ngày được tính theo múi giờ của HA, launcher tính theo múi giờ của màn hình xe; đặt cùng múi
  giờ.

## Chạy test

```
pip install pytest-homeassistant-custom-component
cd home_assistant
pytest
```

Bộ test dựng một Home Assistant thật, thay Firestore bằng câu trả lời mẫu và kiểm tra thực thể, luồng cấu hình và các
tình huống lỗi; một nhóm test còn chạy integration với `tools/mock_firestore.py` qua HTTP thật. Trên Windows `tests/conftest.py` đã xử lý sẵn các khác biệt của vòng lặp sự kiện.
