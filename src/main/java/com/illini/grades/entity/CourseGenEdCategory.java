package com.illini.grades.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "course_gen_ed_categories")
@Getter
@Setter
@NoArgsConstructor
public class CourseGenEdCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "category_id", nullable = false, length = 20)
    private String categoryId;

    @Column(name = "category_name", length = 100)
    private String categoryName;

    @Column(name = "attribute_code", nullable = false, length = 20)
    private String attributeCode = "";

    @Column(name = "attribute_name", length = 100)
    private String attributeName;
}
