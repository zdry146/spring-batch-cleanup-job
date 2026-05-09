package com.example.cleanupjob.writer;

import com.example.cleanupjob.model.Post;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import java.util.List;

@Slf4j
public class BatchSoftDeleteWriter implements ItemWriter<Post> {

    @PersistenceContext
    private EntityManager entityManager;

    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void write(Chunk<? extends Post> chunk) throws Exception {
        List<? extends Post> posts = chunk.getItems();
        int batchSize = posts.size();

        for (Post post : posts) {
            entityManager.merge(post);
        }

        entityManager.flush();
        entityManager.clear();

        log.info("Batch soft-deleted {} unpublished posts (batch mode)", batchSize);
    }
}