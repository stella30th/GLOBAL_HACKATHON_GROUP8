package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.dto.JobDto;
import com.gbhackathon.AICareerCode.model.JobOpportunity;
import com.gbhackathon.AICareerCode.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(origins = "*")
public class JobController {

    private final JobService jobService;
    private final com.gbhackathon.AICareerCode.service.ExternalJobService externalJobService;

    public JobController(JobService jobService, com.gbhackathon.AICareerCode.service.ExternalJobService externalJobService) {
        this.jobService = jobService;
        this.externalJobService = externalJobService;
    }

    /**
     * Reports what each job board actually returned. A source can work from a developer machine and
     * be blocked from the deployment's datacenter IP, which a job count alone cannot distinguish
     * from "no matching jobs".
     */
    @GetMapping("/source-status")
    public ResponseEntity<?> getSourceStatus() {
        return ResponseEntity.ok(externalJobService.probeSources());
    }

    @PostMapping("/sync-external")
    public ResponseEntity<?> syncExternalJobs() {
        return ResponseEntity.ok(externalJobService.syncExternalJobs());
    }

    @GetMapping
    public ResponseEntity<List<JobDto>> getJobs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean isOverseas,
            @RequestParam(required = false) String workType,
            @RequestParam(required = false) Boolean visaSponsorship
    ) {
        List<JobOpportunity> jobs = jobService.searchJobs(keyword, isOverseas, workType, visaSponsorship);
        return ResponseEntity.ok(jobService.toDtoList(jobs));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDto> getJobById(@PathVariable Long id) {
        return jobService.getJobById(id)
                .map(job -> ResponseEntity.ok(jobService.toDto(job)))
                .orElse(ResponseEntity.notFound().build());
    }
}
