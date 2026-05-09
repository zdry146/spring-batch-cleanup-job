package com.example.cleanupjob.writer;

import com.example.cleanupjob.model.Post;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchSoftDeleteWriterTest {

    @Mock
    private EntityManager entityManager;

    private BatchSoftDeleteWriter writer;

    @BeforeEach
    void setUp() {
        writer = new BatchSoftDeleteWriter();
        writer.setEntityManager(entityManager);
    }

    @Test
    void write_shouldMergeAndFlushAllPosts() throws Exception {
        List<Post> posts = Arrays.asList(createPost(1L), createPost(2L));
        Chunk<Post> chunk = new Chunk<>(posts);

        writer.write(chunk);

        verify(entityManager, times(2)).merge(any(Post.class));
        verify(entityManager).flush();
        verify(entityManager).clear();
    }

    @Test
    void write_shouldHandleEmptyChunk() throws Exception {
        Chunk<Post> chunk = new Chunk<>();

        writer.write(chunk);

        verify(entityManager, never()).merge(any(Post.class));
        verify(entityManager).flush();
    }

    @Test
    void write_shouldFlushAndClearEntityManager() throws Exception {
        List<Post> posts = Arrays.asList(createPost(1L));
        Chunk<Post> chunk = new Chunk<>(posts);

        writer.write(chunk);

        verify(entityManager).flush();
        verify(entityManager).clear();
    }

    private Post createPost(Long id) {
        Post post = new Post();
        post.setId(id);
        post.setIsDeleted(false);
        return post;
    }
}