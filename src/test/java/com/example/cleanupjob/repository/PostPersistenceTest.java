package com.example.cleanupjob.repository;

import com.example.cleanupjob.model.Post;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.example.cleanupjob.CleanupJobApplication.class)
@ActiveProfiles("test")
class PostPersistenceTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PostRepository repo;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void softDelete_mutationShouldPersistInDatabase() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Long id = tx.execute(status -> {
            Post post = new Post();
            post.setAuthorName("Author");
            post.setTitle("Test");
            post.setContent("Content");
            post.setIsPublished(false);
            post.setIsDeleted(false);
            post.setCreatedAt(LocalDateTime.now().minusDays(35));
            post.setUpdatedAt(LocalDateTime.now());

            em.persist(post);
            em.flush();
            return post.getId();
        });

        tx.executeWithoutResult(status -> {
            Post loaded = repo.findById(id).orElseThrow();
            loaded.setIsDeleted(true);
            em.flush();
        });

        Boolean finalDeleted = tx.execute(status ->
                repo.findById(id).orElseThrow().getIsDeleted()
        );

        assertThat(finalDeleted)
                .as("setIsDeleted(true) mutation must be persisted to the database")
                .isTrue();
    }
}
