package com.illini.grades.service;

import com.illini.grades.entity.Course;
import com.illini.grades.entity.CourseOffering;
import com.illini.grades.entity.Subject;
import com.illini.grades.entity.Term;
import com.illini.grades.repository.CourseOfferingRepository;
import com.illini.grades.repository.CourseRepository;
import com.illini.grades.repository.ScheduledSectionRepository;
import com.illini.grades.repository.SubjectRepository;
import com.illini.grades.repository.TermRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ingests live section schedules for a given term (year + season) from the public
 * UIUC Course Explorer "schedule" API: courses.illinois.edu/cisapp/explorer/schedule.
 *
 * Unlike the historical grade CSV ingestion, this walks the API's own catalog
 * (term -> subjects -> courses -> sections -> section detail) rather than our
 * existing course list, so it only touches courses actually offered in the
 * target term. Safe to re-run for the same term: upserts by CRN and prunes
 * sections that disappeared from the schedule (cancelled/renumbered), so it
 * doubles as a refresh once seats/times change during registration.
 *
 * Sequential with a minimum delay plus random jitter between requests and aborts on a streak of 403s
 * (WAF block) rather than burning through the remaining queue -- re-running the
 * admin endpoint later picks up wherever it left off, since already-ingested
 * sections are just re-upserted.
 */
@Service
public class SectionScheduleIngestionService {

    private static final String BASE = "https://courses.illinois.edu/cisapp/explorer/schedule";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final Duration REQUEST_DELAY = Duration.ofMillis(750);
    private static final int ABORT_ON_403_STREAK = 5;
    private static final List<String> VALID_SEASONS = List.of("spring", "summer", "fall", "winter");

    @Value("${COURSE_EXPLORER_USER_AGENT:" + DEFAULT_USER_AGENT + "}")
    private String userAgent = DEFAULT_USER_AGENT;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final TermRepository termRepository;
    private final SubjectRepository subjectRepository;
    private final CourseRepository courseRepository;
    private final CourseOfferingRepository courseOfferingRepository;
    private final ScheduledSectionRepository scheduledSectionRepository;
    private final ScheduledSectionWriter writer;
    private final EntityManager entityManager;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public SectionScheduleIngestionService(TermRepository termRepository, SubjectRepository subjectRepository,
                                            CourseRepository courseRepository, CourseOfferingRepository courseOfferingRepository,
                                            ScheduledSectionRepository scheduledSectionRepository, ScheduledSectionWriter writer,
                                            EntityManager entityManager) {
        this.termRepository = termRepository;
        this.subjectRepository = subjectRepository;
        this.courseRepository = courseRepository;
        this.courseOfferingRepository = courseOfferingRepository;
        this.scheduledSectionRepository = scheduledSectionRepository;
        this.writer = writer;
        this.entityManager = entityManager;
    }

    public boolean isRunning() {
        return running.get();
    }

    public static boolean isValidSeason(String season) {
        return VALID_SEASONS.contains(season.toLowerCase());
    }

