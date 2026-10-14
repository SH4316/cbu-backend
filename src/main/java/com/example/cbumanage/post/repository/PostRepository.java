package com.example.cbumanage.post.repository;


import com.example.cbumanage.post.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findByIdAndIsDeletedFalse(Long id);

    Page<Post> findByCategoryAndIsDeletedFalse(int category, Pageable pageable);

    @Query("""
        select p from Post p
        where p.category = 7 and p.isDeleted = false
          and (p.authorId = :userId or exists (
              select r.id from PostReport r
              where r.post = p and exists (
                  select gm.id from GroupMember gm
                  where gm.group.id = r.groupId and gm.user.userId = :userId
                    and gm.groupMemberStatus = com.example.cbumanage.group.entity.enums.GroupMemberStatus.ACTIVE
                    and gm.group.isDeleted = false
              )
          ))
        """)
    Page<Post> findVisibleReports(@Param("userId") Long userId, Pageable pageable);

    Page<Post> findByTitleContainingAndIsDeletedFalse(String title, Pageable pageable);

    Page<Post> findByContentContainingAndIsDeletedFalse(String content, Pageable pageable);

    Page<Post> findByAuthorIdAndIsDeletedFalse(Long authorId,Pageable pageable);


    /**
     * race condition 없이 atomic하게 조회수를 +1 처리합니다.
     */
    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :postId")
    void incrementViewCount(@Param("postId") Long postId);
}
