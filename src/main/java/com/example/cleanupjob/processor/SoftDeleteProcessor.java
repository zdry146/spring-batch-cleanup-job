package com.example.cleanupjob.processor;

import com.example.cleanupjob.model.Post;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;

@Slf4j
public class SoftDeleteProcessor implements ItemProcessor<Post, Post> {

    @Override
    public Post process(Post post) throws Exception {
        post.setIsDeleted(true);
        log.info("Soft-deleting unpublished post: id={}", post.getId());
        return post;
    }
}