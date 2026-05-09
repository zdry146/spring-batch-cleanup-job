package com.example.cleanupjob.processor;

import com.example.cleanupjob.model.Post;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SoftDeleteProcessorTest {

    private SoftDeleteProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SoftDeleteProcessor();
    }

    @Test
    void process_shouldSetIsDeletedToTrue() throws Exception {
        Post post = createPost(1L, false);

        Post result = processor.process(post);

        assertTrue(result.getIsDeleted());
    }

    @Test
    void process_shouldNotAffectOtherFields() throws Exception {
        Post post = createPost(1L, false);
        post.setTitle("Test Title");
        post.setContent("Test Content");
        post.setAuthorName("Author");

        Post result = processor.process(post);

        assertEquals("Test Title", result.getTitle());
        assertEquals("Test Content", result.getContent());
        assertEquals("Author", result.getAuthorName());
    }

    @Test
    void process_shouldReturnSameInstance() throws Exception {
        Post post = createPost(1L, false);

        Post result = processor.process(post);

        assertSame(post, result);
    }

    @Test
    void process_shouldHandleAlreadyDeletedPost() throws Exception {
        Post post = createPost(1L, true);

        Post result = processor.process(post);

        assertTrue(result.getIsDeleted());
    }

    private Post createPost(Long id, Boolean isDeleted) {
        Post post = new Post();
        post.setId(id);
        post.setIsDeleted(isDeleted);
        post.setIsPublished(false);
        post.setCreatedAt(LocalDateTime.now().minusDays(35));
        post.setUpdatedAt(LocalDateTime.now());
        return post;
    }
}