# Bài Phân Tích Kỹ Thuật: Cơ Chế Drop Span & Cấu Hình Non-Blocking Trong Hệ Thống Giám Sát Tracing RikkeiPay Assistant

---

## 1. Bối Cảnh Nghiệp Vụ & Thách Thức Kỹ Thuật

Hệ thống **RikkeiPay Assistant** được thiết kế để xử lý hàng chục nghìn giao dịch tài chính - chuyển tiền mỗi ngày. Trong kiến trúc microservices và AI Assistant, việc tích hợp **Distributed Tracing (OpenTelemetry)** giúp đội ngũ vận hành theo dõi luồng đi của request, thời gian phản hồi (latency), và dấu vết lời gọi API/LLM đến các dịch vụ hạ tầng.

Tuy nhiên, hệ thống giám sát phân tán mang lại một rủi ro tiềm ẩn lớn: **Phụ thuộc hạ tầng giám sát (Telemetry Infrastructure Dependency)**. Nếu hạ tầng Langfuse Server (PostgreSQL, ClickHouse, OTLP Receiver) bị quá tải, nghẽn mạng hoặc ngưng hoạt động, việc truyền gửi vết Spans có thể bị chậm trễ hoặc thất bại. 

Nếu không được cấu hình bất đồng bộ và non-blocking đúng cách, hệ thống giám sát có thể trở thành **nút thắt cổ chai (single point of failure)** kéo sập toàn bộ dịch vụ ngân hàng.

---

## 2. Kiến Trúc OpenTelemetry Batch Span Processor Bất Đồng Bộ

OpenTelemetry Java SDK cung cấp hai cơ chế xử lý Span chính: `SimpleSpanProcessor` (đồng bộ) và `BatchSpanProcessor` (bất đồng bộ). Trong môi trường sản xuất của RikkeiPay Assistant, `BatchSpanProcessor` là bắt buộc.

```
 [User Transaction Request]
            │
            ▼
┌─────────────────────────┐      Non-Blocking       ┌─────────────────────────┐
│ Spring Boot Worker      │ ─── (queue.offer) ───►  │ In-Memory Bounded Queue │
│ Thread (Tomcat Pool)    │                         │  (max-queue-size: 2048) │
└─────────────────────────┘                         └─────────────────────────┘
            │                                                    │
    (Trả về HTTP 200 OK                                          │  Batch Export
     cho Khách hàng)                                             ▼ (Async Background)
                                                    ┌─────────────────────────┐
                                                    │ OTel Background Thread  │
                                                    └─────────────────────────┘
                                                                 │
                                                       (OTLP HTTP POST /traces)
                                                                 ▼
                                                    ┌─────────────────────────┐
                                                    │ Langfuse Server / OTLP  │
                                                    └─────────────────────────┘
```

### Các Tham Số Cấu Hình Quan Trọng Trong `application.yml`:

- **`max-queue-size` (`2048`)**: Kích thước tối đa của hàng đợi RAM lưu trữ các Spans hoàn thành chờ xuất dữ liệu.
- **`schedule-delay` (`5000ms`)**: Khoảng thời gian định kỳ luồng ngầm (Background Exporter Thread) gom dữ liệu và gửi tới OTLP Endpoint.
- **`max-export-batch-size` (`512`)**: Số lượng Spans tối đa được đóng gói trong một đợt export (HTTP payload size control).
- **`export-timeout` (`10000ms`)**: Thời gian timeout tối đa cho một kết nối xuất dữ liệu tới Langfuse Server.

---

## 3. Phân Tích Cơ Chế Drop Span Khi Hàng Đợi Bị Đầy (Queue Overflow & Drop Mechanics)

### 3.1. Nguyên Lý Hàng Đợi Bị Chặn (Blocking) vs Hàng Đợi Không Chặn (Non-Blocking)

Trong lập trình đa luồng Java:
- **Phương thức `put(span)` (Blocking)**: Khi hàng đợi đầy, luồng gọi (Caller Thread - ở đây là luồng xử lý giao dịch ngân hàng) sẽ bị **chặn lại (BLOCKED)** và phải chờ cho đến khi hàng đợi có khoảng trống.
- **Phương thức `offer(span)` (Non-Blocking)**: Khi hàng đợi đầy, phương thức ngay lập tức trả về `false` mà **không làm tạm dừng hay chặn** luồng gọi.

