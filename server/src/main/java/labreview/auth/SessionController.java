package labreview.auth;

import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SessionController {
    @GetMapping("/api/session")
    ResponseEntity<Session> session(Principal principal, CsrfToken csrf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Session(
                principal == null ? null : principal.getName(), csrf.getToken(), csrf.getHeaderName()));
    }

    record Session(String username, String csrfToken, String csrfHeader) {}
}
