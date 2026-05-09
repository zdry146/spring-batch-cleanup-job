-- Insert test data for Spring Batch job testing
-- Run this before executing the cleanup job

-- Insert unpublished old posts (for cleanupStep - Step 1)
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT
    'Author ' || i,
    'Content ' || i,
    'Unpublished Post ' || i,
    0, 0, false, false,
    NOW() - INTERVAL '35 days',
    NOW() - INTERVAL '35 days'
FROM generate_series(1, 15) i;

-- Mark some posts as already deleted (for processDeletedPostsStep - Step 2)
UPDATE posts
SET is_deleted = true, updated_at = NOW() - INTERVAL '1 day'
WHERE title LIKE 'Unpublished Post 1'
   OR title LIKE 'Unpublished Post 2'
   OR title LIKE 'Unpublished Post 3'
   OR title LIKE 'Unpublished Post 4'
   OR title LIKE 'Unpublished Post 5';

-- Verify data
SELECT 'Unpublished old posts (for cleanupStep):' as info;
SELECT COUNT(*) as count FROM posts
WHERE is_published = false AND is_deleted = false
AND created_at < NOW() - INTERVAL '30 days';

SELECT 'Already deleted posts (for processDeletedPostsStep):' as info;
SELECT COUNT(*) as count FROM posts WHERE is_deleted = true;