    /** Kicks off the ingest; no-ops (returns false) if one is already running. */
    public boolean start(int year, String season) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("section-ingest-" + year + "-" + season.toLowerCase()).start(() -> {
            try {
                ingestTerm(year, season);
            } catch (Exception e) {
                System.err.println("Section ingestion for " + season + " " + year + " failed: " + e);
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    private void ingestTerm(int year, String season) {
        String seasonLower = season.toLowerCase();
        String seasonCap = Character.toUpperCase(seasonLower.charAt(0)) + seasonLower.substring(1);
        String yearTerm = year + "-" + seasonCode(seasonLower);

        System.out.println("Starting scheduled-section ingestion for " + seasonCap + " " + year + "...");

        FetchResult termListResult = fetch(BASE + "/" + year + "/" + seasonLower + ".xml");
        if (termListResult.status() != 200) {
            System.err.println("Could not fetch subject list for " + seasonCap + " " + year + " (HTTP " + termListResult.status() + "). Aborting.");
            return;
        }

        List<String> subjectCodes;
        try {
            Element root = parse(termListResult.body()).getDocumentElement();
            subjectCodes = attrValues(child(root, "subjects"), "subject", "id");
        } catch (Exception e) {
            System.err.println("Could not parse subject list: " + e.getMessage());
            return;
        }

        Term term = termRepository.findByYearTerm(yearTerm).orElseGet(() -> {
            Term t = new Term();
            t.setYear((short) year);
            t.setSeason(seasonCap);
            t.setYearTerm(yearTerm);
            return termRepository.save(t);
        });

        int consecutive403 = 0;
        int subjectsProcessed = 0, coursesProcessed = 0, sectionsUpserted = 0;
        boolean aborted = false;

        outer:
        for (String subjectCode : subjectCodes) {
            sleepBetweenRequests();
            FetchResult subjRes = fetch(BASE + "/" + year + "/" + seasonLower + "/" + subjectCode + ".xml");
            if (subjRes.status() == 403) {
                if (++consecutive403 >= ABORT_ON_403_STREAK) { aborted = true; break; }
                continue;
            }
            if (subjRes.status() != 200) continue;
            consecutive403 = 0;

            List<String[]> courseEntries;
            Element subjRoot;
            try {
                subjRoot = parse(subjRes.body()).getDocumentElement();
                courseEntries = courseListEntries(subjRoot);
            } catch (Exception e) {
                continue;
            }
            if (courseEntries.isEmpty()) {
                subjectsProcessed++;
                continue;
            }

            final Subject subject = subjectRepository.findByCode(subjectCode).orElseGet(() -> {
                Subject s = new Subject();
                s.setCode(subjectCode);
                return subjectRepository.save(s);
            });

            for (String[] entry : courseEntries) {
                String numberStr = entry[0];
                String label = entry[1];
                Short number;
                try {
                    number = Short.parseShort(numberStr);
                } catch (NumberFormatException e) {
                    continue;
                }

                sleepBetweenRequests();
                FetchResult courseRes = fetch(BASE + "/" + year + "/" + seasonLower + "/" + subjectCode + "/" + numberStr + ".xml");
                if (courseRes.status() == 403) {
                    if (++consecutive403 >= ABORT_ON_403_STREAK) { aborted = true; break outer; }
                    continue;
                }
                if (courseRes.status() != 200) continue;
                consecutive403 = 0;

                List<String> crns;
                try {
                    Element courseRoot = parse(courseRes.body()).getDocumentElement();
                    crns = attrValues(child(courseRoot, "sections"), "section", "id");
                } catch (Exception e) {
                    continue;
                }

                List<Course> existingCourses = courseRepository.findBySubjectIdAndNumber(subject.getId(), number);
                Course course = existingCourses.isEmpty() ? null : existingCourses.get(0);
                if (course == null) {
                    if (crns.isEmpty()) continue; // don't create courses we have zero section data for
                    Course c = new Course();
                    c.setSubject(subject);
                    c.setNumber(number);
                    c.setTitle(label);
                    course = courseRepository.save(c);
                }

                final Course finalCourse = course;
                CourseOffering offering = courseOfferingRepository.findByCourseIdAndTermId(course.getId(), term.getId())
                        .orElseGet(() -> {
                            CourseOffering co = new CourseOffering();
                            co.setCourse(finalCourse);
                            co.setTerm(term);
                            return courseOfferingRepository.save(co);
                        });

                if (crns.isEmpty()) {
                    scheduledSectionRepository.deleteByCourseOfferingId(offering.getId());
                    coursesProcessed++;
                    continue;
                }

                List<String> seenCrns = new ArrayList<>();
                for (String crn : crns) {
                    sleepBetweenRequests();
                    FetchResult sectionRes = fetch(BASE + "/" + year + "/" + seasonLower + "/" + subjectCode + "/" + numberStr + "/" + crn + ".xml");
                    if (sectionRes.status() == 403) {
                        if (++consecutive403 >= ABORT_ON_403_STREAK) { aborted = true; break outer; }
                        continue;
                    }
                    if (sectionRes.status() != 200) continue;
                    consecutive403 = 0;

                    try {
                        Element sectionRoot = parse(sectionRes.body()).getDocumentElement();
                        ParsedSection parsed = parseSection(sectionRoot);
                        writer.upsert(offering, crn, parsed);
                        seenCrns.add(crn);
                        sectionsUpserted++;
                    } catch (Exception e) {
                        System.err.println("Skipping section " + subjectCode + " " + numberStr + " CRN " + crn + " due to error: " + e.getMessage());
                    }
                }

                if (!seenCrns.isEmpty()) {
                    scheduledSectionRepository.deleteStale(offering.getId(), seenCrns);
                }

                coursesProcessed++;
                if (coursesProcessed % 50 == 0) {
                    entityManager.clear();
                    System.out.println("  ... " + coursesProcessed + " courses processed, " + sectionsUpserted + " sections upserted so far");
                }
            }
            subjectsProcessed++;
        }

        entityManager.clear();
        System.out.println("Finished scheduled-section ingestion for " + seasonCap + " " + year + ". "
                + subjectsProcessed + "/" + subjectCodes.size() + " subjects, " + coursesProcessed + " courses, "
                + sectionsUpserted + " sections upserted."
                + (aborted ? " Aborted early after repeated 403s (WAF block) -- safe to re-trigger later, already-ingested data is untouched." : ""));
    }

    private void sleepBetweenRequests() {
        try {
            Thread.sleep(REQUEST_DELAY.toMillis() + ThreadLocalRandom.current().nextLong(501));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String seasonCode(String seasonLower) {
        return switch (seasonLower) {
            case "spring" -> "sp";
            case "summer" -> "su";
            case "fall" -> "fa";
            case "winter" -> "wi";
            default -> throw new IllegalArgumentException("Unknown season: " + seasonLower);
        };
    }

    private FetchResult fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/xml, text/xml;q=0.9, */*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new FetchResult(response.statusCode(), response.body());
        } catch (Exception e) {
            return new FetchResult(-1, "");
        }
    }

    private Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static Element child(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && el.getTagName().equals(tagName)) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && el.getTagName().equals(tagName)) {
                result.add(el);
            }
        }
        return result;
    }

    private static String text(Element parent, String tagName) {
        Element el = child(parent, tagName);
        if (el == null) return null;
        String t = el.getTextContent();
        if (t == null) return null;
        t = t.trim();
        return t.isEmpty() ? null : t;
    }

    /** attribute values of each <tagName> child under the child named containerTag */
    private static List<String> attrValues(Element container, String tagName, String attr) {
        List<String> result = new ArrayList<>();
        for (Element el : children(container, tagName)) {
            String v = el.getAttribute(attr);
            if (v != null && !v.isBlank()) result.add(v);
        }
        return result;
    }

    /** [number, label] pairs for each <course> under <courses> */
    private static List<String[]> courseListEntries(Element subjectRoot) {
        List<String[]> result = new ArrayList<>();
        for (Element el : children(child(subjectRoot, "courses"), "course")) {
            String number = el.getAttribute("id");
            String label = el.getTextContent() != null ? el.getTextContent().trim() : "";
            if (number != null && !number.isBlank()) result.add(new String[]{number, label});
        }
        return result;
    }

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd-yy");

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim();
        if (cleaned.endsWith("Z")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        try {
            return LocalDate.parse(cleaned, DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, java.time.Instant> lastRefreshTimes = new java.util.concurrent.ConcurrentHashMap<>();

    public record CourseRefreshStatus(
        boolean success,
        String message,
        long secondsRemaining,
        java.time.Instant lastRefreshedAt,
        int sectionsCount
    ) {}

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 3 * * SUN")
    public void scheduledWeeklyIngest() {
        String defaultTerm = termRepository.findAllByOrderByYearAscSeasonAsc().stream()
                .reduce((first, second) -> second)
                .map(Term::getYearTerm)
                .orElse("2026-fa");
        String[] parts = defaultTerm.split("-");
        int year = Integer.parseInt(parts[0]);
        String season = switch (parts[1]) {
            case "sp" -> "spring";
            case "su" -> "summer";
            case "fa" -> "fall";
            case "wi" -> "winter";
            default -> "fall";
        };
        start(year, season);
    }


    public CourseRefreshStatus getCourseRefreshStatus(Long courseId, String yearTerm) {
        String key = courseId + ":" + (yearTerm != null ? yearTerm.toLowerCase() : "default");
        java.time.Instant last = lastRefreshTimes.get(key);
        if (last == null && yearTerm != null && !yearTerm.isBlank()) {
            Term term = termRepository.findByYearTerm(yearTerm.toLowerCase()).orElse(null);
            if (term != null) {
                CourseOffering co = courseOfferingRepository.findByCourseIdAndTermId(courseId, term.getId()).orElse(null);
                if (co != null && co.getLastRefreshedAt() != null) {
                    last = co.getLastRefreshedAt();
                }
            }
        }

        if (last == null) {
            return new CourseRefreshStatus(true, "Ready to refresh", 0, null, 0);
        }
        long elapsedSeconds = java.time.Duration.between(last, java.time.Instant.now()).getSeconds();
        long remaining = Math.max(0, 900 - elapsedSeconds);
        return new CourseRefreshStatus(remaining == 0, remaining == 0 ? "Ready to refresh" : "On cooldown", remaining, last, 0);
    }

    @org.springframework.transaction.annotation.Transactional
    public CourseRefreshStatus refreshCourseSections(Long courseId, String yearTerm) {

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new com.illini.grades.exception.ResourceNotFoundException("Course not found: " + courseId));

        Term term;
        if (yearTerm != null && !yearTerm.isBlank()) {
            term = termRepository.findByYearTerm(yearTerm.toLowerCase())
                    .orElseThrow(() -> new com.illini.grades.exception.ResourceNotFoundException("Term not found: " + yearTerm));
        } else {
            term = termRepository.findAllByOrderByYearAscSeasonAsc().stream()
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new com.illini.grades.exception.ResourceNotFoundException("No terms available"));
        }

        String key = courseId + ":" + term.getYearTerm().toLowerCase();
        java.time.Instant last = lastRefreshTimes.get(key);
        if (last == null) {
            CourseOffering co = courseOfferingRepository.findByCourseIdAndTermId(course.getId(), term.getId()).orElse(null);
            if (co != null && co.getLastRefreshedAt() != null) {
                last = co.getLastRefreshedAt();
            }
        }
        if (last != null) {
            long elapsedSeconds = java.time.Duration.between(last, java.time.Instant.now()).getSeconds();
            if (elapsedSeconds < 900) {
                long remaining = 900 - elapsedSeconds;
                return new CourseRefreshStatus(false, "Rate limit reached. Please wait " + (remaining / 60 + 1) + " minutes before refreshing this course again.", remaining, last, 0);
            }
        }

        String subjectCode = course.getSubject().getCode();
        String numberStr = String.valueOf(course.getNumber());
        int year = term.getYear();
        String seasonLower = term.getSeason().toLowerCase();

        FetchResult courseRes = fetch(BASE + "/" + year + "/" + seasonLower + "/" + subjectCode + "/" + numberStr + ".xml");
        if (courseRes.status() != 200) {
            return new CourseRefreshStatus(false, "Course not found on UIUC Course Explorer for " + term.getYearTerm() + " (HTTP " + courseRes.status() + ")", 0, null, 0);
        }

        List<String> crns;
        try {
            Element courseRoot = parse(courseRes.body()).getDocumentElement();
            crns = attrValues(child(courseRoot, "sections"), "section", "id");
        } catch (Exception e) {
            return new CourseRefreshStatus(false, "Failed to parse course section list: " + e.getMessage(), 0, null, 0);
        }

        CourseOffering courseOffering = courseOfferingRepository.findByCourseIdAndTermId(course.getId(), term.getId())
                .orElseGet(() -> {
                    CourseOffering co = new CourseOffering();
                    co.setCourse(course);
                    co.setTerm(term);
                    return courseOfferingRepository.save(co);
                });

        int upserted = 0;
        List<String> seenCrns = new ArrayList<>();
        for (String crn : crns) {
            sleepBetweenRequests();
            FetchResult secRes = fetch(BASE + "/" + year + "/" + seasonLower + "/" + subjectCode + "/" + numberStr + "/" + crn + ".xml");
            if (secRes.status() != 200) continue;
            try {
                Element secRoot = parse(secRes.body()).getDocumentElement();
                ParsedSection parsed = parseSection(secRoot);
                writer.upsert(courseOffering, crn, parsed);
                seenCrns.add(crn);
                upserted++;
            } catch (Exception e) {
                System.err.println("Error upserting CRN " + crn + ": " + e.getMessage());
            }
        }

        if (!seenCrns.isEmpty()) {
            scheduledSectionRepository.deleteStale(courseOffering.getId(), seenCrns);
        }

        java.time.Instant now = java.time.Instant.now();
        courseOffering.setLastRefreshedAt(now);
        courseOfferingRepository.save(courseOffering);
        lastRefreshTimes.put(key, now);
        return new CourseRefreshStatus(true, "Successfully refreshed " + upserted + " sections from UIUC Course Explorer", 900, now, upserted);
    }

    private ParsedSection parseSection(Element sectionRoot) {
        String sectionNumber = text(sectionRoot, "sectionNumber");
        String statusCode = text(sectionRoot, "statusCode");
        String partOfTerm = text(sectionRoot, "partOfTerm");
        LocalDate startDate = parseDate(text(sectionRoot, "startDate"));
        LocalDate endDate = parseDate(text(sectionRoot, "endDate"));

        String notes = java.util.stream.Stream.of(
                        text(sectionRoot, "sectionText"),
                        text(sectionRoot, "sectionNotes"),
                        text(sectionRoot, "sectionCappArea")
                )
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse(null);

        List<ParsedMeeting> meetings = new ArrayList<>();
        for (Element meetingEl : children(child(sectionRoot, "meetings"), "meeting")) {
            String startTime = text(meetingEl, "start");
            String endTime = text(meetingEl, "end");
            String daysOfWeek = text(meetingEl, "daysOfTheWeek");
            String roomNumber = text(meetingEl, "roomNumber");
            String buildingName = text(meetingEl, "buildingName");
            Element typeEl = child(meetingEl, "type");
            String typeCode = typeEl != null ? typeEl.getAttribute("code") : null;
            String typeDescription = typeEl != null ? typeEl.getTextContent().trim() : null;

            List<String> instructorNames = new ArrayList<>();
            for (Element instrEl : children(child(meetingEl, "instructors"), "instructor")) {
                String name = instrEl.getTextContent();
                if (name != null && !name.isBlank()) instructorNames.add(name.trim());
            }

            meetings.add(new ParsedMeeting(startTime, endTime, daysOfWeek, roomNumber, buildingName, typeCode, typeDescription, instructorNames));
        }

        return new ParsedSection(sectionNumber, statusCode, partOfTerm, startDate, endDate, notes, meetings);
    }

    private record FetchResult(int status, String body) {}

    public record ParsedMeeting(String startTime, String endTime, String daysOfWeek, String roomNumber,
                                 String buildingName, String typeCode, String typeDescription, List<String> instructorNames) {}

    public record ParsedSection(String sectionNumber, String statusCode, String partOfTerm, LocalDate startDate,
                                 LocalDate endDate, String notes, List<ParsedMeeting> meetings) {}
}

