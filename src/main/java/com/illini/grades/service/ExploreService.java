package com.illini.grades.service;

import org.springframework.transaction.annotation.Transactional;

import com.illini.grades.dto.*;
import com.illini.grades.entity.CourseGenEdCategory;
import com.illini.grades.entity.CourseGrade;
import com.illini.grades.entity.InstructorGrade;
import com.illini.grades.entity.Section;
import com.illini.grades.entity.SubjectGrade;
import com.illini.grades.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ExploreService {

    private final CourseGradeRepository courseGradeRepository;
    private final SubjectGradeRepository subjectGradeRepository;
    private final InstructorGradeRepository instructorGradeRepository;
    private final CourseRepository courseRepository;
    private final SubjectRepository subjectRepository;
    private final InstructorRepository instructorRepository;
    private final String baseUrl;

    public ExploreService(CourseGradeRepository courseGradeRepository, SubjectGradeRepository subjectGradeRepository, InstructorGradeRepository instructorGradeRepository, CourseRepository courseRepository, SubjectRepository subjectRepository, InstructorRepository instructorRepository, @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.courseGradeRepository = courseGradeRepository;
        this.subjectGradeRepository = subjectGradeRepository;
        this.instructorGradeRepository = instructorGradeRepository;
        this.courseRepository = courseRepository;
        this.subjectRepository = subjectRepository;
        this.instructorRepository = instructorRepository;
        this.baseUrl = baseUrl;
    }

    private Sort getSort(String sort, String order) {
        Sort.Direction direction = "asc".equalsIgnoreCase(order) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String sortBy = "avgStudents";
        if ("gpa".equalsIgnoreCase(sort)) sortBy = "gpa";
        else if ("totalStudents".equalsIgnoreCase(sort)) sortBy = "totalStudents";
        return Sort.by(direction, sortBy);
    }

    public PagedResponse<ExploreCourseDto> exploreCourses(String subjectCode, String genEd, Long instructorId, Integer minStudents, Double minAvgStudents, int page, int perPage, String sort, String order) {
        if (perPage > 100) perPage = 100;
        PageRequest pageReq = PageRequest.of(page - 1, perPage, getSort(sort, order));

        Specification<CourseGrade> spec = (root, q, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (minStudents != null) predicates.add(cb.greaterThanOrEqualTo(root.get("totalStudents"), minStudents));
            if (minAvgStudents != null) predicates.add(cb.greaterThanOrEqualTo(root.get("avgStudents"), minAvgStudents));
            if (subjectCode != null && !subjectCode.isBlank()) {
                var sq = q.subquery(Long.class);
                var cRoot = sq.from(com.illini.grades.entity.Course.class);
                sq.select(cRoot.get("id")).where(cb.equal(cRoot.get("subject").get("code"), subjectCode));
                predicates.add(root.get("courseId").in(sq));
            }
            if (genEd != null && !genEd.isBlank()) {
                var sq = q.subquery(Long.class);
                var gRoot = sq.from(CourseGenEdCategory.class);
                sq.select(gRoot.get("courseId")).where(cb.equal(gRoot.get("categoryId"), genEd.trim().toUpperCase()));
                predicates.add(root.get("courseId").in(sq));
            }
            if (instructorId != null) {
                var sq = q.subquery(Long.class);
                var sRoot = sq.from(Section.class);
                sq.select(sRoot.get("courseOffering").get("course").get("id")).where(cb.equal(sRoot.get("instructor").get("id"), instructorId));
                predicates.add(root.get("courseId").in(sq));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<CourseGrade> res = courseGradeRepository.findAll(spec, pageReq);
        List<ExploreCourseDto> dtos = res.stream().map(cg -> {
            var c = courseRepository.findById(cg.getCourseId()).orElseThrow();
            var csDto = new CourseSummaryDto(c.getId(), new SubjectSummaryDto(c.getSubject().getId(), c.getSubject().getCode()), c.getNumber(), c.getTitle(), cg.getGpa(), cg.getTotalStudents(), baseUrl + "/v1/courses/" + c.getId());
            return new ExploreCourseDto(csDto, cg.getTotalStudents(), cg.getAvgStudents(), cg.getGpa());
        }).toList();

        return new PagedResponse<>(page, res.getTotalPages(), res.getTotalElements(), dtos);
    }

    public PagedResponse<ExploreSubjectDto> exploreSubjects(Integer minStudents, Double minAvgStudents, int page, int perPage, String sort, String order) {
        if (perPage > 100) perPage = 100;
        PageRequest pageReq = PageRequest.of(page - 1, perPage, getSort(sort, order));

        Specification<SubjectGrade> spec = (root, q, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (minStudents != null) predicates.add(cb.greaterThanOrEqualTo(root.get("totalStudents"), minStudents));
            if (minAvgStudents != null) predicates.add(cb.greaterThanOrEqualTo(root.get("avgStudents"), minAvgStudents));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<SubjectGrade> res = subjectGradeRepository.findAll(spec, pageReq);
        List<ExploreSubjectDto> dtos = res.stream().map(sg -> {
            var s = subjectRepository.findById(sg.getSubjectId()).orElseThrow();
            return new ExploreSubjectDto(new SubjectSummaryDto(s.getId(), s.getCode()), sg.getTotalStudents(), sg.getAvgStudents(), sg.getGpa());
        }).toList();

        return new PagedResponse<>(page, res.getTotalPages(), res.getTotalElements(), dtos);
    }

    public PagedResponse<ExploreInstructorDto> exploreInstructors(String subjectCode, Long instructorId, Integer minStudents, Double minAvgStudents, int page, int perPage, String sort, String order) {
        if (perPage > 100) perPage = 100;
        PageRequest pageReq = PageRequest.of(page - 1, perPage, getSort(sort, order));

        Specification<InstructorGrade> spec = (root, q, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (minStudents != null) predicates.add(cb.greaterThanOrEqualTo(root.get("totalStudents"), minStudents));
            if (minAvgStudents != null) predicates.add(cb.greaterThanOrEqualTo(root.get("avgStudents"), minAvgStudents));
            if (subjectCode != null && !subjectCode.isBlank()) {
                var sq = q.subquery(Long.class);
                var sRoot = sq.from(Section.class);
                sq.select(sRoot.get("instructor").get("id")).where(cb.equal(sRoot.get("courseOffering").get("course").get("subject").get("code"), subjectCode));
                predicates.add(root.get("instructorId").in(sq));
            }
            if (instructorId != null) {
                predicates.add(cb.equal(root.get("instructorId"), instructorId));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<InstructorGrade> res = instructorGradeRepository.findAll(spec, pageReq);
        List<ExploreInstructorDto> dtos = res.stream().map(ig -> {
            var i = instructorRepository.findById(ig.getInstructorId()).orElseThrow();
            return new ExploreInstructorDto(new InstructorSummaryDto(i.getId(), i.getName()), ig.getTotalStudents(), ig.getAvgStudents(), ig.getGpa());
        }).toList();

        return new PagedResponse<>(page, res.getTotalPages(), res.getTotalElements(), dtos);
    }
}
