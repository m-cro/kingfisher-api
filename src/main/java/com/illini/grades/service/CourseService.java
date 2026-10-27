package com.illini.grades.service;

import org.springframework.transaction.annotation.Transactional;

import com.illini.grades.dto.*;
import com.illini.grades.entity.Course;
import com.illini.grades.entity.CourseOffering;
import com.illini.grades.entity.Section;
import com.illini.grades.exception.ResourceNotFoundException;
import com.illini.grades.entity.ScheduledSection;
import com.illini.grades.repository.CourseOfferingRepository;
import com.illini.grades.repository.CourseRepository;
import com.illini.grades.repository.ScheduledSectionRepository;
import com.illini.grades.repository.SectionRepository;
import com.illini.grades.repository.TermRepository;
import com.illini.grades.util.GpaCalculator;
import com.illini.grades.util.InstructorNameSearch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageImpl;

@Service
@Transactional(readOnly = true)
public class CourseService {

    private final CourseRepository courseRepository;
    private final CourseOfferingRepository courseOfferingRepository;
    private final SectionRepository sectionRepository;
    private final TermRepository termRepository;
    private final ScheduledSectionRepository scheduledSectionRepository;
    private final String baseUrl;

    @PersistenceContext
    private EntityManager em;

    /**
     * SQL expression that strips exclusion/negation clauses (e.g. "Not intended for freshmen.",
     * "Not open to CS majors.", "Excludes online MCS students.") out of a scheduled_sections.notes
     * value before keyword matching, so cohort/tag detection doesn't fire on the thing a section
     * explicitly excludes. Mirrors the positive/negative segmentation done client-side in
     * kingfisher-web's parseNotesContext().
     *
     * The trigger phrases are wrapped in \y word boundaries so e.g. "excludes?" can't partial-match
     * inside "excluded" (that silently ate the rest of unrelated sentences, like course descriptions
     * mentioning "historically excluded composers"). The trailing group also strips any number of
     * directly-following sentences that repeat the just-excluded phrase as their subject (backreference
     * \1), which covers the common registrar pattern "Not intended for James Scholars. James Scholars
     * should file an HCLA ... James Scholars should register for discussion section ..." -- without it,
     * those follow-up sentences re-introduce the excluded keyword and undo the strip.
     */
    private static final String CLEAN_NOTES =
        "regexp_replace(ss.notes, '\\y(?:not\\s+(?:intended\\s+for|open\\s+to|available\\s+to|eligible\\s+for|for)" +
        "|excludes?|excluding|cannot\\s+be\\s+taken\\s+by|may\\s+not\\s+be\\s+taken\\s+by|no\\s+credit\\s+for" +
        "|not\\s+for|will\\s+not\\s+allow)\\y\\s+([^.]*)\\.(?:\\s*\\1\\M[^.]*\\.)*', '', 'gi')";

    public CourseService(CourseRepository courseRepository, CourseOfferingRepository courseOfferingRepository, SectionRepository sectionRepository, TermRepository termRepository, ScheduledSectionRepository scheduledSectionRepository, @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.courseRepository = courseRepository;
        this.courseOfferingRepository = courseOfferingRepository;
        this.sectionRepository = sectionRepository;
        this.termRepository = termRepository;
        this.scheduledSectionRepository = scheduledSectionRepository;
        this.baseUrl = baseUrl;
    }

    public PagedResponse<CourseSummaryDto> listCourses(String query, String subjectCode, String instructorName, Integer number, List<String> sectionTypes, String level, List<String> cohorts, String term, String filterMode, int page, int perPage, String sort, String order) {
        if (perPage > 100) perPage = 100;

        List<String> upperSectionTypes = (sectionTypes == null) ? List.of() :
                sectionTypes.stream().filter(t -> t != null && !t.isBlank()).map(t -> t.trim().toUpperCase()).distinct().toList();

        List<String> cleanCohorts = (cohorts == null) ? List.of() :
                cohorts.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).distinct().toList();

        String q = (query != null && !query.isBlank()) ? normalizeQuery(query) : null;
        boolean hasQuery = (q != null && !q.isBlank());
        boolean isExactMatch = hasQuery && q.matches("^[a-z]+\\s+\\d+$");

