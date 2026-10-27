package com.illini.grades.service;

import com.illini.grades.entity.Course;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs the production list/count SQL against an isolated PostgreSQL fixture.
 * Set TEST_DATABASE_URL to a disposable PostgreSQL database with pg_trgm installed.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CourseInstructorFilterTest {
    private CourseService service;
    private final List<String> statements = new ArrayList<>();
    private final Map<String, Object> parameters = new HashMap<>();

    @BeforeEach
    void setUp() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(query.getResultList()).thenReturn(List.of());
        when(query.getSingleResult()).thenReturn(0L);
        when(query.setParameter(anyString(), any())).thenAnswer(call -> {
            parameters.put(call.getArgument(0), call.getArgument(1));
            return query;
        });
        when(em.createNativeQuery(anyString(), eq(Course.class))).thenAnswer(call -> {
            statements.add(call.getArgument(0));
            return query;
        });
        when(em.createNativeQuery(anyString())).thenAnswer(call -> {
            statements.add(call.getArgument(0));
            return query;
        });
        service = new CourseService(null, null, null, null, null, "http://localhost");
        ReflectionTestUtils.setField(service, "em", em);
    }

    @Test
    void autocompleteNameExcludesSimilarSurnamesAndDifferentInitials() throws Exception {
        assertCourses("Talati, N", List.of(1L, 5L, 7L));
    }

    @Test
    void exactNameIgnoresCaseAndSurroundingWhitespace() throws Exception {
        assertCourses("  TALATI, n  ", List.of(1L, 5L, 7L));
    }

    @Test
    void partialNameStillFindsMultipleInstructors() throws Exception {
        assertCourses("Talati", List.of(1L, 2L, 3L, 5L, 6L, 7L));
    }

    @Test
    void fullNameMatchesTheCatalogInitial() throws Exception {
        assertCourses("Nishil Talati", List.of(1L, 5L, 7L));
    }

    @Test
    void dottedInitialAndMiddleNameAreSupported() throws Exception {
        assertCourses("Talati, N.", List.of(1L, 5L, 7L));
    }

    @Test
    void unknownNameReturnsNoCourses() throws Exception {
        assertCourses("Zzzunknown", List.of());
    }

    private void assertCourses(String instructor, List<Long> expectedIds) throws Exception {
        service.listCourses(null, null, instructor, null, null, null, null,
                null, "and", 1, 25, "title", "asc");
        try (Connection connection = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"));
             var statement = connection.createStatement()) {
            // Temporary tables shadow any existing application tables and disappear on close.
            statement.execute("CREATE TEMP TABLE subjects (id bigint, code text)");
            statement.execute("CREATE TEMP TABLE courses (id bigint, subject_id bigint, number int, title text)");
            statement.execute("CREATE TEMP TABLE course_grades (course_id bigint, total_students int, avg_students float, gpa float)");
            statement.execute("CREATE TEMP TABLE course_offerings (id bigint, course_id bigint)");
            statement.execute("CREATE TEMP TABLE sections (course_offering_id bigint, instructor_id bigint)");
            statement.execute("CREATE TEMP TABLE instructors (id bigint, name text)");
            statement.execute("INSERT INTO subjects VALUES (1, 'CS')");
            statement.execute("INSERT INTO instructors VALUES (1, 'Talati, N'), (2, 'Talati, A'), (3, 'Talathi, N'), (4, 'Smith, N'), (5, 'Nishil Talati'), (6, 'Anish Talati'), (7, 'Nishil Kumar Talati')");
            statement.execute("INSERT INTO courses SELECT n, 1, 100 + n, 'Course ' || n FROM generate_series(1, 7) n");
            statement.execute("INSERT INTO course_offerings SELECT id, id FROM courses");
            statement.execute("INSERT INTO sections SELECT id, id FROM courses");
            List<Long> actualIds = new ArrayList<>();
            try (var rows = statement.executeQuery(bind(statements.get(0)))) {
                while (rows.next()) actualIds.add(rows.getLong("id"));
            }
            assertEquals(expectedIds, actualIds);
            parameters.put("query", instructor);
            parameters.put("namePattern", com.illini.grades.util.InstructorNameSearch.patternFor(instructor));
            var repositoryQuery = com.illini.grades.repository.InstructorRepository.class
                    .getMethod("searchByQuery", String.class, String.class, org.springframework.data.domain.Pageable.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class);
            List<Long> instructorIds = new ArrayList<>();
            try (var rows = statement.executeQuery(bind(repositoryQuery.value()))) {
                while (rows.next()) instructorIds.add(rows.getLong("id"));
            }
            instructorIds.sort(Long::compareTo);
            assertEquals(expectedIds, instructorIds, "instructor directory must use the same matching rules");
            try (var rows = statement.executeQuery(bind(repositoryQuery.countQuery()))) {
                rows.next();
                assertEquals(expectedIds.size(), rows.getLong(1));
            }
            try (var rows = statement.executeQuery(bind(statements.get(1)))) {
                rows.next();
                assertEquals(expectedIds.size(), rows.getLong(1), "count must use the same instructor filter");
            }
        }
    }

    private String bind(String sql) {
        // Preserve SQL string literals and PostgreSQL :: casts.
        return Pattern.compile("'(?:''|[^'])*'|(?<!:):([a-zA-Z][a-zA-Z0-9]*)").matcher(sql).replaceAll(match -> {
            if (match.group(1) == null) return java.util.regex.Matcher.quoteReplacement(match.group());
            Object value = parameters.get(match.group(1));
            if (value == null) throw new AssertionError("Missing parameter " + match.group(1));
            return java.util.regex.Matcher.quoteReplacement("'" + value.toString().replace("'", "''") + "'");
        });
    }
}
