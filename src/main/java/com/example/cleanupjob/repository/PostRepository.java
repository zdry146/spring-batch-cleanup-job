package com.example.cleanupjob.repository;

import com.example.cleanupjob.model.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    List<Post> findByIsDeletedFalse();

    @Query("SELECT p FROM Post p WHERE p.isDeleted = false AND p.isPublished = false AND p.createdAt < :cutoffDate")
    List<Post> findUnpublishedOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate);

    List<Post> findByIsDeletedTrue();
}