        StringBuilder sql = new StringBuilder();
        if (isExactMatch && (sort == null || "match".equalsIgnoreCase(sort) || "".equals(sort))) {
            sql.append("""
                WITH RECURSIVE
                  target_course AS (
                      SELECT c.id
                      FROM courses c JOIN subjects s ON s.id = c.subject_id
                      WHERE LOWER(s.code || ' ' || c.number::text) = :query LIMIT 1
                  ),
                  forward_chain(id, depth) AS (
                      SELECT id, 0 FROM target_course
                      UNION ALL
                      SELECT cp.course_id, fc.depth + 1
                      FROM course_prerequisites cp
                      JOIN forward_chain fc ON cp.prerequisite_id = fc.id
                      WHERE fc.depth < 5
                  ),
                  backward_chain(id, depth) AS (
                      SELECT id, 0 FROM target_course
                      UNION ALL
                      SELECT cp.prerequisite_id, bc.depth + 1
                      FROM course_prerequisites cp
                      JOIN backward_chain bc ON cp.course_id = bc.id
                      WHERE bc.depth < 5
                  ),
                  chain_distances AS (
                      SELECT id, MIN(depth) as depth, MAX(is_after) as is_after FROM (
                          SELECT id, MIN(depth) as depth, 1 as is_after FROM forward_chain WHERE depth > 0 GROUP BY id
                          UNION ALL
                          SELECT id, MIN(depth) as depth, 0 as is_after FROM backward_chain WHERE depth > 0 GROUP BY id
                      ) sub GROUP BY id
                  )
                """);
        }

        sql.append("""
            SELECT c.* FROM courses c
            JOIN subjects s ON s.id = c.subject_id
            LEFT JOIN course_grades cg ON c.id = cg.course_id
        """);

        if (isExactMatch && (sort == null || "match".equalsIgnoreCase(sort) || "".equals(sort))) {
            sql.append(" LEFT JOIN chain_distances cd ON c.id = cd.id ");
        }

        sql.append(" WHERE 1=1 ");

        if (hasQuery) {
            sql.append("""
                 AND (c.title % :query
                    OR (s.code || ' ' || c.number::text) % :query
                    OR (s.code || c.number::text) % :query
                    OR LOWER(c.title) LIKE '%' || :query || '%'
                    OR LOWER(s.code || ' ' || c.number::text) LIKE '%' || :query || '%')
            """);
        }

        if (subjectCode != null && !subjectCode.isBlank()) {
            sql.append(" AND s.code = :subject ");
        }

        if (number != null) {
            sql.append(" AND c.number = :number ");
        }

        if (level != null && !level.isBlank()) {
            if ("undergrad".equalsIgnoreCase(level) || "ug".equalsIgnoreCase(level)) {
                sql.append(" AND c.number < 500 ");
            } else if ("graduate".equalsIgnoreCase(level) || "grad".equalsIgnoreCase(level)) {
                sql.append(" AND c.number >= 500 ");
            }
        }

        if (term != null && !term.isBlank() && !"all".equalsIgnoreCase(term)) {
            sql.append(" AND EXISTS (SELECT 1 FROM course_offerings co JOIN terms t ON co.term_id = t.id WHERE co.course_id = c.id AND LOWER(t.year_term) = :term) ");
        }

        String exactInstructorName = instructorName == null ? null : instructorName.strip().toLowerCase(Locale.ROOT);
        String instructorPattern = InstructorNameSearch.patternFor(instructorName);
        String[] instructorTokens = null;
        if (instructorName != null && !instructorName.isBlank()) {
            instructorTokens = Arrays.stream(exactInstructorName.split("[\\s,]+"))
                                     .filter(t -> !t.isBlank())
                                     .toArray(String[]::new);
            if (instructorTokens.length > 0) {
                sql.append(" AND EXISTS (SELECT 1 FROM course_offerings co JOIN sections sec ON sec.course_offering_id = co.id JOIN instructors i ON i.id = sec.instructor_id WHERE co.course_id = c.id ");
                if (!instructorPattern.isEmpty()) {
                    sql.append(" AND ").append(InstructorNameSearch.NORMALIZED_NAME).append(" ~ :instPattern) ");
                } else {
                    sql.append(" AND (LOWER(BTRIM(i.name)) = :instExact OR (NOT EXISTS (SELECT 1 FROM instructors exact_i WHERE LOWER(BTRIM(exact_i.name)) = :instExact) ");
                    for (int i = 0; i < instructorTokens.length; i++) {
                        sql.append(" AND (LOWER(i.name) LIKE :inst").append(i)
                           .append(" OR i.name % :instRaw").append(i)
                           .append(" OR similarity(LOWER(i.name), :instRaw").append(i).append(") > 0.3) ");
                    }
                    sql.append("))) ");
                }
            }
        }

