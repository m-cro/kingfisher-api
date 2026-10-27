package com.illini.grades.service;

import com.illini.grades.dto.*;
import com.illini.grades.entity.Course;
import com.illini.grades.entity.CourseGrade;
import com.illini.grades.entity.CourseOffering;
import com.illini.grades.entity.Instructor;
import com.illini.grades.entity.Section;
import com.illini.grades.exception.ResourceNotFoundException;
import com.illini.grades.repository.InstructorRepository;
import com.illini.grades.repository.SectionRepository;
import com.illini.grades.util.GpaCalculator;
import com.illini.grades.util.InstructorNameSearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class InstructorService {

    private final InstructorRepository instructorRepository;
    private final SectionRepository sectionRepository;
    private final String baseUrl;

    public InstructorService(InstructorRepository instructorRepository, SectionRepository sectionRepository, @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.instructorRepository = instructorRepository;
        this.sectionRepository = sectionRepository;
        this.baseUrl = baseUrl;
    }

    public PagedResponse<InstructorSummaryDto> listInstructors(String query, int page, int perPage) {
        if (perPage > 100) perPage = 100;
        Page<Instructor> result;
        if (query != null && !query.isBlank()) {
            result = instructorRepository.searchByQuery(query.strip(), InstructorNameSearch.patternFor(query), PageRequest.of(page - 1, perPage));
        } else {
            result = instructorRepository.findAll(PageRequest.of(page - 1, perPage, org.springframework.data.domain.Sort.by("name", "id")));
        }

        List<InstructorSummaryDto> dtos = result.stream()
                .map(i -> new InstructorSummaryDto(i.getId(), i.getName()))
                .toList();

        return new PagedResponse<>(page, result.getTotalPages(), result.getTotalElements(), dtos);
    }

    public InstructorDetailDto getInstructor(Long id) {
        Instructor instructor = instructorRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Instructor not found"));
        
        List<Section> sections = sectionRepository.findByInstructorId(id);
        List<CourseSummaryDto> courses = sections.stream()
                .map(s -> s.getCourseOffering().getCourse())
                .distinct()
                .map(c -> new CourseSummaryDto(
                        c.getId(),
                        new SubjectSummaryDto(c.getSubject().getId(), c.getSubject().getCode()),
                        c.getNumber(),
                        c.getTitle(),
                        c.getCourseGrade() != null ? c.getCourseGrade().getGpa() : null,
                        c.getCourseGrade() != null ? c.getCourseGrade().getTotalStudents() : null,
                        baseUrl + "/v1/courses/" + c.getId()
                )).toList();

        return new InstructorDetailDto(id, instructor.getName(), courses);
    }

    public InstructorGradesResponseDto getInstructorGrades(Long id) {
        if (!instructorRepository.existsById(id)) throw new ResourceNotFoundException("Instructor not found");

        List<Section> sections = sectionRepository.findByInstructorId(id);
        Map<Long, List<Section>> grouped = sections.stream().collect(Collectors.groupingBy(s -> s.getCourseOffering().getId()));

        List<InstructorGradesResponseDto.InstructorCourseOfferingGradeDto> coDtos = new ArrayList<>();
        List<GradeDistributionDto> overallDists = new ArrayList<>();

        for (Map.Entry<Long, List<Section>> entry : grouped.entrySet()) {
            CourseOffering co = entry.getValue().get(0).getCourseOffering();
            Course course = co.getCourse();

            List<SectionGradeDto> sDtos = new ArrayList<>();
            List<GradeDistributionDto> coDists = new ArrayList<>();

            for (Section s : entry.getValue()) {
                GradeDistributionDto dist = GpaCalculator.fromCounts(
                        s.getAPlus(), s.getA(), s.getAMinus(),
                        s.getBPlus(), s.getB(), s.getBMinus(),
                        s.getCPlus(), s.getC(), s.getCMinus(),
                        s.getDPlus(), s.getD(), s.getDMinus(),
                        s.getF(), s.getW()
                );
                sDtos.add(new SectionGradeDto(s.getId(), s.getSchedType(), new InstructorSummaryDto(s.getInstructor().getId(), s.getInstructor().getName()), dist));
                coDists.add(dist);
            }

            GradeDistributionDto coCum = GpaCalculator.sum(coDists);
            overallDists.add(coCum);

            coDtos.add(new InstructorGradesResponseDto.InstructorCourseOfferingGradeDto(
                    co.getTerm().getId(),
                    co.getTerm().getYearTerm(),
                    new CourseSummaryDto(course.getId(), new SubjectSummaryDto(course.getSubject().getId(), course.getSubject().getCode()), course.getNumber(), course.getTitle(), course.getCourseGrade() != null ? course.getCourseGrade().getGpa() : null, course.getCourseGrade() != null ? course.getCourseGrade().getTotalStudents() : null, baseUrl + "/v1/courses/" + course.getId()),
                    co.getId(),
                    coCum,
                    sDtos
            ));
        }

        coDtos.sort((o1, o2) -> {
            int y1 = Integer.parseInt(o1.yearTerm().split("-")[0]);
            int y2 = Integer.parseInt(o2.yearTerm().split("-")[0]);
            if (y1 != y2) return Integer.compare(y2, y1);
            return o2.yearTerm().compareTo(o1.yearTerm()); // basic fallback
        });

        GradeDistributionDto cumulative = GpaCalculator.sum(overallDists);

        return new InstructorGradesResponseDto(id, cumulative, coDtos);
    }
}
