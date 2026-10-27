package com.illini.grades.repository;

import com.illini.grades.entity.CourseGenEdCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface CourseGenEdCategoryRepository extends JpaRepository<CourseGenEdCategory, Long> {
    @Modifying
    @Transactional
    void deleteByCourseId(Long courseId);
}
