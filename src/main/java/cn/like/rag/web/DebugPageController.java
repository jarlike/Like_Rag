package cn.like.rag.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DebugPageController {

    @GetMapping(value = {"/", "/debug"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> debug() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("debug.html"));
    }
}
