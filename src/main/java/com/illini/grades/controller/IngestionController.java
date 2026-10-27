package com.illini.grades.controller;

import com.illini.grades.service.IngestionService;
import com.illini.grades.service.SectionScheduleIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
@Tag(name = "Admin")
public class IngestionController {

    private final IngestionService ingestionService;
    private final SectionScheduleIngestionService sectionScheduleIngestionService;

    public IngestionController(IngestionService ingestionService, SectionScheduleIngestionService sectionScheduleIngestionService) {
        this.ingestionService = ingestionService;
        this.sectionScheduleIngestionService = sectionScheduleIngestionService;
    }

    @PostMapping("/ingest")
    @Operation(summary = "Ingest CSV data")
    public Map<String, Object> ingest(@RequestParam("file") MultipartFile file) {
        try {
            ingestionService.ingest(file.getInputStream());
            return Map.of("message", "Ingested successfully", "errors", new String[]{});
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/ingest-gen-ed")
    @Operation(summary = "Backfill gen ed categories for all existing courses from the UIUC Course Explorer catalog")
    public Map<String, Object> backfillGenEd() {
        if (!sectionScheduleIngestionService.startGenEdBackfill()) {
            return Map.of("error", "A gen ed backfill is already running. Check server logs for progress.");
        }
        return Map.of("message", "Gen ed backfill started. This makes one request per course and runs in the background -- check server logs for progress.");
    }

    @PostMapping("/ingest-sections")
    @Operation(summary = "Ingest/refresh live section schedules for a term from the UIUC Course Explorer")
    public Map<String, Object> ingestSections(@RequestParam int year, @RequestParam String season) {
        if (!SectionScheduleIngestionService.isValidSeason(season)) {
            return Map.of("error", "season must be one of: spring, summer, fall, winter");
        }
        boolean started = sectionScheduleIngestionService.start(year, season);
        if (!started) {
            return Map.of("error", "A section ingestion is already running. Check server logs for progress.");
        }
        return Map.of("message", "Section ingestion started for " + season + " " + year
                + ". This runs in the background (can take a while) -- check server logs for progress.");
    }
}
