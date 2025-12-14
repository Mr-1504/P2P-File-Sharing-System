PRAGMA foreign_keys = ON;


-- 1. Bảng Peers (Cho các Peer đã kết nối)
CREATE TABLE IF NOT EXISTS peers (
    id TEXT PRIMARY KEY,
    ip VARCHAR(45) NOT NULL,
    port INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    is_online BOOLEAN DEFAULT 1,
    last_seen TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

-- 2. Bảng Conversation (Cập nhật thêm peer_public_key)
CREATE TABLE IF NOT EXISTS conversation (
    id TEXT PRIMARY KEY,                 -- PeerID (nếu 1-1) hoặc GroupID
    name TEXT,
    avatar_path TEXT,
    is_group INTEGER DEFAULT 0,

    -- [MỚI] Lưu Public Key của đối phương nếu là Chat Private (is_group=0)
    -- Nếu là Group thì trường này để NULL
    peer_public_key TEXT,

    last_msg_content TEXT,
    last_msg_time BIGINT,
    unread_count INTEGER DEFAULT 0
);

-- 3. Bảng Message (Giữ nguyên - Đã chuẩn)
CREATE TABLE IF NOT EXISTS message (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,

    sender_id TEXT NOT NULL,
    msg_type TEXT DEFAULT 'text',
    content TEXT,
    status TEXT DEFAULT 'received',
    created_at INTEGER,

    FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_msg_conversation_time
ON message(conversation_id, created_at DESC);

-- 4. Bảng Group Members (Giữ nguyên - Đã chuẩn)
CREATE TABLE IF NOT EXISTS group_members (
    group_id TEXT NOT NULL,
    peer_id TEXT NOT NULL,
    public_key TEXT,             -- Key để mã hóa tin nhắn cho thành viên này
    role TEXT DEFAULT 'member',

    PRIMARY KEY (group_id, peer_id),
    FOREIGN KEY (group_id) REFERENCES conversation(id) ON DELETE CASCADE
);