        if (!upperSectionTypes.isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM course_offerings co JOIN sections sec ON sec.course_offering_id = co.id WHERE co.course_id = c.id AND UPPER(sec.sched_type) IN (:sectionTypes)) ");
        }

        if (!cleanCohorts.isEmpty()) {
            List<String> positiveConditions = new ArrayList<>();
            List<String> negativeConditions = new ArrayList<>();
            for (String rawCohort : cleanCohorts) {
                boolean isNot = rawCohort.startsWith("!") || rawCohort.startsWith("-");
                String cohortTag = isNot ? rawCohort.substring(1).trim() : rawCohort.trim();
                String cond = getCohortSqlCondition(cohortTag);
                if (cond != null) {
                    if (isNot) {
                        negativeConditions.add(cond);
                    } else {
                        positiveConditions.add(cond);
                    }
                }
            }

            if ("or".equalsIgnoreCase(filterMode)) {
                if (!positiveConditions.isEmpty()) {
                    sql.append(" AND EXISTS (SELECT 1 FROM course_offerings co JOIN scheduled_sections ss ON ss.course_offering_id = co.id WHERE co.course_id = c.id AND (")
                       .append(String.join(" OR ", positiveConditions))
                       .append(")) ");
                }
                for (String negCond : negativeConditions) {
                    sql.append(" AND NOT EXISTS (SELECT 1 FROM course_offerings co JOIN scheduled_sections ss ON ss.course_offering_id = co.id WHERE co.course_id = c.id AND ")
                       .append(negCond)
                       .append(") ");
                }
            } else {
                for (String posCond : positiveConditions) {
                    sql.append(" AND EXISTS (SELECT 1 FROM course_offerings co JOIN scheduled_sections ss ON ss.course_offering_id = co.id WHERE co.course_id = c.id AND ")
                       .append(posCond)
                       .append(") ");
                }
                for (String negCond : negativeConditions) {
                    sql.append(" AND NOT EXISTS (SELECT 1 FROM course_offerings co JOIN scheduled_sections ss ON ss.course_offering_id = co.id WHERE co.course_id = c.id AND ")
                           .append(negCond)
                           .append(") ");
                }
            }
        }

        // Sorting
        String orderBy;
        if ("gpa".equalsIgnoreCase(sort)) {
            orderBy = "cg.gpa " + ("asc".equalsIgnoreCase(order) ? "ASC" : "DESC") + " NULLS LAST";
        } else if ("total_grades".equalsIgnoreCase(sort)) {
            orderBy = "cg.total_students " + ("asc".equalsIgnoreCase(order) ? "ASC" : "DESC") + " NULLS LAST";
        } else if ("title".equalsIgnoreCase(sort) || "name".equalsIgnoreCase(sort)) {
            orderBy = "c.title " + ("desc".equalsIgnoreCase(order) ? "DESC" : "ASC");
        } else if ("avg_students".equalsIgnoreCase(sort) || "popularity".equalsIgnoreCase(sort)) {
            orderBy = "cg.avg_students " + ("asc".equalsIgnoreCase(order) ? "ASC" : "DESC") + " NULLS LAST, cg.total_students " + ("asc".equalsIgnoreCase(order) ? "ASC" : "DESC") + " NULLS LAST";
        } else { // default to closest match if query, otherwise popularity
            if (hasQuery) {
                if (isExactMatch) {
                    orderBy = "(CASE WHEN LOWER(s.code || ' ' || c.number::text) = :query THEN 1 ELSE 0 END) DESC, " +
                              "cd.depth ASC NULLS LAST, " +
                              "cd.is_after DESC NULLS LAST, " +
                              "(CASE WHEN :query ~ ('\\y' || LOWER(s.code) || '\\y') THEN 1.0 ELSE 0.0 END) + " +
                              "GREATEST(similarity(c.title, :query), similarity(s.code || ' ' || c.number::text, :query)) DESC NULLS LAST, " +
                              "cg.total_students DESC NULLS LAST";
                } else {
                    orderBy = "(CASE WHEN :query ~ ('\\y' || LOWER(s.code) || '\\y') THEN 1.0 ELSE 0.0 END) + " +
                              "GREATEST(similarity(c.title, :query), similarity(s.code || ' ' || c.number::text, :query)) DESC NULLS LAST, " +
                              "cg.total_students DESC NULLS LAST";
                }
            } else {
                orderBy = "cg.avg_students DESC NULLS LAST, cg.total_students DESC NULLS LAST, s.code ASC, c.number ASC";
            }
        }

        String countSql = "SELECT count(*) FROM (" + sql.toString() + ") AS sq";
        sql.append(" ORDER BY ").append(orderBy);

        jakarta.persistence.Query queryObj = em.createNativeQuery(sql.toString(), Course.class);
        jakarta.persistence.Query countQueryObj = em.createNativeQuery(countSql);

        if (hasQuery) {
            queryObj.setParameter("query", q);
            countQueryObj.setParameter("query", q);
        }

        if (subjectCode != null && !subjectCode.isBlank()) {
            queryObj.setParameter("subject", subjectCode);
            countQueryObj.setParameter("subject", subjectCode);
        }

        if (number != null) {
            queryObj.setParameter("number", number);
            countQueryObj.setParameter("number", number);
        }

        if (term != null && !term.isBlank() && !"all".equalsIgnoreCase(term)) {
            queryObj.setParameter("term", term.trim().toLowerCase());
            countQueryObj.setParameter("term", term.trim().toLowerCase());
        }

        if (instructorTokens != null && instructorTokens.length > 0) {
            if (!instructorPattern.isEmpty()) {
                queryObj.setParameter("instPattern", instructorPattern);
                countQueryObj.setParameter("instPattern", instructorPattern);
            } else {
                queryObj.setParameter("instExact", exactInstructorName);
                countQueryObj.setParameter("instExact", exactInstructorName);
                for (int i = 0; i < instructorTokens.length; i++) {
                    queryObj.setParameter("inst" + i, "%" + instructorTokens[i] + "%");
                    queryObj.setParameter("instRaw" + i, instructorTokens[i]);
                    countQueryObj.setParameter("inst" + i, "%" + instructorTokens[i] + "%");
                    countQueryObj.setParameter("instRaw" + i, instructorTokens[i]);
                }
            }
        }

        if (!upperSectionTypes.isEmpty()) {
            queryObj.setParameter("sectionTypes", upperSectionTypes);
            countQueryObj.setParameter("sectionTypes", upperSectionTypes);
        }

        queryObj.setFirstResult((page - 1) * perPage);
        queryObj.setMaxResults(perPage);

        @SuppressWarnings("unchecked")
        List<Course> list = queryObj.getResultList();
        long total = ((Number) countQueryObj.getSingleResult()).longValue();
        Page<Course> result = new PageImpl<>(list, PageRequest.of(page - 1, perPage), total);

        List<String> availableCohorts = new ArrayList<>();
        if (!list.isEmpty() || total > 0) {
            String tagSubquery = "SELECT tag FROM (VALUES " +
                "('undergrad'), ('graduate'), ('freshman'), ('senior'), " +
                "('online'), ('online-mcs'), ('chicago-scholars'), ('online-business'), " +
                "('coursera'), ('honors'), ('study-abroad'), ('netmath'), " +
                "('majors-only'), ('non-majors'), ('approval-required'), " +
                "('asynchronous'), ('synchronous'), ('additional-fee')" +
                ") AS all_tags(tag) " +
                "WHERE EXISTS ( " +
                "    SELECT 1 FROM (" + sql.toString() + ") filtered_c " +
                "    JOIN course_offerings co ON co.course_id = filtered_c.id " +
                "    JOIN scheduled_sections ss ON ss.course_offering_id = co.id " +
                "    WHERE ( " +
                "        CASE tag " +
                "            WHEN 'undergrad' THEN (filtered_c.number < 500 OR LOWER(" + CLEAN_NOTES + ") LIKE '%undergrad%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%undergraduate%' OR UPPER(ss.section_number) LIKE '%U%' OR UPPER(ss.section_number) LIKE '%QU%' OR UPPER(ss.section_number) LIKE '%RU%' OR UPPER(ss.section_number) LIKE '%AMU%') " +
                "            WHEN 'graduate' THEN (filtered_c.number >= 500 OR LOWER(" + CLEAN_NOTES + ") ~* '\\ygrad(uate)?\\y' OR UPPER(ss.section_number) LIKE '%G%' OR UPPER(ss.section_number) LIKE '%QG%' OR UPPER(ss.section_number) LIKE '%RG%' OR UPPER(ss.section_number) LIKE '%AMG%') " +
                "            WHEN 'freshman' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%first-time freshman%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%first time freshman%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%freshman%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%first-year%') " +
                "            WHEN 'senior' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%senior standing%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%senior%') " +
                "            WHEN 'online' THEN (UPPER(ss.section_number) LIKE 'ONL%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%online%' OR EXISTS (SELECT 1 FROM scheduled_section_meetings ssm WHERE ssm.scheduled_section_id = ss.id AND UPPER(ssm.type_code) = 'ONL')) " +
                "            WHEN 'online-mcs' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%online mcs%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%mcs-ds%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%chicago mcs%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%master of computer science online%' OR UPPER(ss.section_number) LIKE 'DS%' OR UPPER(ss.section_number) LIKE 'MC%') " +
                "            WHEN 'chicago-scholars' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%chicago city scholars%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%chicago scholars%' OR UPPER(ss.section_number) = 'CSP') " +
                "            WHEN 'online-business' THEN (LOWER(" + CLEAN_NOTES + ") ~* '\\y(online msm|msm online|imba|imsm|imsa|ianalytics|gies online)\\y') " +

                "            WHEN 'coursera' THEN LOWER(" + CLEAN_NOTES + ") LIKE '%coursera%' " +
                "            WHEN 'honors' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%honors%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%james scholar%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%chancellor%scholar%' OR UPPER(ss.section_number) LIKE 'H%') " +
                "            WHEN 'study-abroad' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%study abroad%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%off-campus%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%off campus%') " +
                "            WHEN 'netmath' THEN LOWER(" + CLEAN_NOTES + ") LIKE '%netmath%' " +
                "            WHEN 'majors-only' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%restricted to %major%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%majors only%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%for % majors only%') " +
                "            WHEN 'non-majors' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%non-major%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%non major%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%non cs majors%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%for non-%') " +
                "            WHEN 'approval-required' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%consent of instructor%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%instructor approval%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%departmental approval%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%departmental consent%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%authorization required%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%by permit only%') " +
                "            WHEN 'asynchronous' THEN LOWER(" + CLEAN_NOTES + ") ~* '\\yasynchronous\\y' " +
                "            WHEN 'synchronous' THEN (LOWER(" + CLEAN_NOTES + ") ~* '\\ysynchronous\\y' AND LOWER(ss.notes) !~* '\\yno\\s+synchronous\\y|\\ynot\\s+synchronous\\y|synchronous\\s+attendance\\s+not\\s+required') " +
                "            WHEN 'additional-fee' THEN (LOWER(" + CLEAN_NOTES + ") LIKE '%course fee%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%lab fee%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%additional % fee%' OR LOWER(" + CLEAN_NOTES + ") ~* '\\ydifferential\\s+(tuition|fee)') " +
                "            ELSE FALSE " +
                "        END " +
                "    ) " +
                ")";

            try {
                jakarta.persistence.Query tagQuery = em.createNativeQuery(tagSubquery);
                if (hasQuery) tagQuery.setParameter("query", q);
                if (subjectCode != null && !subjectCode.isBlank()) tagQuery.setParameter("subject", subjectCode);
                if (number != null) tagQuery.setParameter("number", number);
                if (term != null && !term.isBlank() && !"all".equalsIgnoreCase(term)) tagQuery.setParameter("term", term.trim().toLowerCase());
                if (instructorTokens != null && instructorTokens.length > 0) {
                    if (!instructorPattern.isEmpty()) {
                        tagQuery.setParameter("instPattern", instructorPattern);
                    } else {
                        tagQuery.setParameter("instExact", exactInstructorName);
                        for (int i = 0; i < instructorTokens.length; i++) {
                            tagQuery.setParameter("inst" + i, "%" + instructorTokens[i] + "%");
                            tagQuery.setParameter("instRaw" + i, instructorTokens[i]);
                        }
                    }
                }
                if (!upperSectionTypes.isEmpty()) tagQuery.setParameter("sectionTypes", upperSectionTypes);

                @SuppressWarnings("unchecked")
                List<String> tags = tagQuery.getResultList();
                availableCohorts.addAll(tags);
            } catch (Exception ignored) {}
        }

        List<CourseSummaryDto> dtos = result.stream().map(c -> new CourseSummaryDto(
                c.getId(),
                new SubjectSummaryDto(c.getSubject().getId(), c.getSubject().getCode()),
                c.getNumber(),
                c.getTitle(),
                c.getCourseGrade() != null ? c.getCourseGrade().getGpa() : null,
                c.getCourseGrade() != null ? c.getCourseGrade().getTotalStudents() : null,
                baseUrl + "/v1/courses/" + c.getId()
        )).toList();

        return new PagedResponse<>(page, result.getTotalPages(), result.getTotalElements(), dtos, availableCohorts);
    }


    private static final Map<String, String> SEARCH_SYNONYMS = new LinkedHashMap<>();
    static {
        // Subjects
        SEARCH_SYNONYMS.put("computer science", "CS");
        SEARCH_SYNONYMS.put("comp sci", "CS");
        SEARCH_SYNONYMS.put("math", "MATH");
        SEARCH_SYNONYMS.put("mathematics", "MATH");
        SEARCH_SYNONYMS.put("stat", "STAT");
        SEARCH_SYNONYMS.put("statistics", "STAT");
        SEARCH_SYNONYMS.put("phys", "PHYS");
        SEARCH_SYNONYMS.put("physics", "PHYS");
        SEARCH_SYNONYMS.put("econ", "ECON");
        SEARCH_SYNONYMS.put("economics", "ECON");
        SEARCH_SYNONYMS.put("bio", "IB");
        SEARCH_SYNONYMS.put("biology", "IB");
        SEARCH_SYNONYMS.put("chem", "CHEM");
        SEARCH_SYNONYMS.put("chemistry", "CHEM");
        
        // Course specific tricky terms
        SEARCH_SYNONYMS.put("algos", "algs");
        SEARCH_SYNONYMS.put("algorithms", "algs");
        SEARCH_SYNONYMS.put("introduction", "intro");
        SEARCH_SYNONYMS.put("computer", "comp");
        SEARCH_SYNONYMS.put("computation", "comp");
        SEARCH_SYNONYMS.put("computing", "comp");
        SEARCH_SYNONYMS.put("and", "&");
    }

    private String normalizeQuery(String q) {
        String normalized = q.toLowerCase().trim();
        
        for (Map.Entry<String, String> entry : SEARCH_SYNONYMS.entrySet()) {
            // Use word boundaries to avoid replacing parts of words (e.g., "statistical" -> "STATistical")
            normalized = normalized.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue().toLowerCase());
        }
        
        // Ensure space between subject and number (e.g., "cs374" -> "cs 374")
        normalized = normalized.replaceAll("([a-z])(\\d)", "$1 $2");
        
        // Handle common course renumberings/equivalents
        normalized = normalized.replaceAll("\\bcs\\s+241\\b", "cs 341");
        normalized = normalized.replaceAll("\\bcs\\s+125\\b", "cs 124");
        normalized = normalized.replaceAll("\\bcs\\s+242\\b", "cs 222");
        
        // Translate Arabic numerals to Roman numerals for course titles (e.g. "Calculus 3" -> "Calculus III")
        normalized = normalized.replaceAll("\\b1\\b", "i");
        normalized = normalized.replaceAll("\\b2\\b", "ii");
        normalized = normalized.replaceAll("\\b3\\b", "iii");
        normalized = normalized.replaceAll("\\b4\\b", "iv");
        normalized = normalized.replaceAll("\\b5\\b", "v");
        
        return normalized.trim();
    }

    public CourseDetailDto getCourse(Long id) {
        Course course = courseRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Course not found"));
        List<CourseOffering> offerings = courseOfferingRepository.findByCourseId(id);
        
        List<CourseOfferingSummaryDto> coDtos = offerings.stream().map(co -> new CourseOfferingSummaryDto(
                co.getId(),
                new TermDto(co.getTerm().getId(), co.getTerm().getYear(), co.getTerm().getSeason(), co.getTerm().getYearTerm()),
                baseUrl + "/v1/course_offerings/" + co.getId()
        )).toList();

        List<Course> crosslisted = courseRepository.findByTitleAndNumber(course.getTitle(), (short) course.getNumber());
        List<CourseSummaryDto> crosslistedDtos = crosslisted.stream()
                .filter(c -> !c.getId().equals(id))
                .map(c -> new CourseSummaryDto(
                        c.getId(),
                        new SubjectSummaryDto(c.getSubject().getId(), c.getSubject().getCode()),
                        c.getNumber(),
                        c.getTitle(),
                        c.getCourseGrade() != null ? c.getCourseGrade().getGpa() : null,
                        c.getCourseGrade() != null ? c.getCourseGrade().getTotalStudents() : null,
                        baseUrl + "/v1/courses/" + c.getId()
                )).toList();

        return new CourseDetailDto(
                id,
                new SubjectSummaryDto(course.getSubject().getId(), course.getSubject().getCode()),
                course.getNumber(),
                course.getTitle(),
                course.getDescription(),
                baseUrl + "/v1/courses/" + id + "/grades",
                coDtos,
                crosslistedDtos
        );
    }

    public CourseGradesResponseDto getCourseGrades(Long id) {
        if (!courseRepository.existsById(id)) throw new ResourceNotFoundException("Course not found");
        
        List<CourseOffering> offerings = courseOfferingRepository.findByCourseId(id);
        List<Long> coIds = offerings.stream().map(CourseOffering::getId).toList();
        List<Section> allSections = sectionRepository.findByCourseOfferingIdIn(coIds);

        Map<Long, List<Section>> sectionsByCo = allSections.stream().collect(Collectors.groupingBy(s -> s.getCourseOffering().getId()));

        List<CourseOfferingGradeDto> coGradeDtos = new ArrayList<>();
        List<GradeDistributionDto> overallDists = new ArrayList<>();

        offerings.sort((o1, o2) -> {
            int y1 = o1.getTerm().getYear();
            int y2 = o2.getTerm().getYear();
            if (y1 != y2) return Integer.compare(y2, y1);
            return Integer.compare(seasonOrder(o2.getTerm().getSeason()), seasonOrder(o1.getTerm().getSeason()));
        });

        for (CourseOffering co : offerings) {
            List<Section> sections = sectionsByCo.getOrDefault(co.getId(), Collections.emptyList());
            List<SectionGradeDto> sDtos = new ArrayList<>();
            List<GradeDistributionDto> coDists = new ArrayList<>();

            for (Section s : sections) {
                GradeDistributionDto sd = GpaCalculator.fromCounts(
                        s.getAPlus(), s.getA(), s.getAMinus(),
                        s.getBPlus(), s.getB(), s.getBMinus(),
                        s.getCPlus(), s.getC(), s.getCMinus(),
                        s.getDPlus(), s.getD(), s.getDMinus(),
                        s.getF(), s.getW()
                );
                sDtos.add(new SectionGradeDto(
                        s.getId(),
                        s.getSchedType(),
                        new InstructorSummaryDto(s.getInstructor().getId(), s.getInstructor().getName()),
                        sd
                ));
                coDists.add(sd);
            }

            GradeDistributionDto coCum = GpaCalculator.sum(coDists);
            overallDists.add(coCum);

            coGradeDtos.add(new CourseOfferingGradeDto(
                    co.getTerm().getId(),
                    co.getTerm().getYearTerm(),
                    coCum,
                    sDtos
            ));
        }

        GradeDistributionDto cumulative = GpaCalculator.sum(overallDists);
        return new CourseGradesResponseDto(id, cumulative, coGradeDtos);
    }

    public List<ScheduledSectionDto> getScheduledSections(Long id, String yearTerm) {
        if (!courseRepository.existsById(id)) throw new ResourceNotFoundException("Course not found");
        var term = termRepository.findByYearTerm(yearTerm)
                .orElseThrow(() -> new ResourceNotFoundException("Term not found: " + yearTerm));
        var offeringOpt = courseOfferingRepository.findByCourseIdAndTermId(id, term.getId());
        if (offeringOpt.isEmpty()) return List.of();

        List<ScheduledSection> sections = scheduledSectionRepository.findByCourseOfferingIdOrderBySectionNumberAsc(offeringOpt.get().getId());
        return sections.stream().map(s -> new ScheduledSectionDto(
                s.getId(),
                s.getCrn(),
                s.getSectionNumber(),
                s.getStatusCode(),
                s.getPartOfTerm(),
                s.getStartDate(),
                s.getEndDate(),
                s.getNotes(),
                s.getMeetings().stream().map(m -> new ScheduledSectionMeetingDto(
                        m.getStartTime(),
                        m.getEndTime(),
                        m.getDaysOfWeek(),
                        m.getRoomNumber(),
                        m.getBuildingName(),
                        m.getTypeCode(),
                        m.getTypeDescription(),
                        m.getInstructors().stream().map(i -> new InstructorSummaryDto(i.getId(), i.getName())).toList()
                )).toList()
        )).toList();
    }

    private String getCohortSqlCondition(String tag) {
        if (tag == null || tag.isBlank()) return null;
        String t = tag.trim().toLowerCase();
        return switch (t) {
            case "undergrad", "undergrad-section" -> "(c.number < 500 OR LOWER(" + CLEAN_NOTES + ") LIKE '%undergrad%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%undergraduate%' OR UPPER(ss.section_number) LIKE '%U%' OR UPPER(ss.section_number) LIKE '%QU%' OR UPPER(ss.section_number) LIKE '%RU%' OR UPPER(ss.section_number) LIKE '%AMU%')";
            case "graduate", "grad", "grad-section" -> "(c.number >= 500 OR LOWER(" + CLEAN_NOTES + ") ~* '\\ygrad(uate)?\\y' OR UPPER(ss.section_number) LIKE '%G%' OR UPPER(ss.section_number) LIKE '%QG%' OR UPPER(ss.section_number) LIKE '%RG%' OR UPPER(ss.section_number) LIKE '%AMG%')";
            case "online" -> "(UPPER(ss.section_number) LIKE 'ONL%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%online%' OR EXISTS (SELECT 1 FROM scheduled_section_meetings ssm WHERE ssm.scheduled_section_id = ss.id AND UPPER(ssm.type_code) = 'ONL'))";
            case "online-mcs" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%online mcs%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%mcs-ds%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%chicago mcs%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%master of computer science online%' OR UPPER(ss.section_number) LIKE 'DS%' OR UPPER(ss.section_number) LIKE 'MC%')";


            case "chicago-scholars", "chicago-city-scholars" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%chicago city scholars%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%chicago scholars%' OR UPPER(ss.section_number) = 'CSP')";
            case "online-business" -> "(LOWER(" + CLEAN_NOTES + ") ~* '\\y(online msm|msm online|imba|imsm|imsa|ianalytics|gies online)\\y')";

            case "coursera" -> "LOWER(" + CLEAN_NOTES + ") LIKE '%coursera%'";
            case "honors" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%honors%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%james scholar%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%chancellor%scholar%' OR UPPER(ss.section_number) LIKE 'H%')";
            case "majors-only" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%restricted to %major%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%majors only%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%for % majors only%')";
            case "non-majors" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%non-major%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%non major%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%non cs majors%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%for non-%')";
            case "study-abroad" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%study abroad%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%off-campus%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%off campus%')";
            case "freshman" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%first-time freshman%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%first time freshman%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%freshman%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%first-year%')";
            case "senior" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%senior standing%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%senior%')";
            case "approval-required" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%consent of instructor%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%instructor approval%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%departmental approval%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%departmental consent%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%authorization required%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%by permit only%')";
            case "fee", "additional-fee" -> "(LOWER(" + CLEAN_NOTES + ") LIKE '%course fee%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%lab fee%' OR LOWER(" + CLEAN_NOTES + ") LIKE '%additional % fee%' OR LOWER(" + CLEAN_NOTES + ") ~* '\\ydifferential\\s+(tuition|fee)')";
            case "asynchronous" -> "LOWER(" + CLEAN_NOTES + ") ~* '\\yasynchronous\\y'";
            case "synchronous" -> "(LOWER(" + CLEAN_NOTES + ") ~* '\\ysynchronous\\y' AND LOWER(ss.notes) !~* '\\yno\\s+synchronous\\y|\\ynot\\s+synchronous\\y|synchronous\\s+attendance\\s+not\\s+required')";
            case "netmath" -> "LOWER(" + CLEAN_NOTES + ") LIKE '%netmath%'";
            default -> null;
        };
    }

    private int seasonOrder(String season) {
        switch (season.toLowerCase()) {
            case "spring": return 1;
            case "summer": return 2;
            case "fall": return 3;
            case "winter": return 4;
            default: return 0;
        }
    }
}

