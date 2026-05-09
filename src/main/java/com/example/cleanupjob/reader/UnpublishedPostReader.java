package com.example.cleanupjob.reader;

import com.example.cleanupjob.model.Post;
import com.example.cleanupjob.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemReader;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class UnpublishedPostReader implements ItemReader<Post> {

    private static final int DAYS_THRESHOLD = 30;

    private final PostRepository postRepository;
    private Iterator<Post> iterator;
    private boolean initialized = false;

    @Override
    public Post read() {
        if (!initialized) {
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(DAYS_THRESHOLD);
            List<Post> unpublishedPosts = postRepository.findUnpublishedOlderThan(cutoffDate);
            this.iterator = unpublishedPosts.iterator();
            this.initialized = true;
            log.info("UnpublishedPostReader initialized with {} posts older than {} days",
                    unpublishedPosts.size(), DAYS_THRESHOLD);
        }

        if (iterator != null && iterator.hasNext()) {
            return iterator.next();
        }
        // Reset for next job execution
        initialized = false;
        iterator = null;
        return null;
    }
}