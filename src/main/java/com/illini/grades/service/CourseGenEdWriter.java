package com.illini.grades.service;

import com.illini.grades.entity.CourseGenEdCategory;
import com.illini.grades.repository.CourseGenEdCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Replaces a course's gen ed categories in one transaction. Split out from
 * SectionScheduleIngestionService so @Transactional takes effect (see ScheduledSectionWriter).
 */
@Service
public class CourseGenEdWriter {

    private final CourseGenEdCategoryRepository repository;

    public CourseGenEdWriter(CourseGenEdCategoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replace(Long courseId, List<CourseGenEdCategory> categories) {
        repository.deleteByCourseId(courseId);
        repository.flush();
        categories.forEach(c -> c.setCourseId(courseId));
        repository.saveAll(categories);
    }
}
