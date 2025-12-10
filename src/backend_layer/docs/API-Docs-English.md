# P2P File Sharing System - Backend API Documentation

## Overview

This API provides backend services for a Peer-to-Peer (P2P) file sharing system with chat functionality. The server runs on `http://localhost:8080` using JSON for data exchange.

### Authentication
- No authentication tokens required.
- Username management ensures each peer has a unique identity.
- Connections to tracker server required for most operations.

### Data Formats
- All requests and responses use JSON format.
- Dates/Timestamps are in milliseconds since epoch (Unix time).

## Data Models

### FileInfo
Represents file information in the network.

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
Represents peer connection information.

```json
{
  "ip": "string",
  "port": "int",
  "username": "string"
}
```

### ProgressInfo
Represents download/upload task progress.

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
Represents chat conversation.

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
Represents chat message.

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

## Error Responses

Common error response format:
```json
{
  "error": "Error description"
}
```

## API Endpoints

### 1. File Management

#### 1.1 Refresh File List
**GET** `/api/files`

Retrieves and refreshes the list of available files from the network.

**Response:**
```json
[FileInfo, FileInfo, ...]
```

#### 1.2 Share Public File
**POST** `/api/files`

Shares a file publicly to all peers.

**Request Body:**
```json
{
  "filePath": "string",
  "isReplace": 0 // 0 = increment filename if exists, 1 = replace
}
```

**Response:**
```json
"progressId"
```

**Errors:**
- 503: Not connected to tracker
- 404: File not found

#### 1.3 Check File Exists
**GET** `/api/files/exists?fileName=example.txt`

Checks if a file exists and is shared by the current user.

**Response:**
```json
{
  "exists": true
}
```

#### 1.4 Share Private File
**POST** `/api/files/share-to-peers`

Shares a file privately with specific peers.

**Request Body:**
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

**Response:**
```json
{
  "status": "shared"
}
```

#### 1.5 Remove File
**DELETE** `/api/files/{fileName}`

Stops sharing a file.

**Response:**
```json
{
  "status": "success"
}
```

**Errors:**
- 404: File not found
- 500: Internal error

#### 1.6 Get Shared Peers for File
**GET** `/api/files/{fileName}/shared-peers`

Gets the list of peers sharing a specific file.

**Response:**
```json
{
  "peers": [PeerInfo, ...]
}
```

#### 1.7 Edit File Permissions
**PUT** `/api/files/{fileName}/permission`

Changes file sharing permission to public/private with specific peers.

**Request Body:**
```json
{
  "permission": "PRIVATE", // or "PUBLIC"
  "peers": [PeerInfo, ...] // required if PRIVATE
}
```

**Response:**
```json
{
  "status": "success"
}
```

#### 1.8 Download File
**GET** `/api/files/{fileName}/download?savePath=/path/to/save&peerInfo=192.168.1.1:8081`

Starts downloading a file from a specific peer.

**Response:**
```json
"progressId"
```

**Errors:**
- 404: File not found
- 503: Not connected

### 2. Progress Tracking

#### 2.1 Get All Progress
**GET** `/api/progress`

Gets progress of all download/upload tasks.

**Response:**
```json
{
  "taskId": ProgressInfo,
  ...
}
```

#### 2.2 Cleanup Progress
**POST** `/api/progress/cleanup`

Removes completed/failed tasks from progress tracking.

**Request Body:**
```json
{
  "taskIds": ["id1", "id2", ...]
}
```

#### 2.3 Pause Download
**POST** `/api/progress/{progressId}/pause`

Pauses a download task.

**Response:**
```json
{
  "status": "paused"
}
```

#### 2.4 Resume Download
**POST** `/api/progress/{progressId}/resume`

Resumes a paused download task.

**Response:**
```json
{
  "status": "resumed"
}
```

#### 2.5 Cancel Task
**DELETE** `/api/cancel?taskId={progressId}`

Cancels a running task.

#### 2.6 Resume Task
**POST** `/api/resume?taskId={progressId}`

Resumes a stalled/timed out task.

### 3. User Management

#### 3.1 Check Username Exists
**GET** `/api/check-username`

Checks if a username is set for this instance.

**Response:**
```json
{
  "hasUsername": true
}
```

#### 3.2 Set Username
**POST** `/api/set-username`

Sets the username for this peer.

**Request Body:**
```json
{
  "username": "myUsername"
}
```

**Response:**
```json
{
  "status": "success"
}
```

**Errors:**
- 400: Failed to set

### 4. Peer Discovery

#### 4.1 Get Known Peers
**GET** `/api/peers/known`

Gets list of known peers in the network.

**Response:**
```json
[PeerInfo, PeerInfo, ...]
```

#### 4.2 Get All Peers
**GET** `/api/peers`

Gets all registered peers from the tracker server.

**Response:**
```json
[PeerInfo, PeerInfo, ...]
```

### 5. Chat System

#### 5.1 Send Private Message
**POST** `/api/chat/send-private`

Sends a private message to another peer.

**Request Body:**
```json
{
  "receiverId": "peerUsername",
  "content": "Message text"
}
```

**Response:**
```json
{
  "success": true
}
```

#### 5.2 Send Group Message
**POST** `/api/chat/send-group`

Sends a message to all members of a group.

**Request Body:**
```json
{
  "groupId": "groupConversationId",
  "content": "Message text"
}
```

**Response:**
```json
{
  "success": true
}
```

#### 5.3 Create Private Conversation
**POST** `/api/chat/conversations`

Creates a new private conversation.

**Request Body:**
```json
{
  "receiverId": "peerUsername",
  "receiverPublicKey": "publicKeyString"
}
```

**Response:**
Conversation object

#### 5.4 Get All Conversations
**GET** `/api/chat/conversations`

Gets all conversations (private and group).

**Response:**
```json
[Conversation, Conversation, ...]
```

#### 5.5 Get Conversation by ID
**GET** `/api/chat/conversations/{conversationId}`

Gets a specific conversation details.

**Response:**
Conversation object

#### 5.6 Get Messages
**GET** `/api/chat/messages[/{conversationId}]?limit=50&offset=0`

Gets messages for a conversation or all offline messages.

**Response:**
```json
[Message, Message, ...]
```

#### 5.7 Get Offline Messages
**GET** `/api/chat/offline-messages?receiverId=userId`

Gets offline messages for a user.

#### 5.8 Acknowledge Messages
**POST** `/api/chat/acknowledge`

Marks messages as read/acknowledged.

**Request Body:**
```json
{
  "messageIds": ["msgId1", "msgId2", ...]
}
```

**Response:**
```json
{
  "status": "acknowledged"
}
```

#### 5.9 Add Group Member
**POST** `/api/chat/groups/{groupId}/members`

Adds a new member to a group.

**Request Body:**
```json
{
  "memberId": "username",
  "publicKey": "publicKeyString"
}
```

**Response:**
```json
{
  "success": true
}
```

#### 5.10 Get Group Members
**GET** `/api/chat/groups/{groupId}/members`

Gets list of members in a group.

**Response:**
```json
[MemberInfo, ...]
```

## Notes

- All endpoints support CORS for frontend communication.
- Server runs on port 8080, assuming localhost.
- Peer connection to tracker server is required for file operations.
- Progress tracking includes automatic timeout handling (2 minutes).
