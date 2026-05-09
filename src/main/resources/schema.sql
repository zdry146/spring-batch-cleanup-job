-- Schema already exists in PostgreSQL
-- This file is for reference only

-- posts table
CREATE TABLE IF NOT EXISTS posts (
    id BIGSERIAL PRIMARY KEY,
    author_name VARCHAR(255),
    content TEXT,
    cover_image VARCHAR(500),
    title VARCHAR(255),
    view_count INTEGER DEFAULT 0,
    like_count INTEGER DEFAULT 0,
    is_published BOOLEAN DEFAULT false,
    is_deleted BOOLEAN DEFAULT false,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- Index for cleanup query
CREATE INDEX IF NOT EXISTS idx_posts_cleanup ON posts(is_deleted, is_published, created_at);