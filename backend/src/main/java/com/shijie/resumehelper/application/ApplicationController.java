package com.shijie.resumehelper.application;
import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService service;
    public ApplicationController(ApplicationService service){this.service=service;}
    @ExceptionHandler(ApplicationProblem.class)
    public ResponseEntity<ApiResponse<Void>> problem(ApplicationProblem e){
        return ResponseEntity.status(e.status()).body(ApiResponse.fail(e.getMessage()));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidQuery(Exception e){
        return ResponseEntity.badRequest().body(ApiResponse.fail("筛选或分页参数无效"));
    }
    @GetMapping public ApiResponse<ApplicationService.Page> list(@RequestParam(required=false) ApplicationRecord.Status status,
            @RequestParam(defaultValue="false") boolean due,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="30") int limit,HttpServletRequest request){
        return ApiResponse.ok(service.list(UserContext.resolve(request),status,due,offset,limit));
    }
    @PostMapping public ApiResponse<ApplicationRecord> create(@RequestBody ApplicationService.Create body,HttpServletRequest request){
        return ApiResponse.ok(service.create(UserContext.resolve(request),body));
    }
    @GetMapping("/{id}") public ApiResponse<ApplicationRecord> get(@PathVariable String id,HttpServletRequest request){
        return ApiResponse.ok(service.get(UserContext.resolve(request),id));
    }
    @PatchMapping("/{id}/status") public ApiResponse<ApplicationRecord> change(@PathVariable String id,
            @RequestBody ApplicationService.Change body,HttpServletRequest request){
        return ApiResponse.ok(service.change(UserContext.resolve(request),id,body,false));
    }
    @GetMapping("/{id}/events") public ApiResponse<List<ApplicationRecord.Event>> events(@PathVariable String id,HttpServletRequest request){
        return ApiResponse.ok(service.get(UserContext.resolve(request),id).events());
    }
    @PostMapping("/{id}/events") public ApiResponse<ApplicationRecord> addEvent(@PathVariable String id,
            @RequestBody ApplicationService.Change body,HttpServletRequest request){
        return ApiResponse.ok(service.change(UserContext.resolve(request),id,body,true));
    }
    @DeleteMapping("/{id}") public ApiResponse<Map<String,Boolean>> delete(@PathVariable String id,@RequestParam long version,HttpServletRequest request){
        service.delete(UserContext.resolve(request),id,version);return ApiResponse.ok(Map.of("deleted",true));
    }
}
