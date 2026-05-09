package com.example.cleanupjob.reader;

import com.example.cleanupjob.model.Post;
import com.example.cleanupjob.repository.PostRepository;
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
class UnpublishedPostReaderTest {

    @Mock
    private PostRepository postRepository;

    private UnpublishedPostReader reader;

    @BeforeEach
    void setUp() {
        reader = new UnpublishedPostReader(postRepository);
    }

    @Test
    void read_shouldReturnPostsOnFirstCall() {
        List<Post> posts = Arrays.asList(createPost(1L), createPost(2L));
        when(postRepository.findUnpublishedOlderThan(any(LocalDateTime.class))).thenReturn(posts);

        Post result = reader.read();

        assertNotNull(result);
        assertEquals(1L, result.getId());
    }

    @Test
    void read_shouldReturnSecondPostOnSecondCall() {
        List<Post> posts = Arrays.asList(createPost(1L), createPost(2L));
        when(postRepository.findUnpublishedOlderThan(any(LocalDateTime.class))).thenReturn(posts);

        reader.read();
        Post secondResult = reader.read();

        assertNotNull(secondResult);
        assertEquals(2L, secondResult.getId());
    }

    @Test
    void read_shouldReturnNullWhenNoMorePosts() {
        List<Post> posts = Arrays.asList(createPost(1L));
        when(postRepository.findUnpublishedOlderThan(any(LocalDateTime.class))).thenReturn(posts);

        reader.read();
        Post secondResult = reader.read();

        assertNull(secondResult);
    }

    @Test
    void read_shouldResetStateAfterExhaustingPosts() {
        List<Post> posts = Arrays.asList(createPost(1L));
        when(postRepository.findUnpublishedOlderThan(any(LocalDateTime.class))).thenReturn(posts);

        reader.read(); // Returns post 1
        reader.read(); // Returns null, resets state

        reader.read(); // Re-initializes, returns post 1 again
        Post result = reader.read(); // Returns null

        assertNull(result);
    }

    @Test
    void read_shouldQueryRepositoryAgainAfterReset() {
        List<Post> posts = Arrays.asList(createPost(1L));
        when(postRepository.findUnpublishedOlderThan(any(LocalDateTime.class))).thenReturn(posts);

        reader.read();
        reader.read(); // Returns null, resets state

        reader.read(); // Re-initializes
        reader.read(); // Returns null again

        verify(postRepository, times(2)).findUnpublishedOlderThan(any(LocalDateTime.class));
    }

    @Test
    void read_shouldReturnNullWhenRepositoryReturnsEmptyList() {
        when(postRepository.findUnpublishedOlderThan(any(LocalDateTime.class))).thenReturn(List.of());

        Post result = reader.read();

        assertNull(result);
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