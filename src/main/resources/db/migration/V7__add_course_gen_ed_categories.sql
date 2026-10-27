-- Gen Ed categories per course, sourced from the UIUC Course Explorer course XML
-- (<genEdCategories>). A course can satisfy several categories (e.g. ANTH 103 is both
-- Cultural Studies and Social & Behavioral Sciences). attribute_code is the finer-grained
-- attribute (e.g. 1QR2 = Quantitative Reasoning II); '' when the source gives none.

CREATE TABLE course_gen_ed_categories (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    category_id VARCHAR(20) NOT NULL,
    category_name VARCHAR(100),
    attribute_code VARCHAR(20) NOT NULL DEFAULT '',
    attribute_name VARCHAR(100),
    UNIQUE (course_id, category_id, attribute_code)
);

CREATE INDEX idx_course_gen_ed_categories_category_id ON course_gen_ed_categories(category_id);
