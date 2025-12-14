# Hệ thống Chia sẻ File P2P - Tài liệu API Backend

## Tổng quan

API này cung cấp dịch vụ backend cho hệ thống chia sẻ file Peer-to-Peer (P2P) với chức năng chat. Máy chủ chạy trên `http://localhost:8080` sử dụng JSON để trao đổi dữ liệu.

### Xác thực
- Không yêu cầu token xác thực.
- Quản lý tên người dùng đảm bảo mỗi peer có danh tính duy nhất.
- Kết nối với máy chủ tracker được yêu cầu cho hầu hết các thao tác.

### Định dạng dữ liệu
- Tất cả yêu cầu và phản hồi sử dụng định dạng JSON.
- Ngày/Thời gian là mili giây kể từ epoch (thời gian Unix).

## Mô hình dữ liệu

### FileInfo
Đại diện cho thông tin file trong mạng.

```json
{
  "fileName": "string",
  "fileSize": "long",
  "fileHash": "string",
  "peerInfo": {
    "ip": "string",
    "port": "int",
    "username": "string"
  },
  "sharedByMe": "boolean"
}
```

### PeerInfo
Đại diện cho thông tin kết nối peer.

```json
{
  "ip": "string",
  "port": "int",
  "username": "string"
}
```

### ProgressInfo
Đại diện cho tiến trình tác vụ tải lên/tải xuống.

```json
{
  "id": "string",
  "status": "string", // e.g., "downloading", "completed", "failed"
  "fileName": "string",
  "bytesTransferred": "long",
  "totalBytes": "long",
  "taskType": "string", // "download" or "share"
  // Other internal fields omitted
}
```

### Conversation
Đại diện cho cuộc trò chuyện chat.

```json
{
  "id": "string",
  "name": "string",
  "avatarPath": "string",
  "isGroup": "int", // 0 = private, 1 = group
  "peerPublicKey": "string",
  "lastMsgContent": "string",
  "lastMsgTime": "long",
  "unreadCount": "int"
}
```

### Message
Đại diện cho tin nhắn chat.

```json
{
  "id": "string",
  "conversationId": "string",
  "senderId": "string",
  "msgType": "string",
  "content": "string",
  "status": "string",
  "createdAt": "long"
}
```

## Phản hồi lỗi

Định dạng phản hồi lỗi phổ biến:
```json
{
  "error": "Mô tả lỗi"
}
```

## Điểm cuối API

### 1. Quản lý file

#### 1.1 Làm mới danh sách file
**GET** `/api/files`

Lấy và làm mới danh sách file có sẵn từ mạng.

**Phản hồi:**
```json
[FileInfo, FileInfo, ...]
```

#### 1.2 Chia sẻ file công khai
**POST** `/api/files`

Chia sẻ file công khai cho tất cả các peer.

**Thân yêu cầu:**
```json
{
  "filePath": "string",
  "isReplace": 0 // 0 = tăng tên file nếu tồn tại, 1 = thay thế
}
```

**Phản hồi:**
```json
"progressId"
```

**Lỗi:**
- 503: Không kết nối với tracker
- 404: Không tìm thấy file

#### 1.3 Kiểm tra file tồn tại
**GET** `/api/files/exists?fileName=example.txt`

Kiểm tra file có tồn tại và được chia sẻ bởi người dùng hiện tại không.

**Phản hồi:**
```json
{
  "exists": true
}
```

#### 1.4 Chia sẻ file riêng tư
**POST** `/api/files/share-to-peers`

Chia sẻ file riêng tư với các peer cụ thể.

**Thân yêu cầu:**
```json
{
  "filePath": "string",
  "isReplace": 0,
  "peers": [
    {"ip": "192.168.1.1", "port": 8081, "username": "user1"},
    ...
  ]
}
```

**Phản hồi:**
```json
{
  "status": "shared"
}
```

#### 1.5 Xóa file
**DELETE** `/api/files/{fileName}`

Ngừng chia sẻ file.

**Phản hồi:**
```json
{
  "status": "success"
}
```

**Lỗi:**
- 404: Không tìm thấy file
- 500: Lỗi nội bộ

#### 1.6 Lấy peer chia sẻ file
**GET** `/api/files/{fileName}/shared-peers`

Lấy danh sách peer chia sẻ file cụ thể.

**Phản hồi:**
```json
{
  "peers": [PeerInfo, ...]
}
```

#### 1.7 Chỉnh sửa quyền file
**PUT** `/api/files/{fileName}/permission`

Thay đổi quyền chia sẻ file thành công khai/riêng tư với các peer cụ thể.

**Thân yêu cầu:**
```json
{
  "permission": "PRIVATE", // hoặc "PUBLIC"
  "peers": [PeerInfo, ...] // yêu cầu nếu PRIVATE
}
```

**Phản hồi:**
```json
{
  "status": "success"
}
```

#### 1.8 Tải xuống file
**GET** `/api/files/{fileName}/download?savePath=/path/to/save&peerInfo=192.168.1.1:8081`

Bắt đầu tải xuống file từ peer cụ thể.

**Phản hồi:**
```json
"progressId"
```

**Lỗi:**
- 404: Không tìm thấy file
- 503: Không kết nối

### 2. Theo dõi tiến trình

#### 2.1 Lấy tất cả tiến trình
**GET** `/api/progress`

Lấy tiến trình của tất cả tác vụ tải lên/tải xuống.

**Phản hồi:**
```json
{
  "taskId": ProgressInfo,
  ...
}
```

