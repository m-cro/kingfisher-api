package com.illini.grades.service;

import com.illini.grades.entity.CourseGenEdCategory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** XML shapes below are real UIUC Course Explorer course responses (trimmed). */
class GenEdParsingTest {

    private static Element root(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false); // matches SectionScheduleIngestionService.parse
        return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    @Test
    void parsesSingleCategory() throws Exception {
        List<CourseGenEdCategory> rows = SectionScheduleIngestionService.parseGenEd(root(
                "<ns2:course id=\"CS 225\" xmlns:ns2=\"http://rest.cis.illinois.edu\"><label>Data Structures</label>"
                + "<genEdCategories><category id=\"QR\"><description>Quantitative Reasoning</description>"
                + "<ns2:genEdAttributes><genEdAttribute code=\"1QR2\">Quantitative Reasoning II</genEdAttribute></ns2:genEdAttributes>"
                + "</category></genEdCategories></ns2:course>"));
        assertEquals(1, rows.size());
        assertEquals("QR", rows.get(0).getCategoryId());
        assertEquals("Quantitative Reasoning", rows.get(0).getCategoryName());
        assertEquals("1QR2", rows.get(0).getAttributeCode());
        assertEquals("Quantitative Reasoning II", rows.get(0).getAttributeName());
    }

    @Test
    void parsesMultipleCategories() throws Exception {
        List<CourseGenEdCategory> rows = SectionScheduleIngestionService.parseGenEd(root(
                "<ns2:course id=\"ANTH 103\" xmlns:ns2=\"http://rest.cis.illinois.edu\"><genEdCategories>"
                + "<category id=\"CS\"><description>Cultural Studies</description><ns2:genEdAttributes>"
                + "<genEdAttribute code=\"1NW\">Cultural Studies - Non-West</genEdAttribute></ns2:genEdAttributes></category>"
                + "<category id=\"SBS\"><description>Social &amp; Behavioral Sciences</description><ns2:genEdAttributes>"
                + "<genEdAttribute code=\"1SS\">Social &amp; Beh Sci - Soc Sci</genEdAttribute></ns2:genEdAttributes></category>"
                + "</genEdCategories></ns2:course>"));
        assertEquals(List.of("CS", "SBS"), rows.stream().map(CourseGenEdCategory::getCategoryId).toList());
        assertEquals("Social & Behavioral Sciences", rows.get(1).getCategoryName());
    }

    @Test
    void noGenEdYieldsEmpty() throws Exception {
        assertTrue(SectionScheduleIngestionService.parseGenEd(root(
                "<ns2:course id=\"X 1\" xmlns:ns2=\"http://rest.cis.illinois.edu\"><label>x</label></ns2:course>")).isEmpty());
    }

    @Test
    void categoryWithoutAttributesKeepsEmptyCode() throws Exception {
        List<CourseGenEdCategory> rows = SectionScheduleIngestionService.parseGenEd(root(
                "<course id=\"X 1\"><genEdCategories><category id=\"HUM\"><description>Humanities</description></category></genEdCategories></course>"));
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).getAttributeCode());
    }
}
