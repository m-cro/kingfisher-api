package com.illini.grades.controller;

import com.illini.grades.dto.ExploreCourseDto;
import com.illini.grades.dto.ExploreInstructorDto;
import com.illini.grades.dto.ExploreSubjectDto;
import com.illini.grades.dto.PagedResponse;
import com.illini.grades.service.ExploreService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/explore")
@Tag(name = "Explore")
public class ExploreController {

    private final ExploreService exploreService;

    public ExploreController(ExploreService exploreService) {
        this.exploreService = exploreService;
    }

    @GetMapping("/courses")
    public PagedResponse<ExploreCourseDto> exploreCourses(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String genEd,
            @RequestParam(required = false) Long instructor,
            @RequestParam(required = false) Integer minStudents,
            @RequestParam(required = false) Double minAvgStudents,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int perPage,
            @RequestParam(defaultValue = "avgStudents") String sort,
            @RequestParam(defaultValue = "desc") String order) {
        return exploreService.exploreCourses(subject, genEd, instructor, minStudents, minAvgStudents, page, perPage, sort, order);
    }

    @GetMapping("/subjects")
    public PagedResponse<ExploreSubjectDto> exploreSubjects(
            @RequestParam(required = false) Integer minStudents,
            @RequestParam(required = false) Double minAvgStudents,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int perPage,
            @RequestParam(defaultValue = "avgStudents") String sort,
            @RequestParam(defaultValue = "desc") String order) {
        return exploreService.exploreSubjects(minStudents, minAvgStudents, page, perPage, sort, order);
    }

    @GetMapping("/instructors")
    public PagedResponse<ExploreInstructorDto> exploreInstructors(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) Long instructor,
            @RequestParam(required = false) Integer minStudents,
            @RequestParam(required = false) Double minAvgStudents,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int perPage,
            @RequestParam(defaultValue = "avgStudents") String sort,
            @RequestParam(defaultValue = "desc") String order) {
        return exploreService.exploreInstructors(subject, instructor, minStudents, minAvgStudents, page, perPage, sort, order);
    }
}
