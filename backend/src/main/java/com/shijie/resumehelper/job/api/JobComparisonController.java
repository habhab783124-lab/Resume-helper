package com.shijie.resumehelper.job.api;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.job.service.JobComparisonService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs/compare")
public class JobComparisonController {
    private final JobComparisonService service;
    public JobComparisonController(JobComparisonService service) { this.service=service; }
    @PostMapping
    public ApiResponse<JobComparisonService.Comparison> compare(
            @RequestBody JobComparisonService.Request request,HttpServletRequest http) {
        return ApiResponse.ok(service.compare(UserContext.resolve(http),request));
    }
}