#### 2.2 Dọn dẹp tiến trình
**POST** `/api/progress/cleanup`

Xóa tác vụ hoàn thành/thất bại khỏi theo dõi tiến trình.

**Thân yêu cầu:**
```json
{
  "taskIds": ["id1", "id2", ...]
}
```

#### 2.3 Tạm dừng tải xuống
**POST** `/api/progress/{progressId}/pause`

Tạm dừng tác vụ tải xuống.

**Phản hồi:**
```json
{
  "status": "paused"
}
```

#### 2.4 Tiếp tục tải xuống
**POST** `/api/progress/{progressId}/resume`

Tiếp tục tác vụ tải xuống đã tạm dừng.

**Phản hồi:**
```json
{
  "status": "resumed"
}
```

#### 2.5 Hủy tác vụ
**DELETE** `/api/cancel?taskId={progressId}`

Hủy tác vụ đang chạy.

#### 2.6 Tiếp tục tác vụ
**POST** `/api/resume?taskId={progressId}`

Tiếp tục tác vụ bị dừng/timeout.

### 3. Quản lý người dùng

#### 3.1 Kiểm tra tên người dùng tồn tại
**GET** `/api/check-username`

Kiểm tra tên người dùng có được đặt cho instance này không.

**Phản hồi:**
```json
{
  "hasUsername": true
}
```

#### 3.2 Đặt tên người dùng
**POST** `/api/set-username`

Đặt tên người dùng cho peer này.

**Thân yêu cầu:**
```json
{
  "username": "myUsername"
}
```

**Phản hồi:**
```json
{
  "status": "success"
}
```

**Lỗi:**
- 400: Thất bại khi đặt

### 4. Khám phá peer

#### 4.1 Lấy peer đã biết
**GET** `/api/peers/known`

Lấy danh sách peer đã biết trong mạng.

**Phản hồi:**
```json
[PeerInfo, PeerInfo, ...]
```

#### 4.2 Lấy tất cả peer
**GET** `/api/peers`

Lấy tất cả peer đã đăng ký từ máy chủ tracker.

**Phản hồi:**
```json
[PeerInfo, PeerInfo, ...]
```

### 5. Hệ thống chat

#### 5.1 Gửi tin nhắn riêng tư
**POST** `/api/chat/send-private`

Gửi tin nhắn riêng tư đến peer khác.

**Thân yêu cầu:**
```json
{
  "receiverId": "peerUsername",
  "content": "Nội dung tin nhắn"
}
```

**Phản hồi:**
```json
{
  "success": true
}
```

#### 5.2 Gửi tin nhắn nhóm
**POST** `/api/chat/send-group`

Gửi tin nhắn đến tất cả thành viên của nhóm.

**Thân yêu cầu:**
```json
{
  "groupId": "groupConversationId",
  "content": "Nội dung tin nhắn"
}
```

**Phản hồi:**
```json
{
  "success": true
}
```

#### 5.3 Tạo cuộc trò chuyện riêng tư
**POST** `/api/chat/conversations`

Tạo cuộc trò chuyện riêng tư mới.

**Thân yêu cầu:**
```json
{
  "receiverId": "peerUsername",
  "receiverPublicKey": "publicKeyString"
}
```

**Phản hồi:**
Đối tượng Conversation

#### 5.4 Lấy tất cả cuộc trò chuyện
**GET** `/api/chat/conversations`

Lấy tất cả cuộc trò chuyện (riêng tư và nhóm).

**Phản hồi:**
```json
[Conversation, Conversation, ...]
```

#### 5.5 Lấy cuộc trò chuyện theo ID
**GET** `/api/chat/conversations/{conversationId}`

Lấy chi tiết cuộc trò chuyện cụ thể.

**Phản hồi:**
Đối tượng Conversation

#### 5.6 Lấy tin nhắn
**GET** `/api/chat/messages[/{conversationId}]?limit=50&offset=0`

Lấy tin nhắn của cuộc trò chuyện hoặc tất cả tin nhắn offline.

**Phản hồi:**
```json
[Message, Message, ...]
```

#### 5.7 Lấy tin nhắn offline
**GET** `/api/chat/offline-messages?receiverId=userId`

Lấy tin nhắn offline cho người dùng.

#### 5.8 Xác nhận tin nhắn
**POST** `/api/chat/acknowledge`

Đánh dấu tin nhắn là đã đọc/đã xác nhận.

**Thân yêu cầu:**
```json
{
  "messageIds": ["msgId1", "msgId2", ...]
}
```

**Phản hồi:**
```json
{
  "status": "acknowledged"
}
```

#### 5.9 Thêm thành viên nhóm
**POST** `/api/chat/groups/{groupId}/members`

Thêm thành viên mới vào nhóm.

**Thân yêu cầu:**
```json
{
  "memberId": "username",
  "publicKey": "publicKeyString"
}
```

**Phản hồi:**
```json
{
  "success": true
}
```

#### 5.10 Lấy thành viên nhóm
**GET** `/api/chat/groups/{groupId}/members`

Lấy danh sách thành viên trong nhóm.

**Phản hồi:**
```json
[MemberInfo, ...]
```

## Ghi chú

- Tất cả điểm cuối hỗ trợ CORS cho giao tiếp frontend.
- Máy chủ chạy trên cổng 8080, giả sử localhost.
- Kết nối peer với máy chủ tracker là bắt buộc cho các thao tác file.
- Theo dõi tiến trình bao gồm xử lý timeout tự động (2 phút).
