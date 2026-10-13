# DualCam

Ứng dụng Android chụp ảnh / quay video bằng **cả camera trước và sau cùng lúc**, ghép trong CÙNG MỘT khung hình.

- **Ảnh:** camera sau toàn khung, camera trước ở góc trên bên phải (bo góc, viền trắng) → 1 file JPEG.
- **Video:** ghép theo thời gian thực bằng OpenGL → 1 file MP4 duy nhất (có âm thanh từ micro).
  Chọn độ phân giải: 480p / 720p / 1080p (nút "Độ phân giải" ở chế độ Video).
- Yêu cầu: Android 11+ và điện thoại hỗ trợ concurrent camera.

## Build bằng GitHub Actions

1. Tạo repo mới trên GitHub, upload toàn bộ nội dung thư mục này (giữ nguyên thư mục `.github`).
2. Vào tab **Actions** → workflow **Build APK** tự chạy khi push (hoặc bấm *Run workflow*).
3. Chạy xong, mở lần chạy đó → **Artifacts** → tải `DualCam-debug-apk` → giải nén lấy file `.apk`.

Không cần gradle wrapper vì workflow tự cài Gradle 8.7 và JDK 17.

## Cấu trúc code
- `MainActivity.kt` – giao diện, chế độ ảnh/video, chọn độ phân giải
- `GlCompositor.kt` – nhận 2 luồng camera, ghép khung hình bằng OpenGL (xem trước + mã hóa)
- `VideoRecorder.kt` – mã hóa H.264 + AAC và ghi file MP4
