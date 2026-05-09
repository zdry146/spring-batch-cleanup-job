package com.example.cleanupjob.repository;

import com.example.cleanupjob.model.Post;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostRepositoryTest {

    @Mock
    private PostRepository postRepository;

    @BeforeEach
    void setUp() {
    }

    @Test
    void findUnpublishedOlderThan_shouldReturnPostsOlderThanCutoffDate() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30);
        List<Post> expectedPosts = Arrays.asList(createPost(1L), createPost(2L));
        when(postRepository.findUnpublishedOlderThan(cutoffDate)).thenReturn(expectedPosts);

        List<Post> result = postRepository.findUnpublishedOlderThan(cutoffDate);

        assertEquals(2, result.size());
        verify(postRepository).findUnpublishedOlderThan(cutoffDate);
    }

    @Test
    void findUnpublishedOlderThan_shouldReturnEmptyListWhenNoPosts() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30);
        when(postRepository.findUnpublishedOlderThan(cutoffDate)).thenReturn(List.of());

        List<Post> result = postRepository.findUnpublishedOlderThan(cutoffDate);

        assertTrue(result.isEmpty());
    }

    @Test
    void findByIsDeletedFalse_shouldReturnOnlyNonDeletedPosts() {
        List<Post> expectedPosts = Arrays.asList(createPost(1L), createPost(2L));
        when(postRepository.findByIsDeletedFalse()).thenReturn(expectedPosts);

        List<Post> result = postRepository.findByIsDeletedFalse();

        assertEquals(2, result.size());
        verify(postRepository).findByIsDeletedFalse();
    }

    @Test
    void findByIsDeletedTrue_shouldReturnOnlyDeletedPosts() {
        List<Post> expectedPosts = Arrays.asList(createPost(1L));
        when(postRepository.findByIsDeletedTrue()).thenReturn(expectedPosts);

        List<Post> result = postRepository.findByIsDeletedTrue();

        assertEquals(1, result.size());
        verify(postRepository).findByIsDeletedTrue();
    }

    private Post createPost(Long id) {
        Post post = new Post();
        post.setId(id);
        post.setIsDeleted(false);
        post.setIsPublished(false);
        post.setCreatedAt(LocalDateTime.now().minusDays(35));
        return post;
    }
}