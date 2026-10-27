package com.illini.grades.repository;

import com.illini.grades.entity.Instructor;
import com.illini.grades.util.InstructorNameSearch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InstructorRepository extends JpaRepository<Instructor, Long> {
    Optional<Instructor> findByName(String name);

    String SEARCH_PREDICATE = """
        WHERE (:namePattern <> '' AND
        """ + InstructorNameSearch.NORMALIZED_NAME + """
         ~ :namePattern) OR (:namePattern = '' AND (
            i.name % :query
            OR LOWER(i.name) LIKE '%' || LOWER(:query) || '%'
            OR (
                SELECT bool_and(LOWER(i.name) LIKE '%' || t || '%')
                FROM unnest(string_to_array(LOWER(:query), ' ')) t
                WHERE t <> ''
            )
        ))
        """;

    @Query(value = "SELECT i.* FROM instructors i " + SEARCH_PREDICATE
            + " ORDER BY similarity(i.name, :query) DESC, LOWER(i.name), i.id",
        countQuery = "SELECT count(*) FROM instructors i " + SEARCH_PREDICATE,
        nativeQuery = true)
    Page<Instructor> searchByQuery(@Param("query") String query,
                                 @Param("namePattern") String namePattern, Pageable pageable);
}
