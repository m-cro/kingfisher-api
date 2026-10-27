package com.illini.grades.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.illini.grades.entity.CourseOffering;
import com.illini.grades.entity.Instructor;
import com.illini.grades.entity.Section;
import com.illini.grades.entity.Term;
import com.illini.grades.repository.CourseOfferingRepository;
import com.illini.grades.repository.CourseRepository;
import com.illini.grades.repository.SectionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseGradeAvailabilityTest {
    @Test
    void scheduledSemesterKeepsItsTermWithNullGpaAndDoesNotDiluteHistory() throws Exception {
        CourseRepository courses = mock(CourseRepository.class);
        CourseOfferingRepository offerings = mock(CourseOfferingRepository.class);
        SectionRepository sections = mock(SectionRepository.class);
        CourseService service = new CourseService(courses, offerings, sections, null, null, "http://localhost");
        when(courses.existsById(42L)).thenReturn(true);
        CourseOffering historical = offering(1L, 2025, "fall", "2025-fa");
        CourseOffering scheduled = offering(2L, 2026, "fall", "2026-fa");
        when(offerings.findByCourseId(42L)).thenReturn(new ArrayList<>(List.of(historical, scheduled)));
        Section section = new Section();
        section.setId(1L);
        section.setCourseOffering(historical);
        section.setA((short) 2);
        section.setB((short) 2);
        section.setSchedType("LEC");
        Instructor instructor = new Instructor();
        instructor.setId(1L);
        instructor.setName("Talati, N");
        section.setInstructor(instructor);
        when(sections.findByCourseOfferingIdIn(anyList())).thenReturn(List.of(section));

        var response = service.getCourseGrades(42L);
        assertEquals(2, response.courseOfferings().size());
        var future = response.courseOfferings().get(0);
        assertEquals("2026-fa", future.yearTerm());
        assertTrue(future.sections().isEmpty());
        assertEquals(0, future.cumulative().total());
        assertNull(future.cumulative().gpa());
        assertEquals(4, response.cumulative().total());
        assertEquals(3.5, response.cumulative().gpa(), 0.0001);
        var json = new ObjectMapper().valueToTree(response);
        assertTrue(json.at("/courseOfferings/0/cumulative/gpa").isNull());
        assertTrue(json.at("/courseOfferings/0/cumulative/gpaIncludingW").isNull());
    }

    private CourseOffering offering(long id, int year, String season, String yearTerm) {
        Term term = new Term();
        term.setId(id);
        term.setYear((short) year);
        term.setSeason(season);
        term.setYearTerm(yearTerm);
        CourseOffering offering = new CourseOffering();
        offering.setId(id);
        offering.setTerm(term);
        return offering;
    }
}