### 3.2. Cơ Chế Xử Lý Của OpenTelemetry `BatchSpanProcessor`

OpenTelemetry `BatchSpanProcessor` triển khai một hàng đợi vòng trong bộ nhớ (Bounded Queue / RingBuffer). Khi một Span kết thúc (`span.end()`):

1. Luồng xử lý giao dịch đẩy Span vào hàng đợi RAM thông qua phương thức non-blocking `queue.offer(span)`.
2. **Kịch bản Hàng Đợi Còn Chỗ (< 2048 spans)**: Span được đẩy thành công vào hàng đợi. Luồng giao dịch hoàn thành và trả kết quả cho người dùng trong vài mili-giây.
3. **Kịch bản Hàng Đợi Bị Đầy (Queue Full = 2048 spans)**:
   - `queue.offer(span)` thất bại (trả về `false`).
   - SDK thực hiện **Cơ Chế Drop Span**: Hủy bỏ Span mới nhất (hoặc Span cũ nhất tùy chiến lược), đồng thời tăng biến đếm `otel.bsp.dropped_spans` trong Micrometer Metrics.
   - **Luồng giao dịch tài chính KHÔNG BỊ ẢNH HƯỞNG**: Tiếp tục thực thi bình thường mà không hề bị delay hay sụt giảm hiệu năng.

---

## 4. Tại Sao Cơ Chế Non-Blocking Là Bắt Buộc Để Bảo Vệ Luồng Giao Dịch Ngân Hàng?

### 4.1. Bảo Đảm Thời Gian Phản Hồi (SLA Latency) Cho Giao Dịch Tài Chính
Giao dịch thanh toán/chuyển tiền trên RikkeiPay yêu cầu độ trễ cực thấp (< 100ms). Nếu sử dụng cơ chế đồng bộ hoặc blocking, khi Langfuse Server gặp sự cố mạng (network partition) hoặc giật lag, thời gian phản hồi của mỗi giao dịch sẽ bị cộng thêm thời gian chờ timeout OTLP (ví dụ: 10 giây). Điều này khiến trải nghiệm người dùng bị phá hủy hoàn toàn.

### 4.2. Phòng Chống Tình Trạng Nghẽn Dây Chuyền (Cascading Failure & Thread Pool Exhaustion)
Spring Boot sử dụng Tomcat Thread Pool (mặc định 200 worker threads). 
- Nếu 200 threads này bị **blocking** do đợi hàng đợi Tracing giải phóng hoặc đợi kết nối OTLP sang Langfuse Server, toàn bộ worker threads sẽ rơi vào trạng thái `WAITING` / `TIMED_WAITING`.
- Kết quả: Server không còn thread nào để tiếp nhận giao dịch mới, gây ra hiện tượng **Cascading Failure (Sụp đổ dây chuyền)** và đứt gãy toàn bộ dịch vụ ngân hàng (Denial of Service).

### 4.3. Nguyên Tắc Cô Lập Lỗi (Fault Isolation / Bulkheading)
Trong thiết kế hệ thống phân tán chuẩn ngân hàng:
> **"Dữ liệu giám sát (Telemetry / Tracing) chỉ là thông tin bổ trợ (Non-critical Path). Không bao giờ được phép để sự cố của hệ thống giám sát làm hỏng hoặc ngưng trệ luồng nghiệp vụ cốt lõi (Core Business Transaction)."**

Việc hy sinh một số bản ghi Traces (Drop Spans) trong thời điểm Langfuse quá tải để giữ cho luồng chuyển tiền của khách hàng thông suốt là quyết định kiến trúc bắt buộc và tối quan trọng.

---

## 5. Kết Luận

Cấu hình OpenTelemetry Batch Span Processor bất đồng bộ kết hợp với cơ chế **Drop Span khi đầy hàng đợi (max-queue-size: 2048)** cung cấp một lá chắn phòng thủ vững chắc cho **RikkeiPay Assistant**. Nó đảm bảo hệ thống đạt được cả 2 mục tiêu:
1. **Giám sát toàn diện**: Thu thập chi tiết Tracing khi hạ tầng hoạt động bình thường.
2. **Kháng lỗi tối đa**: Tự động giải phóng áp lực RAM và bảo vệ 100% tính sẵn sàng của luồng giao dịch tài chính khi hạ tầng giám sát gặp sự cố.
